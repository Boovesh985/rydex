"""Where a rider could walk to be picked up: points on the main roads (the traffic map's OSM roads)."""

from __future__ import annotations

import json
import os
from pathlib import Path

from . import geo

DEFAULT_FILE = Path(__file__).resolve().parents[2] / "frontend" / "public" / "data" / "traffic-roads.json"
CELL = 0.0025  # degrees, about 250 m


class RoadPoints:
    def __init__(self, points: list[geo.LatLng]):
        self.cells: dict[tuple[int, int], list[geo.LatLng]] = {}
        for p in points:
            self.cells.setdefault(self._cell(*p), []).append(p)

    @staticmethod
    def _cell(lat: float, lng: float) -> tuple[int, int]:
        return int(lat // CELL), int(lng // CELL)

    @classmethod
    def load(cls, path: Path | None = None) -> RoadPoints:
        path = path or Path(os.environ.get("ROADS_FILE", DEFAULT_FILE))
        if not path.exists():
            return cls([])
        data = json.loads(path.read_text(encoding="utf-8"))
        points: list[geo.LatLng] = []
        for _cls, _hash, coords in data["segments"]:
            points.extend((coords[i + 1], coords[i]) for i in range(0, len(coords) - 1, 2))
        return cls(points)

    def __len__(self) -> int:
        return sum(len(v) for v in self.cells.values())

    def near(self, p: geo.LatLng, radius_m: float) -> list[tuple[float, geo.LatLng]]:
        """Road points within ``radius_m`` of p, closest first, as (metres, point)."""
        reach = int(radius_m / 250) + 1
        ci, cj = self._cell(*p)
        found = []
        for i in range(ci - reach, ci + reach + 1):
            for j in range(cj - reach, cj + reach + 1):
                for q in self.cells.get((i, j), ()):
                    d = geo.haversine(p, q)
                    if d <= radius_m:
                        found.append((d, q))
        found.sort()
        return found
