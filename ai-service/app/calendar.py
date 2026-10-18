"""Chennai time, the city's daily rhythm and the time-of-day traffic multiplier.

Free routing (the public OSRM server) has no live traffic, so traffic is modelled: a multiplier on
free-flow driving times that follows the same daily curve as the simulated traffic on the map
(frontend/src/map/traffic.ts, simulator/rydex_sim/traffic.py): morning and evening rush, a quieter
night, lighter at weekends and on public holidays.
"""

from __future__ import annotations

import math
from datetime import date, datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))

# Public holidays in Tamil Nadu that empty the office commute: the fixed-date ones every year, plus
# the movable ones listed by date (add next year's when the state publishes them)
FIXED_HOLIDAYS = frozenset({(1, 1), (1, 14), (1, 15), (1, 16), (1, 26), (4, 14), (5, 1), (8, 15), (10, 2), (12, 25)})
MOVABLE_HOLIDAYS = frozenset(date.fromisoformat(d) for d in ("2026-04-03", "2026-09-14", "2026-11-08"))


def local(t: datetime) -> datetime:
    return t.astimezone(IST)


def hour_of(t: datetime) -> float:
    lt = local(t)
    return lt.hour + lt.minute / 60 + lt.second / 3600


def is_holiday(t: datetime) -> bool:
    d = local(t).date()
    return (d.month, d.day) in FIXED_HOLIDAYS or d in MOVABLE_HOLIDAYS


def is_off_day(t: datetime) -> bool:
    """A weekend or a public holiday: no office rush."""
    return local(t).weekday() >= 5 or is_holiday(t)


def _bump(h: float, center: float, width: float) -> float:
    d = abs(h - center)
    d = min(d, 24.0 - d)
    return math.exp(-((d / width) ** 2))


def busyness(hour: float, off_day: bool = False) -> float:
    """How busy the city's roads are, 0..~0.85 (the traffic model's daily curve)."""
    if off_day:
        # no office rush: a late, gentle morning and a long evening out
        return 0.12 + 0.25 * _bump(hour, 11.0, 2.5) + 0.6 * _bump(hour, 19.5, 3.0) - 0.1 * _bump(hour, 4.0, 2.5)
    return (0.15 + 0.6 * _bump(hour, 9.25, 1.6) + 0.7 * _bump(hour, 18.75, 2.6)
            + 0.25 * _bump(hour, 13.5, 3.0) - 0.12 * _bump(hour, 3.0, 2.5))


def traffic_multiplier(hour: float, off_day: bool = False) -> float:
    """Driving time over free flow: about 1.0 at 3 am, about 1.5 in the evening rush."""
    return 1.0 + 0.6 * max(0.0, busyness(hour, off_day))


def ride_demand_profile(hour: float, off_day: bool = False) -> float:
    """Relative ride requests by hour (1 = an average hour): commutes, plus late evenings out."""
    late = 0.35 * _bump(hour, 22.0, 1.5)
    return max(0.05, 1.6 * busyness(hour, off_day) + late)


def fleet_profile(hour: float) -> float:
    """Share of drivers online by hour: most from 7 am to 11 pm, a night shift of about a fifth."""
    day = 1 / (1 + math.exp(-(hour - 6.5) * 1.6)) * (1 / (1 + math.exp((hour - 23.0) * 1.4)))
    return 0.2 + 0.8 * day
