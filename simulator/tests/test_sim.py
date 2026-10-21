import asyncio
import random

import pytest

import httpx

from rydex_sim.api import READY_PATHS, AuthBudget, Endpoints, wait_for_backend
from rydex_sim.geo import along, bearing, decode_polyline, haversine, offset, path_length, random_point
from rydex_sim.otp import otp_for
from rydex_sim.stomp import Frame
from simulate import TYPES, parse_args, seeded_order, seeded_vehicle

CENTRAL = (13.0827, 80.2707)


def test_otp_matches_ride_service():
    # computed with ride-service's OtpService (HmacSHA256, secret "test-secret")
    assert otp_for("00000000-0000-4000-8000-000000000123", "test-secret") == "7845"
    assert otp_for("6b52c4f8-1d2e-4f3a-9b8c-0123456789ab", "test-secret") == "0145"
    assert otp_for("6b52c4f8-1d2e-4f3a-9b8c-0123456789ab", "other") != "0145"


def test_stomp_frames_round_trip_and_escape_headers():
    frame = Frame("SEND", {"destination": "/app/driver.location", "note": "a:b\nc"}, '{"lat":13.08}')
    raw = frame.encode()
    assert raw.endswith("\x00")
    assert "note:a\\cb\\nc" in raw
    assert Frame.decode(raw) == frame
    assert Frame.decode("\n") is None  # heart-beat


def test_connect_frames_are_not_escaped():
    raw = Frame("CONNECT", {"Authorization": "Bearer a:b"}).encode()
    assert "Authorization:Bearer a:b" in raw


def test_message_frame_from_server():
    raw = "MESSAGE\ndestination:/user/queue/offers\nsubscription:sub-1\nmessage-id:1\n\n{\"type\":\"OFFER\"}\x00"
    frame = Frame.decode(raw)
    assert frame.command == "MESSAGE"
    assert frame.headers["subscription"] == "sub-1"
    assert frame.body == '{"type":"OFFER"}'


def test_geometry():
    north = offset(CENTRAL, 1000, 0)
    assert haversine(CENTRAL, north) == pytest.approx(1000, abs=2)
    assert bearing(CENTRAL, north) == pytest.approx(0, abs=0.01)
    east = offset(CENTRAL, 500, 90)
    assert bearing(CENTRAL, east) == pytest.approx(90, abs=0.1)

    path = [CENTRAL, north, offset(north, 1000, 90)]
    assert path_length(path) == pytest.approx(2000, abs=5)
    point, heading, done = along(path, 1500)
    assert not done and heading == pytest.approx(90, abs=0.5)
    assert haversine(point, north) == pytest.approx(500, abs=5)
    assert along(path, 5000)[2] is True

    rng = random.Random(1)
    assert all(haversine(CENTRAL, random_point(CENTRAL, 4000, rng)) <= 4000.5 for _ in range(200))


def test_polyline6():
    # precision 5 reference example from Google's polyline docs
    assert decode_polyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5) == [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]


def test_endpoints_route_like_caddy():
    local = Endpoints()
    assert local.url("/api/auth/login") == "http://localhost:8081/api/auth/login"
    assert local.url("/api/drivers/me/offer") == "http://localhost:8082/api/drivers/me/offer"
    assert local.url("/api/places/route?x=1") == "http://localhost:8081/api/places/route?x=1"
    assert local.ws == "ws://localhost:8083/ws"
    remote = Endpoints("https://rydex.example.org/")
    assert remote.url("/api/drivers/me") == "https://rydex.example.org/api/drivers/me"
    assert remote.ws == "wss://rydex.example.org/ws"


def test_any_number_of_drivers_gets_every_ride_type():
    order = seeded_order()
    assert sorted(order) == list(range(2, 51))  # driver01 is left for manual testing
    first = order[:10]
    for numbers in TYPES.values():
        assert any(n in numbers for n in first)


def test_arguments():
    args = parse_args(["--drivers", "5", "--city-center", "12.99,80.17"])
    assert args.center == (12.99, 80.17)
    with pytest.raises(SystemExit):
        parse_args(["--drivers", "50"])
    with pytest.raises(SystemExit):
        parse_args(["--riders", "60"])
    city = parse_args(["--city"])
    assert (city.drivers, city.riders, city.center) == (40, 30, (13.045, 80.245))
    assert parse_args(["--city", "--riders", "5"]).riders == 5
    assert parse_args([]).riders == 0
    with pytest.raises(SystemExit):
        parse_args(["--city-center", "chennai"])


def test_auth_budget_paces_sign_ins():
    async def go():
        budget = AuthBudget(per_minute=600)  # one every 0.1 s
        loop = asyncio.get_running_loop()
        start = loop.time()
        for _ in range(4):
            await budget.acquire()
        return loop.time() - start

    assert asyncio.run(go()) >= 0.29


