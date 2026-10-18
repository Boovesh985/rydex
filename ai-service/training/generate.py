"""Synthetic training data: bookings in a Chennai-like city.

Each row is one booking. The ground truth is a small simulation:
- ride requests follow the city's day (morning and evening commute, evenings out, a quiet night),
  with no office rush at weekends and on holidays, and more of them on rainy days (frequent in the
  October to December monsoon); some parts of the city are much busier than others;
- drivers are online mostly from 7 am to 11 pm, fewer when it rains;
- the wait is the drive of the nearest free car of the booked type (drivers spread out over the 3x3
  block of cells; cars busy with other requests are not free), slower in traffic and in the rain,
  plus the matching delay when requests outnumber cars, a little shorter for long trips (drivers take
  them more readily); bikes and autos weave through traffic faster;
- surge is ride-service's formula, ``1 + 0.25 * (demand / supply - 1)`` capped at 2.5.

The features are what the service can know when it predicts: the demand forecast (the true request
rate seen through forecast noise), the cars counted free (they come and go a little), the hour, the day, the holiday flag, the ride type, the trip
length and the time-of-day traffic multiplier. Rain is not a feature (no free weather feed is used),
so rainy days are part of the noise the models must live with.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import numpy as np

from app import calendar
from app.model import FEATURES, RIDE_TYPES, feature_row

# what riders book, and the share of the fleet free for each type (BIKE, AUTO, ECONOMY, COMFORT, XL, PREMIUM)
REQUEST_MIX = np.array([0.2, 0.2, 0.3, 0.15, 0.07, 0.08])
FLEET_SHARE = np.array([0.24, 0.24, 0.26, 0.13, 0.06, 0.07])
# how much faster than a car each type gets through traffic
NIMBLE = np.array([1.3, 1.15, 1.0, 1.0, 0.95, 1.0])
BLOCK_KM2 = 6.5  # the 3x3 block of precision-6 cells, about 3.6 km x 1.8 km
SEARCH_KM = 5.0
ROAD_FACTOR = 1.35
FREE_FLOW_KMH = 26.0
MAX_SURGE = 2.5


@dataclass
class Dataset:
    x: np.ndarray
    wait: np.ndarray
    surge: np.ndarray

    def __len__(self) -> int:
        return len(self.wait)


def surge_formula(demand: np.ndarray, supply: np.ndarray) -> np.ndarray:
    """ride-service's SurgeCalculator: no surge without supply or while supply meets demand."""
    raw = 1 + 0.25 * (demand / np.maximum(supply, 1) - 1)
    out = np.where((supply > 0) & (demand > supply), raw, 1.0)
    # to one decimal, halves up (BigDecimal HALF_UP), not numpy's halves-to-even
    return np.floor(np.clip(out, 1.0, MAX_SURGE) * 10 + 0.5) / 10


def generate(n: int = 80_000, seed: int = 7) -> Dataset:
    rng = np.random.default_rng(seed)
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    seconds = rng.uniform(0, 365 * 86400, n)

    rows = np.empty((n, len(FEATURES)))
    waits = np.empty(n)
    surges = np.empty(n)

    area = rng.lognormal(0.0, 0.6, n)  # how busy this part of the city is
    types = rng.choice(len(RIDE_TYPES), size=n, p=REQUEST_MIX)
    distance_km = np.clip(rng.lognormal(math.log(7.0), 0.6, n), 0.5, 60.0)
    forecast_noise_d = rng.lognormal(0.0, 0.25, n)
    forecast_noise_s = rng.lognormal(0.0, 0.1, n)
    spread = rng.lognormal(0.0, 0.3, n)
    far = rng.exponential(1.5, n)
    delay_noise = rng.normal(0.0, 0.6, n)
    rain_draw = rng.random(n)

    for i in range(n):
        t = start + timedelta(seconds=float(seconds[i]))
        hour = calendar.hour_of(t)
        off = calendar.is_off_day(t)
        monsoon = calendar.local(t).month in (10, 11, 12)
        rain = rain_draw[i] < (0.35 if monsoon else 0.08)

        lam = 5.0 * area[i] ** 1.2 * calendar.ride_demand_profile(hour, off) * (1.6 if rain else 1.0)
        mu = 7.0 * area[i] ** 0.6 * calendar.fleet_profile(hour) * (0.8 if rain else 1.0)
        traffic = calendar.traffic_multiplier(hour, off)

        demand = rng.poisson(lam)
        supply = rng.poisson(mu)
        k = types[i]

        # the nearest free car of this type: cars busy with requests are not free
        busy = math.exp(-0.35 * max(demand / max(supply, 0.5) - 1.0, 0.0))
        cars = supply * FLEET_SHARE[k] / FLEET_SHARE.max() * 0.6 * busy
        # drivers spread out to cover the block, so the nearest is about half the spacing between cars away
        nearest_km = 0.55 * math.sqrt(BLOCK_KM2 / max(cars, 0.01)) * spread[i]
        if nearest_km > SEARCH_KM:
            nearest_km = SEARCH_KM + far[i]  # none close: one from further out
        speed = FREE_FLOW_KMH * NIMBLE[k] / (traffic * (1.15 if rain else 1.0))
        drive_min = nearest_km * ROAD_FACTOR / speed * 60
        # drivers take long trips more readily, so they are matched a little sooner
        matching_min = (0.5 + 1.5 * max(0.0, demand / max(supply, 0.5) - 1.0)) * (1.2 - 0.3 * min(distance_km[i] / 20, 1.0))
        waits[i] = min(35.0, max(1.0, 1.0 + drive_min + matching_min + delay_noise[i]))
        surges[i] = surge_formula(np.array([demand]), np.array([supply]))[0]

        rows[i] = feature_row(hour, calendar.local(t).weekday(), calendar.is_holiday(t),
                              lam * forecast_noise_d[i], supply * forecast_noise_s[i], RIDE_TYPES[k],
                              float(distance_km[i]), traffic)
    return Dataset(rows, waits, surges)
