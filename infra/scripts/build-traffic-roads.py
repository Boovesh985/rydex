"""Builds the road network the traffic layer and the simulator use (ADR 0016).

Fetches Chennai's main roads from OpenStreetMap (Overpass API, in small tiles: the public servers
time out on the whole city at once), simplifies them, cuts them into ~450 m segments and writes
frontend/public/data/traffic-roads.json:

    {"version": 1, "attribution": "...", "bbox": [s, w, n, e],
     "segments": [[roadClass, hash, [lng, lat, lng, lat, ...]], ...]}

roadClass: 0 motorway/trunk, 1 primary, 2 secondary, 3 tertiary (and their links).
hash: a stable number in [0, 1) per segment that gives each stretch of road its own traffic; mostly
      the road's own (so a road is busy or free along its length), partly the segment's (so it varies).

Run once (the output is committed); re-run to refresh the roads:  python infra/scripts/build-traffic-roads.py
Downloaded tiles are kept in .cache/overpass/ (git-ignored) so a re-run only rebuilds.
Road data (c) OpenStreetMap contributors, ODbL 1.0.
"""

from __future__ import annotations

import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BBOX = (12.90, 80.13, 13.20, 80.32)  # south, west, north, east: Chennai city
TILE = 0.05
SERVERS = ["https://overpass-api.de/api/interpreter"]
CLASSES = {
    "motorway": 0, "motorway_link": 0, "trunk": 0, "trunk_link": 0,
    "primary": 1, "primary_link": 1, "secondary": 2, "secondary_link": 2, "tertiary": 3,
}
SEGMENT_M = 450
SIMPLIFY_M = 6
OUT = Path(__file__).resolve().parents[2] / "frontend" / "public" / "data" / "traffic-roads.json"
UA = "Rydex/0.1 (portfolio project; +https://github.com/Boovesh985/rydex)"
CACHE = Path(__file__).resolve().parents[2] / ".cache" / "overpass"


def fetch_tile(s: float, w: float, n: float, e: float) -> list[dict]:
    cached = CACHE / f"{s:.2f}_{w:.2f}.json"
    if cached.exists():
        return json.loads(cached.read_text(encoding="utf-8"))
    elements = download_tile(s, w, n, e)
    time.sleep(2)  # be polite to the free servers
    CACHE.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(elements), encoding="utf-8")
    return elements


def download_tile(s: float, w: float, n: float, e: float) -> list[dict]:
    query = (f'[out:json][timeout:60];way["highway"~"^({"|".join(CLASSES)})$"]'
             f"({s:.4f},{w:.4f},{n:.4f},{e:.4f});out geom;")
    body = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(14):
        server = SERVERS[attempt % len(SERVERS)]
        try:
            req = urllib.request.Request(server, data=body, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=90) as res:
                doc = json.load(res)
            # a busy server answers 200 with no elements and a "remark" saying why
            if "remark" in doc and "error" in doc["remark"].lower():
                raise RuntimeError(doc["remark"][:120])
            return doc["elements"]
        except Exception as ex:  # busy server: back off and try the other one
            print(f"  tile {s:.2f},{w:.2f}: {ex}; retrying", file=sys.stderr)
            time.sleep(min(60, 6 * (attempt + 1)))
    raise SystemExit(f"could not fetch tile {s},{w}")


def meters(a: tuple[float, float], b: tuple[float, float]) -> float:
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((b[0] - a[0]) * 110_540, (b[1] - a[1]) * 111_320 * math.cos(lat))


def simplify(pts: list[tuple[float, float]], tol: float) -> list[tuple[float, float]]:
    """Douglas-Peucker in metres."""
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    lat = math.radians(a[0])
    kx, ky = 111_320 * math.cos(lat), 110_540
    bx, by = (b[1] - a[1]) * kx, (b[0] - a[0]) * ky
    seg2 = bx * bx + by * by
    worst, idx = -1.0, 0
    for i in range(1, len(pts) - 1):
        px, py = (pts[i][1] - a[1]) * kx, (pts[i][0] - a[0]) * ky
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (px * bx + py * by) / seg2))
        d = math.hypot(px - bx * t, py - by * t)
        if d > worst:
            worst, idx = d, i
    if worst <= tol:
        return [a, b]
    return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)


def split(pts: list[tuple[float, float]], every: float) -> list[list[tuple[float, float]]]:
    """Cuts a line into pieces of about `every` metres (the last one takes the remainder)."""
    total = sum(meters(pts[i - 1], pts[i]) for i in range(1, len(pts)))
    pieces = max(1, round(total / every))
    target = total / pieces
    out, cur, acc = [], [pts[0]], 0.0
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]
        d = meters(a, b)
        while len(out) < pieces - 1 and acc + d >= target and d > 0:
            t = (target - acc) / d
            mid = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            cur.append(mid)
            out.append(cur)
            cur, a, d, acc = [mid], mid, d * (1 - t), 0.0
        cur.append(b)
        acc += d
    out.append(cur)
    return [p for p in out if len(p) >= 2]


def fnv1a(text: str) -> float:
    h = 0x811C9DC5
    for ch in text.encode():
        h = ((h ^ ch) * 0x01000193) & 0xFFFFFFFF
    return h / 2**32


def main() -> None:
    s0, w0, n0, e0 = BBOX
    ways: dict[int, dict] = {}
    lat = s0
    while lat < n0 - 1e-9:
        lng = w0
        while lng < e0 - 1e-9:
            tile = (lat, lng, min(n0, lat + TILE), min(e0, lng + TILE))
            elements = fetch_tile(*tile)
            for el in elements:
                if el.get("type") == "way" and el.get("geometry"):
                    ways[el["id"]] = el
            print(f"tile {tile[0]:.2f},{tile[1]:.2f}: {len(elements)} ways ({len(ways)} total)", file=sys.stderr, flush=True)
            lng += TILE
        lat += TILE

    segments = []
    for wid in sorted(ways):
        el = ways[wid]
        cls = CLASSES[el["tags"]["highway"]]
        pts = [(g["lat"], g["lon"]) for g in el["geometry"]]
        pts = simplify(pts, SIMPLIFY_M)
        for i, piece in enumerate(split(pts, SEGMENT_M)):
            flat = []
            for la, ln in piece:
                flat += [round(ln, 5), round(la, 5)]
            segments.append([cls, round(0.65 * fnv1a(str(wid)) + 0.35 * fnv1a(f"{wid}:{i}"), 4), flat])

    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = {"version": 1, "attribution": "Road data (c) OpenStreetMap contributors, ODbL 1.0",
           "bbox": list(BBOX), "segments": segments}
    OUT.write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")
    print(f"{len(ways)} ways -> {len(segments)} segments, {OUT.stat().st_size / 1e6:.2f} MB at {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
