from datetime import datetime, timezone

import numpy as np
import pytest

from app import calendar, geo
from app.engine import Engine, Live, choose_route, preference
from app.features import Area
from app.model import FEATURES, Prediction, Predictor
from app.roads import RoadPoints
from app.schemas import PastRide, RouteOption, SuggestionRequest
from training.generate import surge_formula

PICKUP = (13.0827, 80.2707)
TYPES = ("BIKE", "AUTO", "ECONOMY", "COMFORT", "XL", "PREMIUM")
FARES = (90, 140, 210, 290, 400, 520)


class FakeModel:
    """Predictable stand-in: the wait falls as cars rise, surge is ride-service's formula."""

    version = "test"
    info = {"version": "test", "wait": {"mae": 1.5}}

    def predict(self, rows, max_surge=2.5):
        out = []
        for r in rows:
            f = dict(zip(FEATURES, r))
            surge = float(surge_formula(np.array([f["demand"]]), np.array([f["supply"]]))[0])
            out.append(Prediction(2 + 12 / (f["supply"] + 1) * f["traffic"], min(max_surge, surge)))
        return out


def ist(hh, mm=0, day=19):
    return datetime(2026, 10, day, hh, mm, tzinfo=calendar.IST).astimezone(timezone.utc)


def request(at=None, etas=(3, 4, 4, 6, 9, 8), surge=1.0, demand=3, supply=6, distance_m=8000, history=(), routes=(),
            upgrade=None, pickup=PICKUP):
    options = []
    for t, f, e in zip(TYPES, FARES, etas):
        options.append({"rideType": t, "fare": round(f * surge), "baseFare": f, "etaMinutes": e, "driversNearby": 2,
                        "upgradeTo": upgrade if t == "ECONOMY" else None})
    return SuggestionRequest.model_validate({
        "userId": "7d9f0f5e-0000-4000-8000-000000000001",
        "pickup": {"lat": pickup[0], "lng": pickup[1]}, "drop": {"lat": 13.0, "lng": 80.22},
        "requestedAt": (at or ist(14)).isoformat(), "distanceM": distance_m, "durationS": distance_m // 6,
        "surgeMultiplier": surge, "demand": demand, "supply": supply, "options": options,
        "routes": list(routes), "history": [{"rideType": t} for t in history],
    })


@pytest.fixture
def engine():
    return Engine(FakeModel(), RoadPoints([]))


def test_a_new_rider_gets_the_cheapest_ride_with_a_short_wait(engine):
    s = engine.suggest(request(), Live())
    assert s.recommendedRideType == "BIKE"
    assert "lowest fare with a short wait" in s.reasons[0]
    assert s.bestPickupTime.offsetMinutes == 0 and s.bestPickupTime.predictedFare == 90
    assert s.bestPickupTime.predictedWaitMin == 4  # the closest bike's 3 min, plus matching


def test_bikes_are_not_suggested_for_long_trips_nor_types_with_no_car_near(engine):
    s = engine.suggest(request(distance_m=22_000, etas=(3, None, 5, 6, 9, 8)), Live())
    assert s.recommendedRideType == "ECONOMY"


def test_a_regular_gets_their_usual_type_or_the_nearest_class(engine):
    assert engine.suggest(request(history=["COMFORT"] * 4 + ["ECONOMY"]), Live()).recommendedRideType == "COMFORT"
    s = engine.suggest(request(history=["COMFORT"] * 5, etas=(3, 4, 4, None, None, 8)), Live())
    assert s.recommendedRideType in ("ECONOMY", "PREMIUM")
    assert s.reasons[0].startswith("No Comfort is close right now")


def test_preference_weighs_recent_trips_and_price_sensitivity():
    assert preference([PastRide(rideType="AUTO")] * 2).favourite is None
    # three recent autos outweigh four older premium rides
    p = preference([PastRide(rideType=t) for t in ["AUTO"] * 3 + ["PREMIUM"] * 4])
    assert p.favourite == "AUTO"
    assert preference([PastRide(rideType="BIKE")] * 5).price_sensitive is True
    assert preference([PastRide(rideType="XL")] * 5).price_sensitive is False


def test_a_free_upgrade_is_called_out(engine):
    s = engine.suggest(request(history=["ECONOMY"] * 3, upgrade="COMFORT"), Live())
    assert s.recommendedRideType == "ECONOMY"
    assert any("may come as a Comfort at the Economy fare" in r for r in s.reasons)
    assert "BETTER_PRICE" in s.highlights


def test_waiting_is_suggested_when_the_surge_is_forecast_to_ease(engine):
    # the end of the morning rush at 10 am: requests fall off over the next 20 minutes, the fleet stays out
    s = engine.suggest(request(at=ist(10), surge=2.0, demand=30, supply=6, history=["ECONOMY"] * 3), Live())
    assert s.reasons[0] == "Demand in your area is high right now"
    assert s.bestPickupTime.offsetMinutes > 0
    assert s.bestPickupTime.savingsVsNow >= 15
    assert s.bestPickupTime.predictedFare == 420 - s.bestPickupTime.savingsVsNow
    assert "BETTER_PRICE" in s.highlights
    assert any(f"Waiting {s.bestPickupTime.offsetMinutes} min is predicted to cut the fare by ₹" in r for r in s.reasons)


