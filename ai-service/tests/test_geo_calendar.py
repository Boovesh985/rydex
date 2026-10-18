from datetime import datetime, timezone

from app import calendar, geo


def test_geohash_matches_the_reference_encoding_and_the_java_cells():
    assert geo.encode(57.64911, 10.40744, 11) == "u4pruydqqvj"
    assert geo.encode(13.0827, 80.2707) == "tf346t"


def test_neighbours_are_eight_distinct_symmetric_and_compass_ordered():
    cell = geo.encode(13.0827, 80.2707)
    around = geo.neighbours(cell)
    assert len(set(around)) == 8 and cell not in around
    assert all(cell in geo.neighbours(n) for n in around)
    c = geo.center(cell)
    assert geo.center(around[0])[0] > c[0]  # N
    assert geo.center(around[2])[1] > c[1]  # E
    assert geo.center(around[4])[0] < c[0]  # S
    assert geo.center(around[6])[1] < c[1]  # W
    assert geo.grid3x3(cell)[0] == cell and len(set(geo.grid3x3(cell))) == 9


def test_wraps_across_the_antimeridian():
    assert geo.neighbours(geo.encode(0.1, 179.99, 4))[2] == geo.encode(0.1, -179.99, 4)


def test_compass_words():
    assert geo.compass(0) == "north" and geo.compass(91) == "east" and geo.compass(225) == "south-west"


def ist(y, m, d, hh, mm=0):
    """An instant given in Chennai time."""
    return datetime(y, m, d, hh, mm, tzinfo=calendar.IST).astimezone(timezone.utc)


def test_hours_and_days_go_by_chennai_time():
    t = ist(2026, 10, 19, 9, 15)  # a Monday
    assert calendar.hour_of(t) == 9.25
    assert not calendar.is_off_day(t)
    assert calendar.is_off_day(ist(2026, 10, 18, 9))  # Sunday
    assert calendar.is_holiday(ist(2026, 8, 15, 12)) and calendar.is_holiday(ist(2026, 11, 8, 12))


def test_traffic_peaks_in_the_rush_hours_and_eases_at_night_and_on_off_days():
    rush, night = calendar.traffic_multiplier(18.75), calendar.traffic_multiplier(3.0)
    assert 1.4 < rush < 1.6 and night < 1.05
    assert calendar.traffic_multiplier(9.25, off_day=True) < calendar.traffic_multiplier(9.25)
    assert calendar.fleet_profile(14) > 0.9 > 0.3 > calendar.fleet_profile(3)
