from datetime import datetime

import pytest

from app import calendar
from app.engine import choose_route, traffic_minutes
from app.schemas import RouteOption
import numpy as np

from app.traffic import Segment, TrafficRoads, decode_polyline, level, level_np

# shared with frontend/src/map/traffic.test.ts and simulator/tests/test_city.py: all three must give the same numbers
VECTORS = [
    (0.1234, 0, 1792065600, 0.4180877279789025),
    (0.8765, 1, 1792097100, 0.27399949864662293),
    (0.5, 2, 1792080000, 0.4072792929499385),
    (0.0001, 3, 1792000000, 0.12841262019387095),
    (0.6543, 1, 1792030000, 0.5304950859490551),
]

RUSH = datetime(2026, 10, 21, 18, 45, tzinfo=calendar.IST).timestamp()
NIGHT = datetime(2026, 10, 21, 3, 0, tzinfo=calendar.IST).timestamp()


def encode(points, precision=6):
    """Google's polyline encoding, to build test routes."""
    out, last = [], (0, 0)
    for lat, lng in points:
        cur = (round(lat * 10**precision), round(lng * 10**precision))
        for d in (cur[0] - last[0], cur[1] - last[1]):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        last = cur
    return "".join(out)


@pytest.mark.parametrize("seg_hash,road_class,t,expected", VECTORS)
def test_the_same_traffic_as_the_map_and_the_simulator(seg_hash, road_class, t, expected):
    assert level(seg_hash, road_class, t) == pytest.approx(expected, abs=1e-9)
    assert level_np(np.array([seg_hash]), np.array([road_class]), t)[0] == pytest.approx(expected, abs=1e-9)


def test_decodes_polylines():
    # Google's documented example, precision 5
    assert decode_polyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5) == [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]
    pts = [(13.0827, 80.2707), (13.0901, 80.2611)]
    assert decode_polyline(encode(pts)) == pts


# a straight east-west main road through the city, and a parallel street 600 m north with no traffic data
MAIN = [(13.05, 80.20 + i * 0.004) for i in range(11)]
SIDE = [(13.0554, 80.20 + i * 0.004) for i in range(11)]


def city(seg_hash=0.95, road_class=0):
    return TrafficRoads([Segment(road_class, seg_hash, tuple(MAIN))])


def test_a_jammed_main_road_is_slower_than_its_free_flow_time():
    roads = city()
    jammed = roads.drive_minutes(MAIN, 300, RUSH)
    assert jammed > 5 * 1.5
    assert roads.drive_minutes(MAIN, 300, NIGHT) < jammed
    assert roads.drive_minutes(SIDE, 300, RUSH) == pytest.approx(5.0)  # off the mapped roads: free flow
    assert roads.drive_minutes(MAIN[:1], 300, RUSH) is None


def test_the_route_around_a_jam_wins_in_the_rush_hour_only():
    usual = RouteOption(distanceM=4400, durationS=300, polyline=encode(MAIN))
    around = RouteOption(distanceM=4700, durationS=390, polyline=encode(SIDE))
    roads = city()
    choice, why = choose_route([usual, around], calendar.traffic_multiplier(18.75), roads, RUSH)
    assert choice.choice == "alternative_1" and choice.polyline == around.polyline
    assert why.startswith("Traffic is heavy on the usual way") and why.endswith("min faster")
    night, _ = choose_route([usual, around], calendar.traffic_multiplier(3), roads, NIGHT)
    assert night.choice == "fastest"


def test_routes_without_a_shape_fall_back_to_the_time_of_day_estimate():
    usual = RouteOption(distanceM=4400, durationS=300, polyline=encode(MAIN))
    bare = RouteOption(distanceM=4700, durationS=390)
    choice, _ = choose_route([usual, bare], calendar.traffic_multiplier(18.75), city(), RUSH)
    assert choice.choice == "fastest"


def test_the_shipped_road_file_loads():
    roads = TrafficRoads.load()
    assert len(roads) > 1000
    # Anna Salai near Gemini flyover is a mapped main road
    assert roads.nearest((13.0569, 80.2496)) is not None or roads.nearest((13.0604, 80.2496)) is not None


def test_routes_are_timed_only_with_a_road_map_and_shapes():
    usual = RouteOption(distanceM=4400, durationS=300, polyline=encode(MAIN))
    around = RouteOption(distanceM=4700, durationS=390, polyline=encode(SIDE))
    timed = traffic_minutes([usual, around], city(), RUSH)
    assert timed is not None and timed[0] > timed[1]
    assert traffic_minutes([usual, around], None, RUSH) is None
    assert traffic_minutes([usual, around], TrafficRoads([]), RUSH) is None
