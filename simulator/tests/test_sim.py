import asyncio
import random

import pytest

from rydex_sim.api import AuthBudget, Endpoints
from rydex_sim.geo import along, bearing, decode_polyline, haversine, offset, path_length, random_point
from rydex_sim.otp import otp_for
from rydex_sim.stomp import Frame
from simulate import parse_args, seeded_order

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
    assert sorted(order) == list(range(1, 51))
    first = order[:5]
    assert any(n <= 30 for n in first) and any(31 <= n <= 42 for n in first) and any(n >= 43 for n in first)


def test_arguments():
    args = parse_args(["--drivers", "5", "--city-center", "12.99,80.17"])
    assert args.center == (12.99, 80.17)
    with pytest.raises(SystemExit):
        parse_args(["--drivers", "51"])
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
