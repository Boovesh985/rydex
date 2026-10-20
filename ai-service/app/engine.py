"""Smart ride suggestions: which ride type, when, from where, and which way (master prompt §9).

1. Ride type from the rider's history (what they usually book, how price-sensitive they are); a new
   rider gets the cheapest type with a short wait.
2. Pickup time: the wait and surge models evaluated now and in 10 and 20 minutes, on forecast demand
   and supply; a later time is suggested only when it saves a worthwhile amount.
3. Pickup point: a spot on a main road in a neighbouring cell, when more free cars around it make the
   wait clearly shorter, walk included.
4. Route: OSRM's alternatives timed through the city's simulated traffic (the map's traffic layer),
   or, for routes without a shape, with the time-of-day traffic multiplier.
5. Reasons: plain-language templates, no LLM.
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field

from . import calendar, geo
from .features import Area, fleet_change, seasonal
from .model import RIDE_TYPES, Predictor, feature_row
from .roads import RoadPoints
from .schemas import (AlternativePickup, BestPickupTime, PastRide, RideOption, RouteChoice, RouteOption,
                      SuggestionRequest, SuggestionResponse)
from .traffic import TrafficRoads, decode_polyline

LABEL = {"BIKE": "Bike", "AUTO": "Auto", "ECONOMY": "Economy", "COMFORT": "Comfort", "XL": "XL", "PREMIUM": "Premium"}
BUDGET = ("BIKE", "AUTO", "ECONOMY")
OFFSETS = (0, 10, 20)
ACCEPTABLE_ETA_MIN = 12
SHORT_ETA_MIN = 8
SOONER_MIN = 5  # a ride this much sooner, at no more than SOONER_FARE times the fare, beats the usual one
SOONER_FARE = 1.15
BIKE_MAX_KM = 15
MIN_HISTORY = 3
WALK_MAX_M = 400
WALK_MIN_M = 60
WALK_M_PER_MIN = 75
ROUTE_FACTOR = 1.25  # walking distance over the straight line


@dataclass
class Live:
    """What the feature store knows right now; empty when Kafka or Redis is unavailable."""

    pickup: Area | None = None
    neighbours: dict[str, Area] = field(default_factory=dict)
    fresh: bool = False


@dataclass(frozen=True)
class Preference:
    favourite: str | None
    price_sensitive: bool | None  # None: not enough history to tell

    @property
    def minute_value(self) -> float:
        """INR a minute of the rider's time is worth, for trading a wait against a fare."""
        if self.price_sensitive is None:
            return 2.5
        return 1.5 if self.price_sensitive else 4.0


def _a(label: str) -> str:
    return ("an " if label[0] in "AEIOU" or label == "XL" else "a ") + label


def preference(history: list[PastRide]) -> Preference:
    recent = history[:20]
    if len(recent) < MIN_HISTORY:
        return Preference(None, None)
    weights: dict[str, float] = {}
    budget = total = 0.0
    for i, ride in enumerate(recent):
        w = 0.9**i  # the latest trips count most
        weights[ride.rideType] = weights.get(ride.rideType, 0.0) + w
        total += w
        budget += w if ride.rideType in BUDGET else 0.0
    favourite = max(weights, key=lambda t: (weights[t], -RIDE_TYPES.index(t)))
    return Preference(favourite, budget / total >= 0.6)


def _eligible(options: list[RideOption], distance_km: float) -> list[RideOption]:
    usable = [o for o in options if not (o.rideType == "BIKE" and distance_km > BIKE_MAX_KM)]
    near = [o for o in usable if o.etaMinutes is not None and o.etaMinutes <= ACCEPTABLE_ETA_MIN]
    return near or [o for o in usable if o.etaMinutes is not None] or usable