def test_no_wait_is_suggested_for_a_small_saving(engine):
    s = engine.suggest(request(at=ist(14), surge=1.1, demand=7, supply=6), Live())
    assert s.bestPickupTime.offsetMinutes == 0 and s.bestPickupTime.savingsVsNow == 0


def edge_of_cell():
    """A pickup 50 m inside the north edge of its cell, and a road spot 150 m north, in the next cell."""
    cell = geo.encode(*PICKUP)
    _, min_lng, max_lat, max_lng = geo.bounds(cell)
    lng = (min_lng + max_lng) / 2
    pickup = (max_lat - 0.00045, lng)
    road = (max_lat + 0.0009, lng)
    return cell, pickup, road


def area(cell, supply, demand=2):
    return Area(cell, (demand,) * 8, supply)


def test_a_short_walk_is_suggested_when_cars_are_waiting_next_door():
    cell, pickup, road = edge_of_cell()
    north = geo.neighbours(cell)[0]
    assert geo.encode(*road) == north
    live = Live(pickup=area(cell, 1), neighbours={n: area(n, 14 if n == north else 1) for n in geo.neighbours(cell)},
                fresh=True)
    engine = Engine(FakeModel(), RoadPoints([road]))
    s = engine.suggest(request(pickup=pickup, etas=(9, 9, 9, 9, 9, 9), supply=1), live)
    alt = s.alternativePickup
    assert alt is not None and (alt.lat, alt.lng) == (round(road[0], 6), round(road[1], 6))
    assert 150 <= alt.walkMeters <= 220
    assert alt.predictedWaitMin < 9
    assert "SHORTER_WAIT" in s.highlights
    assert any(r.startswith(f"Walk {alt.walkMeters} m north to a main road") for r in s.reasons)


def test_no_walk_without_live_features_or_a_real_gain():
    cell, pickup, road = edge_of_cell()
    engine = Engine(FakeModel(), RoadPoints([road]))
    same = Live(pickup=area(cell, 6), neighbours={n: area(n, 6) for n in geo.neighbours(cell)}, fresh=True)
    assert engine.suggest(request(pickup=pickup), same).alternativePickup is None
    stale = Live(pickup=area(cell, 1), neighbours={n: area(n, 14) for n in geo.neighbours(cell)}, fresh=False)
    assert engine.suggest(request(pickup=pickup), stale).alternativePickup is None


HIGHWAY = RouteOption(distanceM=20_000, durationS=1500, polyline="highway")
CITY = RouteOption(distanceM=15_000, durationS=1500, polyline="city")


def test_the_city_route_wins_in_the_rush_hour_and_the_highway_at_night():
    rush = calendar.traffic_multiplier(18.75)
    choice, why = choose_route([HIGHWAY, CITY], rush)
    assert choice.choice == "alternative_1" and choice.polyline == "city"
    assert "min faster" in why
    night, _ = choose_route([HIGHWAY, CITY], calendar.traffic_multiplier(3))
    assert night.choice == "fastest" and night.polyline is None
    assert choose_route([], rush) == (None, None)


def test_a_smarter_route_is_highlighted(engine):
    s = engine.suggest(request(at=ist(18, 45), routes=[HIGHWAY.model_dump(), CITY.model_dump()]), Live())
    assert s.route.choice == "alternative_1"
    assert "SMARTER_ROUTE" in s.highlights


def test_confidence_is_higher_with_live_features(engine):
    cell = geo.encode(*PICKUP)
    live = Live(pickup=area(cell, 6), neighbours={n: area(n, 6) for n in geo.neighbours(cell)}, fresh=True)
    with_live = engine.suggest(request(), live).confidence
    without = engine.suggest(request(), Live()).confidence
    assert 0.3 <= without < with_live <= 0.95


def test_the_shipped_model_gives_a_sensible_answer():
    s = Engine(Predictor.load(), RoadPoints([])).suggest(request(at=ist(18, 30), surge=1.5, demand=20, supply=6), Live())
    assert s.recommendedRideType in TYPES
    assert 1 <= s.bestPickupTime.predictedWaitMin <= 35
    assert s.modelVersion == Predictor.load().version
    assert len(s.reasons) <= 4


def test_no_free_cars_is_not_called_high_demand(engine):
    s = engine.suggest(request(supply=0, demand=2, etas=(None,) * 6), Live())
    assert s.reasons[0] == "Few cars are free near you right now"
    assert s.recommendedRideType == "BIKE" and s.reasons[1] == "Bike is the lowest fare for this trip"


def test_a_ride_much_sooner_at_no_higher_fare_beats_the_usual_one(engine):
    # usual Economy is 12 min away, an Auto (cheaper) is 5 min away
    s = engine.suggest(request(history=["ECONOMY"] * 5, etas=(12, 5, 12, 9, 9, 8)), Live())
    assert s.recommendedRideType == "AUTO"
    assert s.reasons[0] == "Auto comes 7 min sooner and ₹70 cheaper than your usual Economy"
    assert s.highlights[:2] == ["SHORTER_WAIT", "BETTER_PRICE"]
    # only a little sooner, or much dearer: the usual one stays
    assert engine.suggest(request(history=["ECONOMY"] * 5, etas=(12, 9, 12, 9, 9, 8)), Live()).recommendedRideType == "ECONOMY"
    assert engine.suggest(request(history=["BIKE"] * 5, etas=(12, 12, 3, 9, 9, 8)), Live()).recommendedRideType == "BIKE"

