"""One simulated (NPC) rider of the living city (ADR 0016): lives near a place in Chennai, books trips
to other places, waits for the car, reads out the PIN to the driver, rides, pays, and goes on from
where the trip ended."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import random
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from .api import ApiError, Session
from .geo import LatLng
from .places import PLACES, Place, destination, near
from .traffic import RoadIndex

log = logging.getLogger("rydex.sim")

POLL_S = 4.0  # how often a rider looks at their ride
ACTIVE = ("REQUESTED", "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_PROGRESS")
# share of trips per ride type
# how NPC riders rate their trips: mostly happy, now and then not
STARS = ((5, 0.68), (4, 0.2), (3, 0.08), (2, 0.03), (1, 0.01))
RIDE_TYPES = (("BIKE", 0.3), ("AUTO", 0.25), ("ECONOMY", 0.22), ("COMFORT", 0.12), ("XL", 0.07), ("PREMIUM", 0.04))


class PinBoard:
    """How an NPC rider reads out their PIN to an NPC driver: both live in this process. A driver
    whose rider is a real person (not on the board) has to get it some other way."""

    def __init__(self) -> None:
        self._pins: dict[str, asyncio.Future[str]] = {}

    def _future(self, ride_id: str) -> asyncio.Future[str]:
        if ride_id not in self._pins:
            self._pins[ride_id] = asyncio.get_running_loop().create_future()
        return self._pins[ride_id]

    def say(self, ride_id: str, pin: str) -> None:
        fut = self._future(ride_id)
        if not fut.done():
            fut.set_result(pin)

    async def hear(self, ride_id: str, timeout: float) -> str | None:
        """The PIN the rider reads out, or None if nobody says it within `timeout` seconds."""
        try:
            return await asyncio.wait_for(asyncio.shield(self._future(ride_id)), timeout)
        except asyncio.TimeoutError:
            return None

    def forget(self, ride_id: str) -> None:
        self._pins.pop(ride_id, None)


@dataclass
class RiderStats:
    riders: int = 0
    booked: int = 0
    completed: int = 0
    cancelled: int = 0
    unmatched: int = 0
    spent: float = 0.0
    started: float = field(default_factory=time.monotonic)

    def line(self) -> str:
        return (f"{self.riders} riders | {self.booked} booked, {self.completed} completed, {self.cancelled} cancelled, "
                f"{self.unmatched} unmatched | Rs {self.spent:.0f} paid")


@dataclass
class RiderSettings:
    speedup: float = 1.0
    # mean seconds between a rider's trips (real time); divided by sqrt(speedup)
    idle_s: float = 150.0
    # how long a rider waits for a match before giving up
    patience_s: float = 150.0
    # main roads: riders wait at the kerb, not in a park or the sea
    roads: RoadIndex | None = None


class SimRider:
    def __init__(self, label: str, session: Session, board: PinBoard, settings: RiderSettings, stats: RiderStats,
                 rng: random.Random):
        self.label = label
        self.session = session
        self.board = board
        self.s = settings
        self.stats = stats
        self.rng = rng
        home: Place = rng.choice(PLACES)
        self.pos: LatLng = near(home, rng, 600, settings.roads)
        self.place = home.name

    def _pace(self, seconds: float) -> float:
        return seconds / max(1.0, self.s.speedup ** 0.5)

    async def run(self) -> None:
        await self.session.token()  # sign in (paced by the shared budget)
        self.stats.riders += 1
        try:
            # a rider with a ride already going (the simulator restarted) sees it through first
            current = await self._current_ride()
            if current:
                await self._follow(current)
            await asyncio.sleep(self.rng.uniform(5, 90))  # everyone does not book at once
            while True:
                await self._trip()
                await asyncio.sleep(self._pace(self.rng.expovariate(1 / self.s.idle_s)) + 5)
        finally:
            self.stats.riders -= 1

    async def _current_ride(self) -> dict[str, Any] | None:
        page = await self.session.call("GET", "/api/rides?mine=true&page=0&size=5")
        for ride in (page or {}).get("items", []):
            if ride["status"] in ACTIVE:
                return ride
        return None

    async def _trip(self) -> None:
        to = destination(self.pos, self.rng)
        drop = near(to, self.rng, 350, self.s.roads)
        ride_type = self.rng.choices([t for t, _ in RIDE_TYPES], [w for _, w in RIDE_TYPES])[0]
        pickup = {"lat": round(self.pos[0], 6), "lng": round(self.pos[1], 6), "address": f"Near {self.place}"}
        dropoff = {"lat": round(drop[0], 6), "lng": round(drop[1], 6), "address": to.name}
        try:
            ride = await self.session.call("POST", "/api/rides", {"pickup": pickup, "drop": dropoff, "rideType": ride_type},
                                           headers={"Idempotency-Key": str(uuid.uuid4())})
        except ApiError as e:
            if e.status == 409:  # a ride is already going: follow that one
                current = await self._current_ride()
                if current:
                    await self._follow(current)
                return
            log.info("%s could not book (%s)", self.label, e.detail)
            return
        self.stats.booked += 1
        log.info("%s booked %s %s -> %s", self.label, ride_type.lower(), self.place, to.name)
        final = await self._follow(ride)
        if final and final["status"] == "COMPLETED":
            self.pos = drop
            self.place = to.name

    async def _rate(self, ride: dict[str, Any]) -> None:
        """Rates the trip, as most riders do: the stars move the driver's rating."""
        if ride.get("riderRating") is not None:
            return
        stars = self.rng.choices([s for s, _ in STARS], [w for _, w in STARS])[0]
        with contextlib.suppress(ApiError):
            await self.session.call("POST", f"/api/rides/{ride['id']}/rating", {"stars": stars})

    async def _follow(self, ride: dict[str, Any]) -> dict[str, Any] | None:
        """Watches the ride to its end: gives up on a slow match, reads out the PIN when the car is here."""
        ride_id = ride["id"]
        requested = time.monotonic()
        said_pin = False
        try:
            while True:
                status = ride["status"]
                if status == "REQUESTED" and time.monotonic() - requested > self._pace(self.s.patience_s):
                    with contextlib.suppress(ApiError):
                        await self.session.call("POST", f"/api/rides/{ride_id}/cancel", {"reason": "Took too long"})
                        self.stats.cancelled += 1
                    return None
                if status == "DRIVER_ARRIVED" and not said_pin and ride.get("otp"):
                    # walk up to the car, then read out the PIN
                    await asyncio.sleep(self.rng.uniform(4, 15))
                    self.board.say(ride_id, ride["otp"])
                    said_pin = True
                if status == "COMPLETED":
                    self.stats.completed += 1
                    self.stats.spent += float(ride.get("finalFare") or ride.get("estFare") or 0)
                    log.info("%s arrived at %s", self.label, ride["drop"].get("address"))
                    await self._rate(ride)
                    return ride
                if status == "CANCELLED":
                    self.stats.cancelled += 1
                    return ride
                if status == "NO_DRIVER_FOUND":
                    self.stats.unmatched += 1
                    return ride
                await asyncio.sleep(POLL_S)
                try:
                    ride = await self.session.call("GET", f"/api/rides/{ride_id}")
                except ApiError as e:
                    if e.status == 404:
                        return None
                    raise
        finally:
            self.board.forget(ride_id)
