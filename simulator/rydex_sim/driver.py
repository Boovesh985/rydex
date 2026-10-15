"""One simulated driver: signs in, goes online, cruises, takes offers, drives the trip, gets paid."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import random
import time
from dataclasses import dataclass, field
from typing import Any

from .api import ApiError, Endpoints, Session
from .geo import LatLng, along, decode_polyline, haversine, path_length, random_point
from .otp import otp_for
from .places import PLACES, near
from .rider import PinBoard
from .stomp import StompClient
from .traffic import RoadIndex

log = logging.getLogger("rydex.sim")

TICK_S = 1.0  # how often the car moves
REPORT_S = 3.0  # how often it tells the server (contracts/websocket.md: every ~3 s)


@dataclass
class Settings:
    center: LatLng
    radius_m: float = 4000
    accept_rate: float = 0.85
    speedup: float = 1.0
    trip_kmh: float = 40
    cruise_kmh: float = 22
    cruise: bool = True
    otp_secret: str | None = None
    # seconds a driver waits at the pickup for the rider to read out their PIN (real time, not sped up);
    # a rider who is a real person cannot reach the board, so after this the driver uses the dev PIN
    board_s: float = 20.0
    # NPC riders read out their PIN here (living city, ADR 0016)
    board: PinBoard | None = None
    # main roads with simulated traffic: cars slow down in jams
    roads: RoadIndex | None = None
    # cruise between real places (streets near them) instead of random points
    cruise_places: bool = False


@dataclass
class Stats:
    online: int = 0
    on_trip: int = 0
    offers: int = 0
    accepted: int = 0
    declined: int = 0
    completed: int = 0
    earned: float = 0.0
    errors: int = 0
    started: float = field(default_factory=time.monotonic)

    def line(self) -> str:
        mins = (time.monotonic() - self.started) / 60
        return (f"{self.online} online ({self.on_trip} on a trip) | offers {self.offers}: {self.accepted} accepted, "
                f"{self.declined} declined | {self.completed} trips done, Rs {self.earned:.0f} earned | "
                f"{self.errors} errors | {mins:.0f} min")


class TripOver(Exception):
    """The current ride ended early (the rider cancelled, or it is gone)."""


class SimDriver:
    def __init__(self, label: str, session: Session, endpoints: Endpoints, settings: Settings, stats: Stats,
                 rng: random.Random):
        self.label = label
        self.session = session
        self.endpoints = endpoints
        self.s = settings
        self.stats = stats
        self.rng = rng
        self.pos: LatLng = random_point(settings.center, settings.radius_m, rng)
        self.heading = 0.0
        self.speed = 0.0  # m/s
        self.ride_id: str | None = None
        self.online = False
        self.stomp: StompClient | None = None
        self.offers: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.cancelled = asyncio.Event()
        self.connected = asyncio.Event()
        self.last_offer: str | None = None

    # ------------------------------------------------------------------ lifecycle

    async def run(self) -> None:
        me = await self.session.call("GET", "/api/drivers/me")
        vehicle = me["vehicleType"]
        if me.get("lastLocation"):
            last = (me["lastLocation"]["lat"], me["lastLocation"]["lng"])
            if haversine(last, self.s.center) < self.s.radius_m * 1.5:
                self.pos = last
        if self.s.roads is not None and not self.s.roads.on_land(self.pos):
            # a start (or an old position) off every road, e.g. in the sea: start on a street instead
            self.pos = self._cruise_target()
        background = [asyncio.create_task(self._connection()), asyncio.create_task(self._reporter())]
        try:
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self.connected.wait(), 15)
            resume = me.get("currentRideId")
            if me["status"] != "ON_TRIP":
                await self.session.call("PATCH", "/api/drivers/me/status",
                                        {"status": "AVAILABLE", "lat": self.pos[0], "lng": self.pos[1]})
            self.online = True
            self.stats.online += 1
            log.info("%s %s online at %.4f,%.4f", self.label, vehicle, *self.pos)
            if resume:
                await self._resume(resume)
            while True:
                offer = await self._idle()
                assignment = await self._consider(offer)
                if assignment:
                    await self._trip(assignment["rideId"], _pt(assignment["pickup"]), _pt(assignment["drop"]))
        finally:
            for t in background:
                t.cancel()
            if self.online:
                self.stats.online -= 1

    async def go_offline(self) -> None:
        if self.online and self.ride_id is None:
            with contextlib.suppress(Exception):
                await self.session.call("PATCH", "/api/drivers/me/status", {"status": "OFFLINE"})

    # ------------------------------------------------------------------ connection and heartbeats

    async def _connection(self) -> None:
        """Keeps one STOMP connection up; reconnects with backoff and before the token expires."""
        backoff = 1.0
        while True:
            planned = False
            try:
                token = await self.session.token()
                async with StompClient(self.endpoints.ws, token) as c:
                    await c.subscribe("/user/queue/offers", self._on_offer_message)
                    await c.subscribe("/user/queue/notifications", self._on_notification)
                    await c.subscribe("/user/queue/errors", self._on_error)
                    self.stomp = c
                    self.connected.set()
                    backoff = 1.0
                    await self._catch_up()
                    assert self.session.tokens is not None
                    ttl = self.session.tokens.expires_at - time.monotonic() - 90
                    try:
                        reason = await asyncio.wait_for(asyncio.shield(c.closed), timeout=max(10.0, ttl))
                        log.debug("%s socket closed: %s", self.label, reason)
                    except asyncio.TimeoutError:
                        planned = True  # reconnect with a fresh token
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.debug("%s cannot connect: %s", self.label, e)
            self.stomp = None
            if not planned:
                await asyncio.sleep(backoff * self.rng.uniform(0.8, 1.2))
                backoff = min(30.0, backoff * 2)

    async def _catch_up(self) -> None:
        """Pushes are not replayed after a reconnect: ask for an open offer over REST."""
        if self.online and self.ride_id is None:
            with contextlib.suppress(ApiError):
                offer = await self.session.call("GET", "/api/drivers/me/offer")
                if offer:
                    await self.offers.put({**offer, "_received": time.monotonic()})

    async def _reporter(self) -> None:
        while True:
            body = {"lat": round(self.pos[0], 6), "lng": round(self.pos[1], 6), "heading": round(self.heading) % 360,
                    "speed": round(min(self.speed, 100.0), 1), "ts": int(time.time() * 1000)}
            try:
                if self.stomp is not None and not self.stomp.closed.done():
                    await self.stomp.send("/app/driver.location", body)
                elif self.online:
                    await self.session.call("POST", "/api/drivers/me/location",
                                            {k: body[k] for k in ("lat", "lng", "heading", "speed")})
            except Exception as e:
                log.debug("%s location not sent: %s", self.label, e)
            await asyncio.sleep(REPORT_S)

    async def _on_offer_message(self, m: dict[str, Any]) -> None:
        if m.get("type") == "OFFER" and m.get("offer"):
            await self.offers.put({**m["offer"], "_received": time.monotonic()})

    async def _on_notification(self, n: dict[str, Any]) -> None:
        if n.get("type") == "RIDE_CANCELLED" and n.get("rideId") == self.ride_id:
            self.cancelled.set()

    async def _on_error(self, e: dict[str, Any]) -> None:
        log.warning("%s server says %s: %s", self.label, e.get("code"), e.get("message"))

    # ------------------------------------------------------------------ waiting for work

    async def _idle(self) -> dict[str, Any]:
        """Cruise around (or stay parked) until an offer arrives."""
        while True:
            if not self.offers.empty():
                return self.offers.get_nowait()
            if self.s.cruise:
                # looking up the route is part of the cruise: an offer must not wait for OSRM
                drive = asyncio.create_task(self._cruise())
            else:
                drive = asyncio.create_task(asyncio.sleep(3600))
            get = asyncio.create_task(self.offers.get())
            done, _ = await asyncio.wait({drive, get}, return_when=asyncio.FIRST_COMPLETED)
            if get in done:
                drive.cancel()
                self.speed = 0.0
                return get.result()
            get.cancel()
            await asyncio.sleep(self.rng.uniform(2, 15))  # a short stop before the next loop

    async def _cruise(self) -> None:
        route = await self._route(self.pos, self._cruise_target(), straight_if_down=False)
        if route is None:
            # no route (OSRM busy): stay parked a while rather than drive through buildings
            self.speed = 0.0
            await asyncio.sleep(self.rng.uniform(20, 45))
            return
        await self._drive(route, self.s.cruise_kmh)

    def _cruise_target(self) -> LatLng:
        if self.s.cruise_places:
            places = [p for p in PLACES if haversine(p.point, self.s.center) <= self.s.radius_m] or list(PLACES)
            return near(self.rng.choice(places), self.rng, 700, self.s.roads)
        return random_point(self.s.center, self.s.radius_m, self.rng)

    async def _consider(self, offer: dict[str, Any]) -> dict[str, Any] | None:
        ride_id = offer["rideId"]
        if ride_id == self.last_offer:
            return None  # the same offer twice (pushed, and read again after a reconnect)
        self.last_offer = ride_id
        self.stats.offers += 1
        # the offer's clock started when it arrived, not now
        left = float(offer.get("secondsLeft", 15)) - (time.monotonic() - offer.get("_received", time.monotonic()))
        if left < 1.5:
            log.info("%s let offer %s lapse (it waited too long)", self.label, ride_id[:8])
            return None
        await asyncio.sleep(min(self.rng.uniform(1.0, 4.0), max(0.0, left - 2.5)))
        take = self.rng.random() < self.s.accept_rate
        try:
            if take:
                assignment = await self.session.call("POST", f"/api/drivers/me/offers/{ride_id}/accept")
                self.stats.accepted += 1
                log.info("%s accepted ride %s (Rs %s, pickup %.0f m away)", self.label, ride_id[:8], offer.get("estFare"),
                         offer.get("distanceToPickupM", 0))
                return assignment
            await self.session.call("POST", f"/api/drivers/me/offers/{ride_id}/reject")
            self.stats.declined += 1
            log.info("%s declined ride %s", self.label, ride_id[:8])
        except ApiError as e:
            log.info("%s offer %s no longer open (%s)", self.label, ride_id[:8], e.detail)
        return None

    # ------------------------------------------------------------------ the trip

    async def _resume(self, ride_id: str) -> None:
        ride = await self.session.call("GET", f"/api/rides/{ride_id}")
        if ride["status"] in ("DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_PROGRESS"):
            log.info("%s resuming ride %s (%s)", self.label, ride_id[:8], ride["status"])
            await self._trip(ride_id, _pt(ride["pickup"]), _pt(ride["drop"]), ride["status"])

    async def _trip(self, ride_id: str, pickup: LatLng, drop: LatLng, status: str = "DRIVER_ASSIGNED") -> None:
        self.ride_id = ride_id
        self.cancelled.clear()
        self.stats.on_trip += 1
        short = ride_id[:8]
        try:
            if status == "DRIVER_ASSIGNED":
                await self._drive_or_cancel(await self._route(self.pos, pickup), self.s.trip_kmh)
                await self._arrive(ride_id, pickup)
                log.info("%s arrived at pickup for %s, waiting for the rider's PIN", self.label, short)
                status = "DRIVER_ARRIVED"
            if status == "DRIVER_ARRIVED":
                # the rider walks up and reads out the PIN on their screen
                if not await self._start(ride_id, await self._hear_pin(ride_id)):
                    return
                status = "IN_PROGRESS"
            await self._drive_or_cancel(await self._route(self.pos, drop), self.s.trip_kmh)
            ride = await self._step("complete", ride_id)
            fare = float(ride.get("finalFare") or ride.get("estFare") or 0)
            self.stats.completed += 1
            self.stats.earned += fare
            log.info("%s completed %s, fare Rs %.0f", self.label, short, fare)
        except TripOver as e:
            log.info("%s ride %s ended: %s", self.label, short, e)
        finally:
            self.ride_id = None
            self.speed = 0.0
            self.stats.on_trip -= 1

    async def _hear_pin(self, ride_id: str) -> str | None:
        """The PIN an NPC rider reads out; for a real rider (nobody on the board), after the boarding
        wait, the one derived from the development OTP secret, or None without it."""
        wait = self.s.board_s * self.rng.uniform(0.8, 1.2)
        if self.s.board is not None:
            hear = asyncio.create_task(self.s.board.hear(ride_id, wait))
            cancel = asyncio.create_task(self.cancelled.wait())
            done, _ = await asyncio.wait({hear, cancel}, return_when=asyncio.FIRST_COMPLETED)
            hear.cancel()
            cancel.cancel()
            self.s.board.forget(ride_id)
            if cancel in done:
                raise TripOver("the rider cancelled")
            pin = hear.result()
            if pin:
                log.info("%s heard the PIN from the rider of %s", self.label, ride_id[:8])
                return pin
        else:
            await self._wait_or_cancel(wait)
        return otp_for(ride_id, self.s.otp_secret) if self.s.otp_secret else None

    async def _start(self, ride_id: str, pin: str | None) -> bool:
        """Starts the trip with the PIN; without one it can only wait for the rider to cancel."""
        if pin is None:
            log.warning("%s cannot start %s: nobody read out the PIN and there is no OTP secret (--otp-secret); "
                        "waiting for the rider to cancel", self.label, ride_id[:8])
            await self._wait_or_cancel(3600)
            return False
        try:
            await self.session.call("POST", f"/api/rides/{ride_id}/start", {"otp": pin})
            log.info("%s started trip %s", self.label, ride_id[:8])
            return True
        except ApiError as e:
            if e.status == 400 or e.status == 422:
                self.stats.errors += 1
                log.warning("%s wrong PIN for %s (%s): --otp-secret must match ride-service's OTP_SECRET",
                            self.label, ride_id[:8], e.detail)
                await self._wait_or_cancel(3600)
                return False
            raise TripOver(e.detail) from e

    async def _arrive(self, ride_id: str, pickup: LatLng) -> None:
        """ride-service only accepts an arrival near the pickup: let the reporter send the parked
        position first. If the road ended short of the pin (a pin inside a campus or a courtyard),
        creep the last stretch slowly, then try again."""
        await self._wait_or_cancel(REPORT_S + 0.5)
        try:
            await self.session.call("POST", f"/api/rides/{ride_id}/arrived")
        except ApiError as e:
            if e.status != 409 or "from the pickup" not in (e.detail or ""):
                await self._step_failed(e)
            if haversine(self.pos, pickup) > 150:
                await self._drive_or_cancel([self.pos, pickup], 12)
            await self._wait_or_cancel(REPORT_S + 0.5)
            await self._step("arrived", ride_id)

    async def _step(self, action: str, ride_id: str) -> dict[str, Any]:
        try:
            return await self.session.call("POST", f"/api/rides/{ride_id}/{action}")
        except ApiError as e:
            await self._step_failed(e)
            raise

    @staticmethod
    async def _step_failed(e: ApiError) -> None:
        if e.status in (404, 409):
            raise TripOver(e.detail) from e
        raise e

    async def _wait_or_cancel(self, seconds: float) -> None:
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self.cancelled.wait(), seconds)
        if self.cancelled.is_set():
            raise TripOver("the rider cancelled")

    async def _drive_or_cancel(self, path: list[LatLng], kmh: float) -> None:
        drive = asyncio.create_task(self._drive(path, kmh))
        cancel = asyncio.create_task(self.cancelled.wait())
        done, _ = await asyncio.wait({drive, cancel}, return_when=asyncio.FIRST_COMPLETED)
        drive.cancel()
        cancel.cancel()
        self.speed = 0.0
        if cancel in done:
            raise TripOver("the rider cancelled")

    # ------------------------------------------------------------------ moving

    async def _route(self, a: LatLng, b: LatLng, straight_if_down: bool = True) -> list[LatLng] | None:
        """ride-service's cached OSRM route (tried twice). When routing is down: a straight line for a
        trip (the rider is waiting), None for a cruise (better to stay parked)."""
        for attempt in (1, 2):
            try:
                r = await self.session.call(
                    "GET", f"/api/places/route?fromLat={a[0]:.5f}&fromLng={a[1]:.5f}&toLat={b[0]:.5f}&toLng={b[1]:.5f}")
                if r and r.get("polyline"):
                    path = decode_polyline(r["polyline"])
                    if len(path) >= 2:
                        # OSRM snaps both ends to the nearest road; a point far off it (a park, the sea) is dropped
                        head = [a] if haversine(a, path[0]) < 150 else []
                        tail = [b] if haversine(b, path[-1]) < 150 else []
                        return [*head, *path, *tail]
            except ApiError as e:
                log.debug("%s route failed (%s)", self.label, e)
            if attempt == 1:
                await asyncio.sleep(self.rng.uniform(1.5, 4))
        return [a, b] if straight_if_down else None

    async def _drive(self, path: list[LatLng], kmh: float) -> None:
        mps = kmh / 3.6
        total = path_length(path)
        travelled = 0.0
        while travelled < total:
            await asyncio.sleep(TICK_S)
            # traffic on the main roads (the same as the map shows)
            jam = self.s.roads.speed_factor_at(self.pos, time.time()) if self.s.roads else 1.0
            step = mps * jam * self.s.speedup * TICK_S * self.rng.uniform(0.85, 1.12)
            travelled += step
            self.pos, self.heading, done = along(path, travelled)
            self.speed = step / TICK_S
            if done:
                break
        self.speed = 0.0


def _pt(p: dict[str, Any]) -> LatLng:
    return float(p["lat"]), float(p["lng"])
