"""Small geometry helpers: distances, headings, walking along a route, OSRM polylines."""

from __future__ import annotations

import math
import random

LatLng = tuple[float, float]

EARTH_M = 6_371_000.0


def haversine(a: LatLng, b: LatLng) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * EARTH_M * math.asin(math.sqrt(h))


def bearing(a: LatLng, b: LatLng) -> float:
    """Compass heading from a to b, 0-360 degrees, 0 = north."""
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    y = math.sin(lng2 - lng1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lng2 - lng1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def offset(p: LatLng, meters: float, heading_deg: float) -> LatLng:
    """The point `meters` from p towards `heading_deg` (fine for the few km a city needs)."""
    h = math.radians(heading_deg)
    dlat = meters * math.cos(h) / 111_195.0
    dlng = meters * math.sin(h) / (111_195.0 * math.cos(math.radians(p[0])))
    return p[0] + dlat, p[1] + dlng


def random_point(center: LatLng, radius_m: float, rng: random.Random) -> LatLng:
    """Uniform over the disc around center."""
    return offset(center, radius_m * math.sqrt(rng.random()), rng.uniform(0, 360))


def path_length(path: list[LatLng]) -> float:
    return sum(haversine(path[i - 1], path[i]) for i in range(1, len(path)))


def along(path: list[LatLng], meters: float) -> tuple[LatLng, float, bool]:
    """(point `meters` along path, heading of that segment, whether the end was reached)."""
    if len(path) == 1:
        return path[0], 0.0, True
    left = max(0.0, meters)
    for i in range(1, len(path)):
        seg = haversine(path[i - 1], path[i])
        if left <= seg:
            t = 1.0 if seg == 0 else left / seg
            a, b = path[i - 1], path[i]
            return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t), bearing(a, b), False
        left -= seg
    return path[-1], bearing(path[-2], path[-1]), True


def decode_polyline(encoded: str, precision: int = 6) -> list[LatLng]:
    """Google encoded polyline; Rydex/OSRM use precision 6 (polyline6)."""
    factor = 10**precision
    points: list[LatLng] = []
    index = lat = lng = 0
    while index < len(encoded):
        deltas = []
        for _ in range(2):
            result = shift = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            deltas.append(~(result >> 1) if result & 1 else result >> 1)
        lat += deltas[0]
        lng += deltas[1]
        points.append((lat / factor, lng / factor))
    return points