def choose_type(options: list[RideOption], pref: Preference, distance_km: float) -> tuple[RideOption, str]:
    pool = _eligible(options, distance_km)
    by_type = {o.rideType: o for o in pool}
    if pref.favourite:
        fav = LABEL[pref.favourite]
        if pref.favourite in by_type:
            o = by_type[pref.favourite]
            return o, f"You usually ride {fav}" + (f", and one is {o.etaMinutes} min away" if o.etaMinutes is not None else "")
        rank = RIDE_TYPES.index(pref.favourite)
        # a class the rider would take instead, if one is on its way, even if a little further out
        usable = [o for o in options if o.etaMinutes is not None and not (o.rideType == "BIKE" and distance_km > BIKE_MAX_KM)]
        similar = ([o for o in pool if _comparable(pref.favourite, o.rideType)]
                   or [o for o in usable if _comparable(pref.favourite, o.rideType)] or pool)
        # the closest class to the usual one; a tie goes the way the rider leans on price
        o = min(similar, key=lambda o: (abs(RIDE_TYPES.index(o.rideType) - rank),
                                     RIDE_TYPES.index(o.rideType) * (1 if pref.price_sensitive else -1)))
        when = f" {o.etaMinutes} min away" if o.etaMinutes is not None else ""
        return o, f"No {fav} is close right now; {LABEL[o.rideType]} is the nearest match{when}"
    short = [o for o in pool if o.etaMinutes is not None and o.etaMinutes <= SHORT_ETA_MIN]
    o = min(short or pool, key=lambda o: (o.fare, RIDE_TYPES.index(o.rideType)))
    if o.etaMinutes is not None:
        return o, f"{LABEL[o.rideType]} is the lowest fare with a short wait: {o.etaMinutes} min away"
    return o, f"{LABEL[o.rideType]} is the lowest fare for this trip"


def _comparable(usual: str, other: str) -> bool:
    """A class the rider would take instead: any bigger one, or one step down, but never a bike in place of a seat."""
    step = RIDE_TYPES.index(other) - RIDE_TYPES.index(usual)
    return step >= -1 and (other != "BIKE" or usual == "BIKE")


def sooner(chosen: RideOption, options: list[RideOption], pref: Preference, distance_km: float) -> RideOption | None:
    """A comparable ride at a similar or lower fare that comes at least SOONER_MIN minutes sooner, if any."""
    if chosen.etaMinutes is None:
        return None
    limit = chosen.fare * (1.0 if pref.price_sensitive else SOONER_FARE)
    faster = [o for o in _eligible(options, distance_km)
              if o.etaMinutes is not None and o.etaMinutes <= chosen.etaMinutes - SOONER_MIN and o.fare <= limit
              and _comparable(chosen.rideType, o.rideType)]
    return min(faster, key=lambda o: (o.etaMinutes, o.fare)) if faster else None


def route_minutes(route: RouteOption, traffic: float) -> float:
    """Free-flow time stretched by traffic, more on fast roads: the big roads are the ones that jam."""
    if route.durationS <= 0:
        return 0.0
    kmh = route.distanceM / route.durationS * 3.6
    main_roads = min(1.0, max(0.0, (kmh - 15) / 30))
    return route.durationS / 60 * (1 + (traffic - 1) * (0.6 + 0.8 * main_roads))


def traffic_minutes(routes: list[RouteOption], city: TrafficRoads | None, t: float) -> list[float] | None:
    """Each route timed through the traffic on its roads at epoch t; None unless every route has a shape."""
    if city is None or not len(city) or any(not r.polyline for r in routes):
        return None
    try:
        timed = [city.drive_minutes(decode_polyline(r.polyline), r.durationS, t) for r in routes]
    except (IndexError, ValueError):  # not a polyline after all
        return None
    return None if any(m is None for m in timed) else timed