def test_bikes_filter_through_jams_and_autos_are_slower():
    from rydex_sim.driver import pace

    assert pace("ECONOMY", 1.0) == 1.0 and pace(None, 0.5) == 0.5
    assert pace("BIKE", 1.0) == 1.0
    assert pace("BIKE", 0.25) == pytest.approx(0.5)  # a jam that quarters a car's speed only halves a bike's
    assert pace("AUTO", 1.0) == pytest.approx(0.8)
    assert pace("AUTO", 0.25) < pace("BIKE", 0.25)


def test_seeded_vehicle_matches_the_driver_seed():
    # R__seed_demo_drivers: (ARRAY[...])[1 + g % len]
    assert seeded_vehicle(1) == ("ECONOMY", "Hyundai i20")
    assert seeded_vehicle(24) == ("PREMIUM", "Toyota Camry")
    assert seeded_vehicle(27) == ("BIKE", "Bajaj Pulsar 150")
    assert seeded_vehicle(50) == ("AUTO", "Piaggio Ape City")


class FakeSession:
    def __init__(self):
        self.calls = []

    async def call(self, method, path, body=None):
        self.calls.append((method, path, body))
        if method == "PUT":
            return {"vehicleType": body["vehicleType"], "vehicleNumber": body["vehicleNumber"], "status": "OFFLINE"}
        return {"status": (body or {}).get("status")}


def test_an_npc_driver_left_on_an_old_vehicle_takes_its_seeded_one():
    from rydex_sim.driver import Settings, SimDriver, Stats

    session = FakeSession()
    driver = SimDriver("driver24", session, None, Settings(center=CENTRAL), Stats(), random.Random(1),
                       fleet=("PREMIUM", "Toyota Camry"))
    me = {"vehicleType": "ECONOMY", "vehicleNumber": "TN 03 YM 1888", "status": "AVAILABLE"}

    after = asyncio.run(driver._take_fleet_vehicle(me))

    # the type can only change while offline: go offline first, then change the vehicle
    assert session.calls == [
        ("PATCH", "/api/drivers/me/status", {"status": "OFFLINE"}),
        ("PUT", "/api/drivers/me", {"vehicleType": "PREMIUM", "vehicleNumber": "TN 03 YM 1888", "vehicleModel": "Toyota Camry"}),
    ]
    assert after["vehicleType"] == "PREMIUM"


def test_reported_speed_is_the_city_speed_not_the_fast_forward():
    from rydex_sim.driver import TICK_S, world_speed

    # 30 km/h in the city: one tick covers 30/3.6 * TICK_S metres at 1x, five times that at 5x
    city = 30 / 3.6
    assert world_speed(city * TICK_S, 1.0) == pytest.approx(city)
    assert world_speed(city * TICK_S * 5, 5.0) == pytest.approx(city)


def test_npc_riders_rate_their_trips_once():
    from rydex_sim.rider import PinBoard, RiderSettings, RiderStats, SimRider

    session = FakeSession()
    rider = SimRider("rider9", session, PinBoard(), RiderSettings(), RiderStats(), random.Random(3))

    asyncio.run(rider._rate({"id": "ride-9", "riderRating": None}))
    asyncio.run(rider._rate({"id": "ride-8", "riderRating": 5}))  # rated already: left alone

    assert len(session.calls) == 1
    method, path, body = session.calls[0]
    assert (method, path) == ("POST", "/api/rides/ride-9/rating") and 1 <= body["stars"] <= 5


def test_a_driver_who_lost_their_position_resumes_from_the_ride_not_a_random_point():
    from rydex_sim.driver import Settings, SimDriver, Stats

    class RideSession:
        def __init__(self, status):
            self.status = status

        async def call(self, method, path, body=None):
            return {"status": self.status, "pickup": {"lat": 13.05, "lng": 80.25}, "drop": {"lat": 13.0, "lng": 80.27}}

    for status, within_m in (("IN_PROGRESS", 1), ("DRIVER_ARRIVED", 1), ("DRIVER_ASSIGNED", 1600)):
        driver = SimDriver("driver10", RideSession(status), None, Settings(center=CENTRAL), Stats(), random.Random(5))
        pos = asyncio.run(driver._ride_position("ride-1", CENTRAL))
        assert haversine(pos, (13.05, 80.25)) <= within_m, status


def test_the_city_waits_until_every_service_answers():
    calls = {}

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        calls[path] = calls.get(path, 0) + 1
        if path == "/api/drivers/me" and calls[path] <= 2:
            return httpx.Response(502)  # driver-service still starting: Caddy answers 502
        return httpx.Response(401 if path != "/api/payments/config" else 200)

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            return await wait_for_backend(http, Endpoints("http://caddy"), timeout_s=5, interval_s=0.01)

    assert asyncio.run(go()) is True
    assert calls["/api/drivers/me"] == 3
    assert calls["/api/me"] == 1  # a service that answered is not asked again
    assert set(calls) == set(READY_PATHS)


def test_a_backend_that_never_answers_does_not_hold_the_city_forever():
    async def go():
        transport = httpx.MockTransport(lambda request: httpx.Response(503))
        async with httpx.AsyncClient(transport=transport) as http:
            return await wait_for_backend(http, Endpoints("http://caddy"), timeout_s=0.05, interval_s=0.01)

    assert asyncio.run(go()) is False
