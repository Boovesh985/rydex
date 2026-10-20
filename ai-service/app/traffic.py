"""The city's simulated traffic (ADR 0016), to time a route as the drivers will actually drive it.

The same formula runs in the web app (frontend/src/map/traffic.ts) and the simulator
(simulator/rydex_sim/traffic.py): the red stretches on the map are where the simulated cars crawl,
so a route through them is slower here too. Shared test vectors pin the three together.
"""

from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.spatial import cKDTree

from . import geo

DEFAULT_FILE = Path(__file__).resolve().parents[2] / "frontend" / "public" / "data" / "traffic-roads.json"

# motorway/trunk, primary, secondary, tertiary
CLASS_WEIGHT = (1.15, 1.0, 0.85, 0.7)
IST_OFFSET_H = 5.5
ON_ROAD_M = 30.0  # a route point this close to a mapped road is on it
STEP_M = 100.0  # how finely a route is sampled


def ist_hour(t: float) -> float:
    return (t / 3600.0 + IST_OFFSET_H) % 24.0


def _bump(h: float, center: float, width: float) -> float:
    d = abs(h - center)
    d = min(d, 24.0 - d)
    return math.exp(-((d / width) ** 2))


def demand(hour: float) -> float:
    return (0.15 + 0.6 * _bump(hour, 9.25, 1.6) + 0.7 * _bump(hour, 18.75, 2.6)
            + 0.25 * _bump(hour, 13.5, 3.0) - 0.12 * _bump(hour, 3.0, 2.5))


def level(seg_hash: float, road_class: int, t: float) -> float:
    """Congestion of a segment at epoch seconds t: 0 free flow .. 1 standstill."""
    wave = (math.sin(2 * math.pi * (t / 1800.0 + seg_hash * 7.13))
            * math.cos(2 * math.pi * (t / 4700.0 + seg_hash * 3.71)))
    base = demand(ist_hour(t)) * CLASS_WEIGHT[road_class] * (0.55 + 0.9 * seg_hash)
    return min(1.0, max(0.0, base + 0.22 * wave))


def level_np(seg_hash: np.ndarray, road_class: np.ndarray, t: float) -> np.ndarray:
    """``level`` for many segments at once."""
    wave = np.sin(2 * np.pi * (t / 1800.0 + seg_hash * 7.13)) * np.cos(2 * np.pi * (t / 4700.0 + seg_hash * 3.71))
    base = demand(ist_hour(t)) * np.asarray(CLASS_WEIGHT)[road_class] * (0.55 + 0.9 * seg_hash)
    return np.clip(base + 0.22 * wave, 0.0, 1.0)


def speed_factor(congestion: float) -> float:
    """Share of the free-flow speed a car keeps: 1 when free, 0.22 in a jam."""
    return 1.0 - 0.78 * congestion


def decode_polyline(encoded: str, precision: int = 6) -> list[geo.LatLng]:
    """Google's encoded polyline (OSRM polyline6) to (lat, lng) points."""
    points: list[geo.LatLng] = []
    index = lat = lng = 0
    factor = 10.0**precision
    while index < len(encoded):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += delta
            else:
                lng += delta
        points.append((lat / factor, lng / factor))
    return points


@dataclass(frozen=True)
class Segment:
    road_class: int
    seg_hash: float
    points: tuple[geo.LatLng, ...]


