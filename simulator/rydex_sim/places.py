"""Places in Chennai the simulated riders live near and travel between (approximate coordinates)."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from typing import TYPE_CHECKING

from .geo import LatLng, haversine

if TYPE_CHECKING:
    from .traffic import RoadIndex


@dataclass(frozen=True)
class Place:
    name: str
    lat: float
    lng: float

    @property
    def point(self) -> LatLng:
        return self.lat, self.lng


PLACES: tuple[Place, ...] = (
    Place("Chennai Central", 13.0827, 80.2757),
    Place("Egmore Station", 13.0781, 80.2610),
    Place("Marina Beach", 13.0500, 80.2824),
    Place("Chepauk Stadium", 13.0628, 80.2793),
    Place("Express Avenue", 13.0583, 80.2643),
    Place("Royapettah", 13.0540, 80.2640),
    Place("Mylapore", 13.0339, 80.2696),
    Place("Alwarpet", 13.0336, 80.2546),
    Place("Teynampet", 13.0440, 80.2510),
    Place("Nungambakkam", 13.0604, 80.2425),
    Place("T Nagar", 13.0418, 80.2341),
    Place("Kodambakkam", 13.0520, 80.2250),
    Place("Vadapalani", 13.0500, 80.2121),
    Place("Ashok Nagar", 13.0350, 80.2120),
    Place("Saidapet", 13.0213, 80.2231),
    Place("Guindy", 13.0067, 80.2206),
    Place("IIT Madras", 13.0060, 80.2420),
    Place("Adyar", 13.0067, 80.2570),
    Place("Besant Nagar", 12.9990, 80.2690),
    Place("Thiruvanmiyur", 12.9830, 80.2594),
    Place("Tidel Park", 12.9894, 80.2486),
    Place("Velachery", 12.9791, 80.2209),
    Place("Kilpauk", 13.0825, 80.2420),
    Place("Purasawalkam", 13.0870, 80.2560),
    Place("Anna Nagar", 13.0870, 80.2140),
    Place("Koyambedu", 13.0694, 80.1948),
    Place("Perambur", 13.1180, 80.2330),
    Place("Parrys Corner", 13.0900, 80.2880),
)


def near(place: Place, rng: random.Random, radius_m: float = 600, roads: RoadIndex | None = None) -> LatLng:
    """A point within `radius_m` of a place: a street near it, not the landmark itself. With the road
    network, a point on a main road (never in the sea next to Marina Beach)."""
    if roads is not None:
        on_road = roads.point_near(place.point, radius_m, rng)
        if on_road is not None:
            return on_road
    r = radius_m * math.sqrt(rng.random())
    theta = rng.random() * 2 * math.pi
    dlat = r * math.cos(theta) / 111_195
    dlng = r * math.sin(theta) / (111_195 * math.cos(math.radians(place.lat)))
    return place.lat + dlat, place.lng + dlng


def destination(origin: LatLng, rng: random.Random, min_m: float = 1500, max_m: float = 9000) -> Place:
    """A place a rider at `origin` might go to: not around the corner, not across the state."""
    options = [p for p in PLACES if min_m <= haversine(origin, p.point) <= max_m]
    return rng.choice(options or list(PLACES))
