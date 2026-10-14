"""A minimal STOMP 1.2 client over a plain WebSocket (what tracking-service speaks, see contracts/websocket.md)."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from websockets.asyncio.client import ClientConnection, connect

log = logging.getLogger(__name__)

Handler = Callable[[dict[str, Any]], Awaitable[None]]


@dataclass
class Frame:
    command: str
    headers: dict[str, str] = field(default_factory=dict)
    body: str = ""

    def encode(self) -> str:
        lines = [self.command]
        # STOMP 1.2: CONNECT frames are never escaped
        esc = (lambda v: v) if self.command == "CONNECT" else _escape
        lines += [f"{k}:{esc(v)}" for k, v in self.headers.items()]
        return "\n".join(lines) + "\n\n" + self.body + "\x00"

    @staticmethod
    def decode(raw: str) -> Frame | None:
        """None for a heart-beat (a bare newline)."""
        raw = raw.lstrip("\r\n")
        if not raw:
            return None
        head, _, body = raw.partition("\n\n")
        lines = head.split("\n")
        headers: dict[str, str] = {}
        for line in lines[1:]:
            key, _, value = line.rstrip("\r").partition(":")
            headers.setdefault(key, _unescape(value))  # STOMP: the first occurrence wins
        return Frame(lines[0].rstrip("\r"), headers, body.split("\x00", 1)[0])


def _escape(v: str) -> str:
    return v.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace(":", "\\c")


def _unescape(v: str) -> str:
    out, i = [], 0
    while i < len(v):
        if v[i] == "\\" and i + 1 < len(v):
            out.append({"\\": "\\", "r": "\r", "n": "\n", "c": ":"}.get(v[i + 1], v[i + 1]))
            i += 2
        else:
            out.append(v[i])
            i += 1
    return "".join(out)


class StompError(Exception):
    pass


class StompClient:
    """One connection. Use `async with StompClient(url, token) as c:`; `c.closed` resolves when it drops."""

    HEARTBEAT_MS = 10_000

    def __init__(self, url: str, token: str):
        self.url = url
        self.token = token
        self._ws: ClientConnection | None = None
        self._handlers: dict[str, Handler] = {}
        self._tasks: list[asyncio.Task[None]] = []
        self._next_id = 0
        self.closed: asyncio.Future[str] = asyncio.get_running_loop().create_future()

    async def __aenter__(self) -> StompClient:
        self._ws = await connect(self.url, open_timeout=10, ping_interval=None, max_size=2**20)
        await self._send(Frame("CONNECT", {
            "accept-version": "1.2",
            "host": "rydex",
            "heart-beat": f"{self.HEARTBEAT_MS},{self.HEARTBEAT_MS}",
            "Authorization": f"Bearer {self.token}",
        }))
        reply = Frame.decode(str(await asyncio.wait_for(self._ws.recv(), 10)))
        if reply is None or reply.command != "CONNECTED":
            message = reply.headers.get("message", "no CONNECTED frame") if reply else "no reply"
            await self._ws.close()
            raise StompError(message)
        self._tasks = [asyncio.create_task(self._read()), asyncio.create_task(self._beat())]
        return self

    async def __aexit__(self, *exc: object) -> None:
        for t in self._tasks:
            t.cancel()
        if self._ws is not None:
            with contextlib.suppress(Exception):
                await self._send(Frame("DISCONNECT"))
            with contextlib.suppress(Exception):
                await self._ws.close()
        self._finish("closed by us")

    async def subscribe(self, destination: str, handler: Handler) -> None:
        self._next_id += 1
        sub_id = f"sub-{self._next_id}"
        self._handlers[sub_id] = handler
        await self._send(Frame("SUBSCRIBE", {"id": sub_id, "destination": destination, "ack": "auto"}))

    async def send(self, destination: str, body: dict[str, Any]) -> None:
        await self._send(Frame("SEND", {"destination": destination, "content-type": "application/json"}, json.dumps(body)))

    async def _send(self, frame: Frame) -> None:
        assert self._ws is not None
        await self._ws.send(frame.encode())

    async def _beat(self) -> None:
        while True:
            await asyncio.sleep(self.HEARTBEAT_MS / 1000)
            assert self._ws is not None
            await self._ws.send("\n")

    async def _read(self) -> None:
        assert self._ws is not None
        try:
            async for raw in self._ws:
                frame = Frame.decode(str(raw))
                if frame is None:
                    continue
                if frame.command == "MESSAGE":
                    handler = self._handlers.get(frame.headers.get("subscription", ""))
                    if handler:
                        try:
                            await handler(json.loads(frame.body))
                        except Exception:  # a bad handler must not kill the connection
                            log.exception("handler failed for %s", frame.headers.get("destination"))
                elif frame.command == "ERROR":
                    self._finish("server error: " + frame.headers.get("message", frame.body))
                    return
            self._finish("connection closed")
        except Exception as e:  # network drop
            self._finish(f"connection lost: {e}")

    def _finish(self, reason: str) -> None:
        if not self.closed.done():
            self.closed.set_result(reason)
