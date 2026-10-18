"""Live demand and supply per geohash-6 cell, built from Kafka (consumer.py), kept in Redis.

- demand: RIDE_REQUESTED events counted per cell per 15-minute bucket (``ai:demand:{cell}:{bucket}``);
- supply: the drivers whose last position (driver.location) was in a cell while AVAILABLE, scored by
  when it was seen (``ai:supply:{cell}``, a sorted set); a driver seen elsewhere or busy leaves it.

The forecast for a later pickup is an EWMA of the recent buckets, shaped by the city's daily curve.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from . import calendar, geo

BUCKET_S = 15 * 60
HISTORY_BUCKETS = 8  # two hours
DEMAND_TTL_S = 3 * 3600
SUPPLY_FRESH_S = 60  # an online driver reports every few seconds
DRIVER_TTL_S = 300
EWMA_ALPHA = 0.5


def bucket_of(t: datetime) -> int:
    return int(t.timestamp()) // BUCKET_S


class FeatureStore(Protocol):
    async def record_request(self, cell: str, at: datetime) -> None: ...

    async def record_driver(self, driver_id: str, cell: str | None, available: bool, at: datetime) -> None: ...

    async def demand_series(self, cells: list[str], at: datetime) -> list[int]:
        """Requests in all the cells per bucket, oldest first; the last bucket is the current one."""
        ...

    async def supply(self, cells: list[str], at: datetime) -> int: ...

    async def last_event_at(self) -> float | None: ...


class MemoryStore:
    """For tests, and a stand-in when Redis is not configured."""

    def __init__(self) -> None:
        self.demand: dict[tuple[str, int], int] = {}
        self.drivers: dict[str, tuple[str, float]] = {}  # driver -> (cell, seen at)
        self.last: float | None = None

    async def record_request(self, cell: str, at: datetime) -> None:
        key = (cell, bucket_of(at))
        self.demand[key] = self.demand.get(key, 0) + 1
        self.last = time.time()

    async def record_driver(self, driver_id: str, cell: str | None, available: bool, at: datetime) -> None:
        if available and cell:
            self.drivers[driver_id] = (cell, at.timestamp())
        else:
            self.drivers.pop(driver_id, None)
        self.last = time.time()

    async def demand_series(self, cells: list[str], at: datetime) -> list[int]:
        now = bucket_of(at)
        return [sum(self.demand.get((c, b), 0) for c in cells) for b in range(now - HISTORY_BUCKETS + 1, now + 1)]

    async def supply(self, cells: list[str], at: datetime) -> int:
        since = at.timestamp() - SUPPLY_FRESH_S
        wanted = set(cells)
        return sum(1 for cell, seen in self.drivers.values() if cell in wanted and seen >= since)

    async def last_event_at(self) -> float | None:
        return self.last


class RedisStore:
    def __init__(self, redis) -> None:  # redis.asyncio.Redis
        self.redis = redis

    async def record_request(self, cell: str, at: datetime) -> None:
        key = f"ai:demand:{cell}:{bucket_of(at)}"
        async with self.redis.pipeline(transaction=False) as p:
            p.incr(key)
            p.expire(key, DEMAND_TTL_S)
            p.set("ai:last_event", time.time())
            await p.execute()

    async def record_driver(self, driver_id: str, cell: str | None, available: bool, at: datetime) -> None:
        where = f"ai:driver:{driver_id}"
        previous = await self.redis.get(where)
        async with self.redis.pipeline(transaction=False) as p:
            if previous and previous != cell:
                p.zrem(f"ai:supply:{previous}", driver_id)
            if available and cell:
                p.zadd(f"ai:supply:{cell}", {driver_id: at.timestamp()})
                p.expire(f"ai:supply:{cell}", DRIVER_TTL_S)
                p.set(where, cell, ex=DRIVER_TTL_S)
            else:
                if cell:
                    p.zrem(f"ai:supply:{cell}", driver_id)
                p.delete(where)
            p.set("ai:last_event", time.time())
            await p.execute()

    async def demand_series(self, cells: list[str], at: datetime) -> list[int]:
        now = bucket_of(at)
        buckets = list(range(now - HISTORY_BUCKETS + 1, now + 1))
        keys = [f"ai:demand:{c}:{b}" for b in buckets for c in cells]
        values = await self.redis.mget(keys)
        out = [0] * len(buckets)
        for i, v in enumerate(values):
            if v is not None:
                out[i // len(cells)] += int(v)
        return out

    async def supply(self, cells: list[str], at: datetime) -> int:
        since = at.timestamp() - SUPPLY_FRESH_S
        async with self.redis.pipeline(transaction=False) as p:
            for c in cells:
                p.zcount(f"ai:supply:{c}", since, "+inf")
            counts = await p.execute()
        return int(sum(counts))

    async def last_event_at(self) -> float | None:
        v = await self.redis.get("ai:last_event")
        return float(v) if v is not None else None


@dataclass(frozen=True)
class Area:
    """Demand and supply of the 3x3 block around one cell."""

    cell: str
    series: tuple[int, ...]
    supply: int

    def demand_now(self, at: datetime) -> float:
        """Requests over the last 30 min (the current bucket so far plus the previous one), as ride-service counts them."""
        return float(self.series[-1] + self.series[-2]) if len(self.series) >= 2 else float(sum(self.series))

    def forecast(self, at: datetime, offset_min: float) -> float:
        """Expected requests over a 30-minute window starting ``offset_min`` from now."""
        done = self.series[:-1]
        elapsed = (at.timestamp() % BUCKET_S) / BUCKET_S
        level = 0.0
        for count in done:
            level = EWMA_ALPHA * count + (1 - EWMA_ALPHA) * level
        # the bucket under way, once it has run long enough to say something
        if elapsed > 0.2:
            level = EWMA_ALPHA * (self.series[-1] / elapsed) + (1 - EWMA_ALPHA) * level
        return 2 * level * seasonal(at, offset_min)


def seasonal(at: datetime, offset_min: float) -> float:
    """How much busier the city is ``offset_min`` from now than now (bounded)."""
    off = calendar.is_off_day(at)
    h = calendar.hour_of(at)
    later = (h + offset_min / 60) % 24
    return min(2.0, max(0.5, calendar.ride_demand_profile(later, off) / calendar.ride_demand_profile(h, off)))


def fleet_change(at: datetime, offset_min: float) -> float:
    h = calendar.hour_of(at)
    return calendar.fleet_profile((h + offset_min / 60) % 24) / calendar.fleet_profile(h)


async def area(store: FeatureStore, cell: str, at: datetime) -> Area:
    cells = geo.grid3x3(cell)
    return Area(cell, tuple(await store.demand_series(cells, at)), await store.supply(cells, at))


def fresh(last_event: float | None, now: float | None = None, within_s: float = 600) -> bool:
    return last_event is not None and (now or time.time()) - last_event <= within_s

