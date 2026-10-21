"""HTTP side of the simulator: which service serves which path, sign-in with a shared rate budget, token refresh."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Any

import httpx

log = logging.getLogger(__name__)

# Local development: each service on its own port (the same prefixes Caddy and the Vite proxy route).
LOCAL_ROUTES = [
    ("/api/admin/payments", "http://localhost:8084"),
    ("/api/admin/stats", "http://localhost:8081"),
    ("/api/admin/demand", "http://localhost:8081"),
    ("/api/admin", "http://localhost:8082"),
    ("/api/payments", "http://localhost:8084"),
    ("/api/drivers", "http://localhost:8082"),
    ("/api/tracking", "http://localhost:8083"),
    ("/api", "http://localhost:8081"),
]


class Endpoints:
    """`api=None`: local ports. `api="https://host"`: one origin that routes by prefix (Caddy, Vite)."""

    def __init__(self, api: str | None = None, ws: str | None = None):
        self.api = api.rstrip("/") if api else None
        self.ws = ws or (self.api.replace("http", "ws", 1) + "/ws" if self.api else "ws://localhost:8083/ws")

    def url(self, path: str) -> str:
        if self.api:
            return self.api + path
        for prefix, base in LOCAL_ROUTES:
            if path.startswith(prefix):
                return base + path
        raise ValueError(path)


class ApiError(Exception):
    def __init__(self, status: int, detail: str, retry_after: float | None = None):
        super().__init__(f"{status}: {detail}")
        self.status = status
        self.detail = detail
        self.retry_after = retry_after


class AuthBudget:
    """ride-service allows 20 sign-ins a minute per IP. All simulated drivers share one budget below
    that (default 15/min), so the rider in your browser can still sign in while 50 drivers start."""

    def __init__(self, per_minute: int):
        self.interval = 60.0 / per_minute
        self._next = 0.0
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            wait = self._next - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            self._next = max(self._next, time.monotonic()) + self.interval

    async def back_off(self, seconds: float) -> None:
        async with self._lock:
            self._next = max(self._next, time.monotonic() + seconds)


@dataclass
class Tokens:
    access: str
    refresh: str
    expires_at: float  # monotonic seconds
    user_id: str
    name: str


class Api:
    def __init__(self, endpoints: Endpoints, budget: AuthBudget, client: httpx.AsyncClient):
        self.endpoints = endpoints
        self.budget = budget
        self.http = client

    async def _auth_call(self, path: str, body: dict[str, Any]) -> Tokens:
        while True:
            await self.budget.acquire()
            res = await self.http.post(self.endpoints.url(path), json=body)
            if res.status_code == 429:
                wait = float(res.headers.get("Retry-After", "60"))
                log.warning("sign-in rate limit hit, every driver waits %.0f s", wait)
                await self.budget.back_off(wait)
                continue
            if res.status_code >= 400:
                raise ApiError(res.status_code, _detail(res))
            data = res.json()
            return Tokens(data["accessToken"], data["refreshToken"], time.monotonic() + data["expiresIn"],
                          data["user"]["id"], data["user"]["name"])

    async def login(self, email: str, password: str) -> Tokens:
        return await self._auth_call("/api/auth/login", {"email": email, "password": password})

    async def refresh(self, tokens: Tokens) -> Tokens:
        return await self._auth_call("/api/auth/refresh", {"refreshToken": tokens.refresh})


class Session:
    """One signed-in driver: keeps the access token fresh (they last 15 minutes)."""

    def __init__(self, api: Api, email: str, password: str):
        self.api = api
        self.email = email
        self.password = password
        self.tokens: Tokens | None = None
        # one refresh at a time: two tasks refreshing with the same (single-use) refresh token look like
        # token theft to ride-service, which then revokes every session of the driver
        self._lock = asyncio.Lock()

    async def token(self) -> str:
        async with self._lock:
            if self.tokens is None:
                self.tokens = await self.api.login(self.email, self.password)
            elif self.tokens.expires_at - time.monotonic() < 90:
                try:
                    self.tokens = await self.api.refresh(self.tokens)
                except ApiError:
                    self.tokens = await self.api.login(self.email, self.password)
            return self.tokens.access

    async def call(self, method: str, path: str, body: dict[str, Any] | None = None,
                   headers: dict[str, str] | None = None) -> Any:
        """JSON body of a 2xx (None for 204); raises ApiError otherwise. Retries once after a refresh on 401."""
        for attempt in (1, 2):
            token = await self.token()
            res = await self.api.http.request(method, self.api.endpoints.url(path), json=body,
                                              headers={"Authorization": f"Bearer {token}", **(headers or {})})
            if res.status_code == 401 and attempt == 1 and self.tokens:
                self.tokens.expires_at = 0  # force a refresh
                continue
            if res.status_code >= 400:
                retry = res.headers.get("Retry-After")
                raise ApiError(res.status_code, _detail(res), float(retry) if retry else None)
            return res.json() if res.content else None
        raise AssertionError("unreachable")


# one cheap request per service; any answer below 500 means it is up (401 without a token is fine)
READY_PATHS = (
    "/api/me",
    "/api/drivers/me",
    "/api/tracking/rides/00000000-0000-0000-0000-000000000000/path",
    "/api/payments/config",
)


async def wait_for_backend(http: httpx.AsyncClient, endpoints: Endpoints, timeout_s: float = 300.0,
                           interval_s: float = 2.0) -> bool:
    """Holds the city back until every service answers. After a reboot Docker starts every container at
    once, and sign-ins sent before the services are up only fail (Caddy answers 502). False on timeout."""
    deadline = time.monotonic() + timeout_s
    waiting = list(READY_PATHS)
    announced = False
    while True:
        still = []
        for path in waiting:
            try:
                up = (await http.get(endpoints.url(path), timeout=5)).status_code < 500
            except httpx.HTTPError:
                up = False
            if not up:
                still.append(path)
        waiting = still
        if not waiting:
            return True
        if time.monotonic() >= deadline:
            log.warning("services still not answering after %.0f s (%s): starting anyway", timeout_s, ", ".join(waiting))
            return False
        if not announced:
            log.info("waiting for the services to answer (%s)...", ", ".join(waiting))
            announced = True
        await asyncio.sleep(interval_s)


def _detail(res: httpx.Response) -> str:
    try:
        body = res.json()
        return str(body.get("detail") or body.get("title") or res.reason_phrase)
    except ValueError:
        return res.reason_phrase
