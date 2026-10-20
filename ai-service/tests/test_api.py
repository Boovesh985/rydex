import json
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app import consumer, contract
from app.features import MemoryStore
from app.main import create_app

REQUEST = {
    "userId": "7d9f0f5e-0000-4000-8000-000000000001",
    "pickup": {"lat": 13.0827, "lng": 80.2707},
    "drop": {"lat": 13.0, "lng": 80.22},
    "requestedAt": "2026-10-19T08:30:00Z",
    "distanceM": 9200,
    "durationS": 1300,
    "surgeMultiplier": 1.2,
    "maxSurge": 2.5,
    "demand": 9,
    "supply": 6,
    "options": [
        {"rideType": "BIKE", "fare": 100, "baseFare": 83, "etaMinutes": 3, "driversNearby": 2},
        {"rideType": "ECONOMY", "fare": 240, "baseFare": 200, "etaMinutes": 5, "driversNearby": 3, "upgradeTo": None},
        {"rideType": "XL", "fare": 480, "baseFare": 400, "etaMinutes": None, "driversNearby": 0},
    ],
    "routes": [{"distanceM": 9200, "durationS": 1300, "polyline": "abc"}],
    "history": [{"rideType": "ECONOMY", "fare": 210}],
}


@pytest.fixture
def store():
    return MemoryStore()


@pytest.fixture
def client(store):
    with TestClient(create_app(store)) as c:
        yield c


def test_suggests_a_ride(client):
    res = client.post("/suggestions", json=REQUEST)
    assert res.status_code == 200
    body = res.json()
    assert body["recommendedRideType"] == "BIKE"
    assert set(body) >= {"suggestionId", "bestPickupTime", "alternativePickup", "route", "reasons", "highlights",
                         "confidence", "modelVersion"}
    assert body["route"]["choice"] == "fastest"
    assert body["reasons"]


def test_rejects_a_request_without_options(client):
    assert client.post("/suggestions", json={**REQUEST, "options": []}).status_code == 422
    assert client.post("/suggestions", json={**REQUEST, "pickup": {"lat": 200, "lng": 0}}).status_code == 422


def test_model_info_reports_the_metrics_and_whether_live_features_flow(client, store):
    info = client.get("/model/info").json()
    assert info["wait"]["mae"] > 0 and info["surge"]["mae"] > 0
    assert info["version"] and info["liveFeatures"] is False and info["roadPoints"] > 1000 and info["trafficSegments"] > 1000
    location = {"eventId": "e", "driverId": "d", "lat": 13.08, "lng": 80.27, "status": "AVAILABLE",
                "recordedAt": datetime.now(timezone.utc).isoformat()}
    client.portal.call(consumer.handle, store, consumer.DRIVER_LOCATION, json.dumps(location))
    assert client.get("/model/info").json()["liveFeatures"] is True


def test_health_and_metrics(client):
    assert client.get("/health").json()["status"] == "UP"
    client.post("/suggestions", json=REQUEST)
    metrics = client.get("/metrics/").text
    assert "ai_suggestion_latency_seconds_count" in metrics and "ai_suggestions_total" in metrics


def test_the_published_contract_is_up_to_date():
    assert contract.CONTRACT.read_text(encoding="utf-8") == contract.render(), "run: python -m app.contract"
