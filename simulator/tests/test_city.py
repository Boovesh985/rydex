"""The living city (ADR 0016): NPC riders, the PIN board, places and traffic."""

import asyncio
import random
from datetime import datetime, timedelta, timezone

import pytest

from rydex_sim.geo import haversine
from rydex_sim.places import PLACES, destination, near
from rydex_sim.rider import PinBoard
from rydex_sim.traffic import RoadIndex, Segment, demand, ist_hour, level, speed_factor

# 2026-10-15 03:00 and 18:45 in India
IST = timezone(timedelta(hours=5, minutes=30))
NIGHT = datetime(2026, 10, 15, 3, 0, tzinfo=IST).timestamp()
RUSH = datetime(2026, 10, 15, 18, 45, tzinfo=IST).timestamp()

# shared with frontend/src/map/traffic.test.ts and ai-service/tests/test_traffic.py: all three must give the same numbers
VECTORS = [
    (0.1234, 0, 1792065600, 0.4180877279789025),
    (0.8765, 1, 1792097100, 0.27399949864662293),
    (0.5, 2, 1792080000, 0.4072792929499385),
    (0.0001, 3, 1792000000, 0.12841262019387095),
    (0.6543, 1, 1792030000, 0.5304950859490551),
]


def test_a_rider_reads_out_the_pin_to_the_driver():
    async def go():
        board = PinBoard()
        heard = asyncio.create_task(board.hear("ride-1", timeout=2))
        await asyncio.sleep(0.05)
        board.say("ride-1", "4821")
        return await heard, await board.hear("ride-2", timeout=0.05)

    pin, nobody = asyncio.run(go())
    assert pin == "4821"
    assert nobody is None  # a real person's ride: nobody on the board


def test_a_pin_said_before_the_driver_listens_is_still_heard():
    async def go():
        board = PinBoard()
        board.say("ride-1", "0007")
        return await board.hear("ride-1", timeout=0.1)

    assert asyncio.run(go()) == "0007"


def test_riders_go_somewhere_worth_a_ride():
    rng = random.Random(7)
    for _ in range(100):
        home = rng.choice(PLACES)
        start = near(home, rng)
        assert haversine(start, home.point) <= 601
        to = destination(start, rng)
        assert 1500 <= haversine(start, to.point) <= 9000


def test_traffic_follows_the_day():
    assert ist_hour(RUSH) == pytest.approx(18.75)
    assert demand(18.75) > 0.8 > 0.2 > demand(3.0)
    night = [level(h / 100, 1, NIGHT) for h in range(100)]
    rush = [level(h / 100, 1, RUSH) for h in range(100)]
    assert sum(rush) / 100 > 0.5 > 0.2 > sum(night) / 100
    assert all(0 <= x <= 1 for x in night + rush)
    # big roads jam first
    assert sum(level(h / 100, 0, RUSH) for h in range(100)) > sum(level(h / 100, 3, RUSH) for h in range(100))
    assert speed_factor(0) == 1 and speed_factor(1) == pytest.approx(0.22)


@pytest.mark.parametrize("seg_hash,road_class,t,expected", VECTORS)
def test_traffic_matches_the_web_app(seg_hash, road_class, t, expected):
    assert level(seg_hash, road_class, t) == pytest.approx(expected, abs=1e-9)


def test_cars_find_the_road_they_are_on():
    anna_salai = Segment(1, 0.9, ((13.0600, 80.2600), (13.0500, 80.2500)))
    side = Segment(3, 0.1, ((13.0700, 80.2700), (13.0710, 80.2710)))
    roads = RoadIndex([anna_salai, side])
    on_it = (13.0550, 80.2550)
    assert roads.nearest(on_it) is anna_salai
    assert roads.nearest((13.0550, 80.2560)) is None  # ~110 m off any main road
    assert roads.speed_factor_at((13.0550, 80.2560), RUSH) == 1.0
    assert roads.speed_factor_at(on_it, RUSH) < roads.speed_factor_at(on_it, NIGHT)


def test_npcs_stand_on_real_roads():
    road = Segment(1, 0.5, ((13.0500, 80.2800), (13.0510, 80.2800), (13.0520, 80.2800)))
    roads = RoadIndex([road])
    rng = random.Random(3)
    marina = PLACES[2]  # Marina Beach: a random point near it can fall in the sea
    for _ in range(20):
        p = near(marina, rng, 600, roads)
        assert p in road.points
    assert roads.on_land((13.0510, 80.2810))
    assert not roads.on_land((13.0510, 80.3100))  # 3 km out in the Bay of Bengal
    # nothing near: fall back to a point around the place
    far = PLACES[0]
    assert haversine(near(far, rng, 300, roads), far.point) <= 301

