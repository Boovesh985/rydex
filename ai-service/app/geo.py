"""Geohash cells (same layout as libs/common-events Geohash.java), distances and polylines."""

from __future__ import annotations

import math

LatLng = tuple[float, float]

EARTH_M = 6_371_000.0
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
# precision-6 cells, about 1.2 km x 0.6 km: the cells ride-service and driver-service match in
PRECISION = 6
NEIGHBOUR_OFFSETS = ((1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1))


def encode(lat: float, lng: float, precision: int = PRECISION) -> str:
    lat_range, lng_range = [-90.0, 90.0], [-180.0, 180.0]
    out, even, bit, ch = [], True, 0, 0
    while len(out) < precision:
        rng, value = (lng_range, lng) if even else (lat_range, lat)
        mid = (rng[0] + rng[1]) / 2
        if value >= mid:
            ch = (ch << 1) | 1
            rng[0] = mid
        else:
            ch <<= 1
            rng[1] = mid
        even = not even
        bit += 1
        if bit == 5:
            out.append(BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def bounds(cell: str) -> tuple[float, float, float, float]:
    """(min_lat, min_lng, max_lat, max_lng) of a cell."""
    lat_range, lng_range = [-90.0, 90.0], [-180.0, 180.0]
    even = True
    for c in cell:
        bits = BASE32.index(c)
        for shift in range(4, -1, -1):
            rng = lng_range if even else lat_range
            mid = (rng[0] + rng[1]) / 2
            if (bits >> shift) & 1:
                rng[0] = mid
            else:
                rng[1] = mid
            even = not even
    return lat_range[0], lng_range[0], lat_range[1], lng_range[1]


def center(cell: str) -> LatLng:
    a, b, c, d = bounds(cell)
    return (a + c) / 2, (b + d) / 2


def neighbours(cell: str) -> list[str]:
    """The 8 cells around this one, north first, clockwise."""
    min_lat, min_lng, max_lat, max_lng = bounds(cell)
    h, w = max_lat - min_lat, max_lng - min_lng
    lat, lng = (min_lat + max_lat) / 2, (min_lng + max_lng) / 2
    return [encode(lat + dy * h, (lng + dx * w + 180) % 360 - 180, len(cell)) for dy, dx in NEIGHBOUR_OFFSETS]


def grid3x3(cell: str) -> list[str]:
    return [cell, *neighbours(cell)]


def haversine(a: LatLng, b: LatLng) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * EARTH_M * math.asin(math.sqrt(h))


def bearing(a: LatLng, b: LatLng) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    y = math.sin(lng2 - lng1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lng2 - lng1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


COMPASS = ("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")


def compass(deg: float) -> str:
    return COMPASS[int((deg + 22.5) // 45) % 8]
