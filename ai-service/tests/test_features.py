import asyncio
import json
from datetime import datetime, timedelta, timezone

from app import consumer, features, geo
from app.features import Area, MemoryStore

CELL = geo.encode(13.0827, 80.2707)


def run(coro):
    return asyncio.run(coro)


def test_requests_are_counted_per_cell_and_bucket():
    store = MemoryStore()
    now = datetime(2026, 10, 19, 6, 40, tzinfo=timezone.utc)
    for minutes_ago in (0, 2, 16, 16, 40, 200):
        run(store.record_request(CELL, now - timedelta(minutes=minutes_ago)))
    run(store.record_request(geo.neighbours(CELL)[0], now))
    series = run(store.demand_series([CELL], now))
    assert len(series) == features.HISTORY_BUCKETS
    assert sum(series) == 5  # the one 200 min ago is out of the two-hour window
    assert series[-1] == 2 and series[-2] == 2
    assert sum(run(store.demand_series(geo.grid3x3(CELL), now))) == 6


def test_supply_counts_free_drivers_seen_lately_in_their_last_cell():
    store = MemoryStore()
    now = datetime.now(timezone.utc)
    north = geo.neighbours(CELL)[0]
    run(store.record_driver("a", CELL, True, now))
    run(store.record_driver("b", CELL, True, now))
    run(store.record_driver("b", north, True, now))  # moved on
    run(store.record_driver("c", CELL, False, now))  # on a trip
    run(store.record_driver("d", CELL, True, now - timedelta(minutes=5)))  # gone quiet
    assert run(store.supply([CELL], now)) == 1
    assert run(store.supply(geo.grid3x3(CELL), now)) == 2


def test_the_forecast_follows_the_recent_rate_and_the_daily_curve():
    at = datetime(2026, 10, 19, 4, 31, tzinfo=timezone.utc)  # 10:01 am in Chennai, the morning rush easing
    steady = Area(CELL, (4,) * 8, 5)
    assert steady.demand_now(at) == 8
    assert 7 < steady.forecast(at, 0) <= 8.1
    assert steady.forecast(at, 20) < steady.forecast(at, 0)
    rising = Area(CELL, (0, 0, 0, 0, 2, 4, 6, 8), 5)
    assert rising.forecast(at, 0) > steady.forecast(at, 0) * 0.9
    assert features.seasonal(at, 0) == 1.0


def test_freshness():
    assert not features.fresh(None)
    assert features.fresh(1000.0, now=1300.0) and not features.fresh(1000.0, now=2000.0)


def envelope(event_type, minutes_ago=0, lat=13.0827, lng=80.2707):
    at = datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)
    return json.dumps({"eventId": "e1", "eventType": event_type, "version": 1, "occurredAt": at.isoformat().replace("+00:00", "Z"),
                       "rideId": "r1", "payload": {"riderId": "u1", "pickup": {"lat": lat, "lng": lng, "address": "x"}}})


def test_the_consumer_turns_ride_requests_and_driver_positions_into_features():
    store = MemoryStore()
    assert run(consumer.handle(store, consumer.RIDE_EVENTS, envelope("RIDE_REQUESTED")))
    assert not run(consumer.handle(store, consumer.RIDE_EVENTS, envelope("RIDE_COMPLETED")))
    assert not run(consumer.handle(store, consumer.RIDE_EVENTS, envelope("RIDE_REQUESTED", minutes_ago=600)))
    assert not run(consumer.handle(store, consumer.RIDE_EVENTS, b"not json"))
    assert not run(consumer.handle(store, consumer.RIDE_EVENTS, json.dumps({"eventType": "RIDE_REQUESTED"})))
    location = {"eventId": "e2", "driverId": "d1", "rideId": None, "lat": 13.0827, "lng": 80.2707, "geohash": CELL,
                "status": "AVAILABLE", "recordedAt": datetime.now(timezone.utc).isoformat()}
    assert run(consumer.handle(store, consumer.DRIVER_LOCATION, json.dumps(location)))
    no_hash = {**location, "driverId": "d2", "geohash": None}
    assert run(consumer.handle(store, consumer.DRIVER_LOCATION, json.dumps(no_hash)))
    now = datetime.now(timezone.utc)
    assert sum(run(store.demand_series([CELL], now))) == 1
    assert run(store.supply([CELL], now)) == 2
    busy = {**location, "status": "ON_TRIP"}
    run(consumer.handle(store, consumer.DRIVER_LOCATION, json.dumps(busy)))
    assert run(store.supply([CELL], now)) == 1
    assert features.fresh(run(store.last_event_at()))
