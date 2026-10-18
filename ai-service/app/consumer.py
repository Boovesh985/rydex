"""Feeds the feature store from Kafka: ride requests from ``ride.events``, free drivers from ``driver.location``.

Starts at the latest offsets (old positions say nothing about now) and keeps retrying while Kafka is
down; suggestions work meanwhile, on ride-service's own counts and the city's daily curve.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone

from prometheus_client import Counter

from . import geo
from .features import FeatureStore

RIDE_EVENTS = "ride.events"
DRIVER_LOCATION = "driver.location"
GROUP = "ai-service"
MAX_AGE = timedelta(hours=3)

log = logging.getLogger(__name__)
EVENTS = Counter("ai_feature_events_total", "Kafka messages turned into features", ["topic"])


def _time(value: str | None) -> datetime:
    if not value:
        return datetime.now(timezone.utc)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


async def handle(store: FeatureStore, topic: str, raw: bytes | str) -> bool:
    """One message; True when it changed the features. Unreadable messages are skipped."""
    try:
        msg = json.loads(raw)
        if topic == RIDE_EVENTS:
            if msg.get("eventType") != "RIDE_REQUESTED":
                return False
            at = _time(msg.get("occurredAt"))
            if datetime.now(timezone.utc) - at > MAX_AGE:
                return False
            pickup = msg["payload"]["pickup"]
            await store.record_request(geo.encode(pickup["lat"], pickup["lng"]), at)
        elif topic == DRIVER_LOCATION:
            cell = msg.get("geohash") or geo.encode(msg["lat"], msg["lng"])
            await store.record_driver(str(msg["driverId"]), cell[: geo.PRECISION], msg.get("status") == "AVAILABLE",
                                      _time(msg.get("recordedAt")))
        else:
            return False
    except (ValueError, KeyError, TypeError) as e:
        log.debug("Skipping an unreadable %s message: %s", topic, e)
        return False
    EVENTS.labels(topic).inc()
    return True


async def run(store: FeatureStore, bootstrap_servers: str, retry_s: float = 15) -> None:
    from aiokafka import AIOKafkaConsumer

    while True:
        consumer = AIOKafkaConsumer(RIDE_EVENTS, DRIVER_LOCATION, bootstrap_servers=bootstrap_servers, group_id=GROUP,
                                    auto_offset_reset="latest", enable_auto_commit=True)
        try:
            await consumer.start()
            log.info("Consuming %s and %s from %s", RIDE_EVENTS, DRIVER_LOCATION, bootstrap_servers)
            async for msg in consumer:
                try:
                    await handle(store, msg.topic, msg.value)
                except Exception as e:  # the feature store (Redis) is down: drop this one, keep going
                    log.debug("Could not record a %s message: %s", msg.topic, e)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.warning("Kafka unavailable (%s); retrying in %.0f s", e, retry_s)
        finally:
            try:
                await consumer.stop()
            except Exception:
                pass
        await asyncio.sleep(retry_s)
