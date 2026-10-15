"""Simulated city traffic (ADR 0016). The same formula runs in the web app (frontend/src/map/traffic.ts),
so the jams a simulated driver slows down in are the red stretches people see on the map.

Each road segment of frontend/public/data/traffic-roads.json has a road class and a stable hash in
[0, 1). Congestion follows Chennai's day (morning and evening rush, a quieter night), is heavier on
the big roads and on "busy" segments, and drifts slowly so the picture changes over half an hour.
"""

from __future__ import annotations

import json
import math
import random
from dataclasses import dataclass
from pathlib import Path

from .geo import LatLng, haversine

ROADS_FILE = Path(__file__).resolve().parents[2] / "frontend" / "public" / "data" / "traffic-roads.json"

# motorway/trunk, primary, secondary, tertiary
CLASS_WEIGHT = (1.15, 1.0, 0.85, 0.7)
# level thresholds: below FREE free flow (green), below SLOW slow (orange), below JAM heavy (red), else jammed
FREE, SLOW, JAM = 0.4, 0.62, 0.82
IST_OFFSET_H = 5.5


def ist_hour(t: float) -> float:
    """Hour of the day in India (0-24) at epoch seconds t."""
    return (t / 3600.0 + IST_OFFSET_H) % 24.0


def _bump(h: float, center: float, width: float) -> float:
    d = abs(h - center)
    d = min(d, 24.0 - d)
    return math.exp(-((d / width) ** 2))


def demand(hour: float) -> float:
    """How busy the city is at this hour: about 0.03 at 3 am, over 0.8 in the evening rush."""
    return (0.15 + 0.6 * _bump(hour, 9.25, 1.6) + 0.7 * _bump(hour, 18.75, 2.6)
            + 0.25 * _bump(hour, 13.5, 3.0) - 0.12 * _bump(hour, 3.0, 2.5))


def level(seg_hash: float, road_class: int, t: float) -> float:
    """Congestion of a segment at epoch seconds t: 0 free flow .. 1 standstill."""
    wave = (math.sin(2 * math.pi * (t / 1800.0 + seg_hash * 7.13))
            * math.cos(2 * math.pi * (t / 4700.0 + seg_hash * 3.71)))
    base = demand(ist_hour(t)) * CLASS_WEIGHT[road_class] * (0.55 + 0.9 * seg_hash)
    return min(1.0, max(0.0, base + 0.22 * wave))


def speed_factor(congestion: float) -> float:
    """Share of the free-flow speed a car keeps: 1 when free, 0.22 in a jam."""
    return 1.0 - 0.78 * congestion


@dataclass(frozen=True)
class Segment:
    road_class: int
    seg_hash: float
    points: tuple[LatLng, ...]


class RoadIndex:
    """The main roads, bucketed in a ~250 m grid, to find the segment a car is on."""

    CELL = 0.0025  # degrees

    def __init__(self, segments: list[Segment]):
        self.segments = segments
        self.cells: dict[tuple[int, int], list[int]] = {}
        for i, seg in enumerate(segments):
            cells: set[tuple[int, int]] = set()
            for a, b in zip(seg.points, seg.points[1:]):
                # every cell the line passes through (sampled at a third of a cell)
                steps = max(1, int(max(abs(b[0] - a[0]), abs(b[1] - a[1])) / (self.CELL / 3)))
                for k in range(steps + 1):
                    cells.add(self._cell(a[0] + (b[0] - a[0]) * k / steps, a[1] + (b[1] - a[1]) * k / steps))
            for cell in cells:
                self.cells.setdefault(cell, []).append(i)

    @classmethod
    def load(cls, path: Path = ROADS_FILE) -> RoadIndex | None:
        if not path.exists():
            return None
        doc = json.loads(path.read_text(encoding="utf-8"))
        segments = []
        for road_class, seg_hash, flat in doc["segments"]:
            pts = tuple((flat[i + 1], flat[i]) for i in range(0, len(flat), 2))
            segments.append(Segment(int(road_class), float(seg_hash), pts))
        return cls(segments)

    def _cell(self, lat: float, lng: float) -> tuple[int, int]:
        return int(math.floor(lat / self.CELL)), int(math.floor(lng / self.CELL))

    def nearest(self, p: LatLng, within_m: float = 30.0) -> Segment | None:
        """The segment passing closest to p, if one is within `within_m` metres."""
        ci, cj = self._cell(*p)
        candidates = {i for di in (-1, 0, 1) for dj in (-1, 0, 1) for i in self.cells.get((ci + di, cj + dj), ())}
        kx, ky = math.cos(math.radians(p[0])) * 111_320, 110_540
        best, best_d = None, within_m
        for i in candidates:
            pts = self.segments[i].points
            for a, b in zip(pts, pts[1:]):
                ax, ay = (a[1] - p[1]) * kx, (a[0] - p[0]) * ky
                bx, by = (b[1] - p[1]) * kx, (b[0] - p[0]) * ky
                dx, dy = bx - ax, by - ay
                l2 = dx * dx + dy * dy
                t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
                d = math.hypot(ax + dx * t, ay + dy * t)
                if d < best_d:
                    best, best_d = self.segments[i], d
        return best

    def point_near(self, p: LatLng, radius_m: float, rng: random.Random) -> LatLng | None:
        """A random point on a main road within `radius_m` of p (somewhere a car can really be), or None."""
        reach = int(radius_m / (self.CELL * 111_000)) + 1
        ci, cj = self._cell(*p)
        candidates = sorted({i for di in range(-reach, reach + 1) for dj in range(-reach, reach + 1)
                             for i in self.cells.get((ci + di, cj + dj), ())})
        points = [q for i in candidates for q in self.segments[i].points if haversine(q, p) <= radius_m]
        return rng.choice(points) if points else None

    def on_land(self, p: LatLng, within_m: float = 400) -> bool:
        """Whether p is near a main road (a car far from all of them is in the sea or a lake)."""
        return self.nearest(p, within_m) is not None

    def speed_factor_at(self, p: LatLng, t: float) -> float:
        """How fast traffic lets a car go at p (1 off the main roads, where there is no data)."""
        seg = self.nearest(p)
        return 1.0 if seg is None else speed_factor(level(seg.seg_hash, seg.road_class, t))