def choose_route(routes: list[RouteOption], traffic: float, city: TrafficRoads | None = None,
                 t: float = 0.0) -> tuple[RouteChoice | None, str | None]:
    if not routes:
        return None, None
    scored = traffic_minutes(routes, city, t) or [route_minutes(r, traffic) for r in routes]
    best = min(range(len(routes)), key=lambda i: scored[i])
    saved = scored[0] - scored[best]
    if best != 0 and saved >= 2 and saved >= 0.05 * scored[0]:
        return (RouteChoice(choice=f"alternative_{best}", predictedDurationMin=round(scored[best]),
                            polyline=routes[best].polyline),
                f"Traffic is heavy on the usual way: the other route is predicted at {round(scored[best])} min, "
                f"{round(saved)} min faster")
    return RouteChoice(choice="fastest", predictedDurationMin=round(scored[0])), None


class Engine:
    def __init__(self, predictor: Predictor, roads: RoadPoints, city: TrafficRoads | None = None):
        self.predictor = predictor
        self.roads = roads
        self.city = city

    def suggest(self, req: SuggestionRequest, live: Live) -> SuggestionResponse:
        at = req.requestedAt
        hour = calendar.hour_of(at)
        off = calendar.is_off_day(at)
        dow = calendar.local(at).weekday()
        holiday = calendar.is_holiday(at)
        distance_km = req.distanceM / 1000
        traffic = calendar.traffic_multiplier(hour, off)
        reasons: list[str] = []
        highlights: list[str] = []

        pref = preference(req.history)
        option, why_type = choose_type(req.options, pref, distance_km)
        quicker = sooner(option, req.options, pref, distance_km) if pref.favourite else None
        if quicker:
            cheaper = option.fare - quicker.fare
            price = f" and ₹{round(cheaper)} cheaper" if cheaper >= 1 else ""
            why_type = (f"{LABEL[quicker.rideType]} comes {option.etaMinutes - quicker.etaMinutes} min sooner{price} "
                        f"than your usual {LABEL[option.rideType]}")
            option = quicker
            highlights.append("SHORTER_WAIT")
            if cheaper >= 1:
                highlights.append("BETTER_PRICE")
        reasons.append(why_type)
        if option.upgradeTo:
            reasons.append(f"It may come as {_a(LABEL[option.upgradeTo])} at the {LABEL[option.rideType]} fare")
            highlights.append("BETTER_PRICE")

        # demand and supply around the pickup now: ride-service's own counts, else the feature store's
        demand0 = float(req.demand) if req.demand is not None else (live.pickup.demand_now(at) if live.pickup else 0.0)
        supply0 = float(req.supply) if req.supply is not None else float(live.pickup.supply if live.pickup else 0)

        def row(offset: int, demand: float, supply: float) -> list[float]:
            h = (hour + offset / 60) % 24
            return feature_row(h, dow, holiday, demand, supply, option.rideType, distance_km,
                               calendar.traffic_multiplier(h, off))

        rows = []
        for k in OFFSETS:
            if k == 0:
                rows.append(row(0, demand0, supply0))
            else:
                demand_k = (live.pickup.forecast(at, k) if live.fresh and live.pickup else demand0 * seasonal(at, k))
                rows.append(row(k, demand_k, supply0 * fleet_change(at, k)))
        preds = self.predictor.predict(rows, req.maxSurge)

        # anchor the model to what is known now: the closest car's ETA and the quoted surge
        observed = option.etaMinutes + 1 if option.etaMinutes is not None else None
        scale = min(2.0, max(0.5, observed / preds[0].wait_min)) if observed is not None else 1.0
        base = option.baseFare or option.fare / req.surgeMultiplier
        candidates = []
        for k, p in zip(OFFSETS, preds):
            wait = observed if k == 0 and observed is not None else p.wait_min * scale
            # the model's change from now, added to the surge quoted now, in ride-service's 0.1 steps
            change = p.surge - preds[0].surge
            surge = req.surgeMultiplier if k == 0 else min(req.maxSurge, max(1.0, math.floor((req.surgeMultiplier + change) * 10 + 0.5) / 10))
            fare = option.fare if k == 0 else max(base, round(option.fare / req.surgeMultiplier * surge))
            candidates.append((k, wait, round(fare), surge))
        minute = pref.minute_value

        def cost(c):  # waiting at home costs less than waiting at the kerb
            k, wait, fare, _ = c
            return fare + minute * (0.5 * k + wait)

        now = candidates[0]
        best = min(candidates, key=cost)
        savings = now[2] - best[2]
        if best[0] == 0 or savings < max(15, 0.08 * now[2]):
            best, savings = now, 0
        if req.surgeMultiplier > 1 or (supply0 > 0 and demand0 > 1.2 * supply0):
            reasons.insert(0, "Demand in your area is high right now")
        elif supply0 == 0:
            reasons.insert(0, "Few cars are free near you right now")
        if best[0] > 0:
            reasons.append(f"Waiting {best[0]} min is predicted to cut the fare by ₹{savings}")
            highlights.append("BETTER_PRICE")
        best_time = BestPickupTime(offsetMinutes=best[0], predictedWaitMin=max(1, round(best[1])),
                                   predictedFare=best[2], savingsVsNow=savings)

        alternative = self._alternative_pickup(req, live, option, now[1], row)
        if alternative:
            spot, why = alternative
            reasons.append(why)
            highlights.append("SHORTER_WAIT")

        route, why_route = choose_route(req.routes, traffic, self.city, at.timestamp())
        if why_route:
            reasons.append(why_route)
            highlights.append("SMARTER_ROUTE")

        confidence = 1 - self.predictor.info["wait"]["mae"] / 6
        confidence += 0.1 if live.fresh else -0.1
        confidence += 0.05 if observed is not None else -0.05
        confidence -= 0.05 if pref.favourite is None else 0
        return SuggestionResponse(
            suggestionId=uuid.uuid4(),
            recommendedRideType=option.rideType,
            bestPickupTime=best_time,
            alternativePickup=alternative[0] if alternative else None,
            route=route,
            reasons=reasons[:4],
            highlights=list(dict.fromkeys(highlights)),
            confidence=round(min(0.95, max(0.3, confidence)), 2),
            modelVersion=self.predictor.version,
        )

    def _alternative_pickup(self, req: SuggestionRequest, live: Live, option: RideOption, wait_now: float,
                            row) -> tuple[AlternativePickup, str] | None:
        """A road spot in a neighbouring cell where the model expects a clearly shorter wait."""
        if not (live.fresh and live.pickup and live.neighbours):
            return None
        here = (req.pickup.lat, req.pickup.lng)
        spots: dict[str, tuple[float, geo.LatLng]] = {}
        for d, q in self.roads.near(here, WALK_MAX_M / ROUTE_FACTOR):
            cell = geo.encode(*q)
            if d >= WALK_MIN_M and cell in live.neighbours and cell not in spots:
                spots[cell] = (d, q)
        if not spots:
            return None
        at = req.requestedAt
        mine = live.pickup
        cells = list(spots)
        rows = [row(0, mine.demand_now(at), float(mine.supply))]
        rows += [row(0, live.neighbours[c].demand_now(at), float(live.neighbours[c].supply)) for c in cells]
        preds = self.predictor.predict(rows, req.maxSurge)
        best = None
        for c, p in zip(cells, preds[1:]):
            d, q = spots[c]
            walk_m = d * ROUTE_FACTOR
            wait = wait_now * p.wait_min / preds[0].wait_min
            # the car drives over while the rider walks
            effective = max(wait, walk_m / WALK_M_PER_MIN)
            gain = wait_now - effective
            if gain >= 2 and (best is None or gain > best[0]):
                best = (gain, q, walk_m, effective)
        if best is None:
            return None
        _, q, walk_m, effective = best
        walk = int(round(walk_m / 10) * 10)
        direction = geo.compass(geo.bearing(here, q))
        return (AlternativePickup(lat=round(q[0], 6), lng=round(q[1], 6), walkMeters=walk,
                                  predictedWaitMin=max(1, round(effective))),
                f"Walk {walk} m {direction} to a main road: {_a(LABEL[option.rideType])} is predicted there in "
                f"{max(1, round(effective))} min instead of {max(1, round(wait_now))}")