class TrafficRoads:
    """The main roads of the traffic map, as points every DENSE_M metres in a k-d tree, to find the road a
    point is on. A whole route is looked up in one query (about a millisecond for a 30 km trip)."""

    DENSE_M = 10.0  # finer than ON_ROAD_M: off the true line by at most about half a metre

    def __init__(self, segments: list[Segment]):
        self.segments = segments
        self.hashes = np.array([s.seg_hash for s in segments], dtype=float)
        self.classes = np.array([s.road_class for s in segments], dtype=int)
        lat0 = float(np.mean([p[0] for s in segments for p in s.points])) if segments else 13.0
        self._k = (math.cos(math.radians(lat0)) * 111_320.0, 110_540.0)  # metres per degree of lng, lat
        # every straight piece of every segment, then points along each, DENSE_M apart, plus each segment's end
        starts = [(seg.points[k], seg.points[k + 1], i) for i, seg in enumerate(segments) for k in range(len(seg.points) - 1)]
        if not starts:
            self._owner, self._tree = np.zeros(0, dtype=int), None
            return
        a = self._xy(np.array([s[0] for s in starts], dtype=float))
        b = self._xy(np.array([s[1] for s in starts], dtype=float))
        piece_owner = np.array([s[2] for s in starts])
        n = np.maximum(1, np.ceil(np.hypot(*(b - a).T) / self.DENSE_M)).astype(int)
        piece = np.repeat(np.arange(len(n)), n)
        frac = (np.arange(int(n.sum())) - np.repeat(np.cumsum(n) - n, n)) / n[piece]
        ends = self._xy(np.array([seg.points[-1] for seg in segments], dtype=float))
        self._owner = np.concatenate([piece_owner[piece], np.arange(len(segments))])
        self._tree = cKDTree(np.concatenate([a[piece] + (b - a)[piece] * frac[:, None], ends]))

    @classmethod
    def load(cls, path: Path | None = None) -> TrafficRoads:
        path = path or Path(os.environ.get("ROADS_FILE", DEFAULT_FILE))
        if not path.exists():
            return cls([])
        doc = json.loads(path.read_text(encoding="utf-8"))
        return cls([Segment(int(c), float(h), tuple((flat[i + 1], flat[i]) for i in range(0, len(flat) - 1, 2)))
                    for c, h, flat in doc["segments"]])

    def __len__(self) -> int:
        return len(self.segments)

    def _xy(self, latlng: np.ndarray) -> np.ndarray:
        """(lat, lng) degrees to local metres (x east, y north); flat enough across one city."""
        return np.stack([latlng[:, 1] * self._k[0], latlng[:, 0] * self._k[1]], axis=1) if len(latlng) else latlng

    def _on_road(self, latlng: np.ndarray) -> np.ndarray:
        """The segment each point is on (within ON_ROAD_M), or -1."""
        if self._tree is None:
            return np.full(len(latlng), -1)
        dist, idx = self._tree.query(self._xy(latlng), distance_upper_bound=ON_ROAD_M)
        near = np.isfinite(dist)
        return np.where(near, self._owner[np.minimum(idx, len(self._owner) - 1)], -1)

    def nearest(self, p: geo.LatLng) -> Segment | None:
        """The segment within ON_ROAD_M of p, if any."""
        i = int(self._on_road(np.array([p], dtype=float))[0])
        return None if i < 0 else self.segments[i]

    def drive_minutes(self, points: list[geo.LatLng], duration_s: float, t: float) -> float | None:
        """The route's free-flow time (OSRM's), each stretch slowed by the traffic on its road at epoch t.

        Stretches off the mapped main roads keep their free-flow pace. None if the route has no shape.
        """
        if len(points) < 2 or duration_s <= 0:
            return None
        deg = np.array(points, dtype=float)
        rad = np.radians(deg)
        lat1, lng1, lat2, lng2 = rad[:-1, 0], rad[:-1, 1], rad[1:, 0], rad[1:, 1]
        h = np.sin((lat2 - lat1) / 2) ** 2 + np.cos(lat1) * np.cos(lat2) * np.sin((lng2 - lng1) / 2) ** 2
        legs = 2 * geo.EARTH_M * np.arcsin(np.sqrt(h))
        length = float(legs.sum())
        if length <= 0:
            return None
        # sample every leg at the middle of STEP_M-long pieces
        n = np.maximum(1, np.ceil(legs / STEP_M)).astype(int)
        leg = np.repeat(np.arange(len(legs)), n)
        frac = (np.arange(int(n.sum())) - np.repeat(np.cumsum(n) - n, n) + 0.5) / n[leg]
        samples = deg[leg] + (deg[leg + 1] - deg[leg]) * frac[:, None]
        weight = legs[leg] / n[leg] * (duration_s / length)  # free-flow seconds of each piece
        seg = self._on_road(samples)
        on = seg >= 0
        slow = np.ones(len(samples))
        if on.any():
            slow[on] = 1.0 - 0.78 * level_np(self.hashes[seg[on]], self.classes[seg[on]], t)
        return float((weight / slow).sum()) / 60
