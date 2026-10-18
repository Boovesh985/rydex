"""The /suggestions contract (contracts/ai/suggestions.schema.json is generated from these models)."""

from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

RideType = Literal["BIKE", "AUTO", "ECONOMY", "COMFORT", "XL", "PREMIUM"]
Highlight = Literal["SHORTER_WAIT", "BETTER_PRICE", "SMARTER_ROUTE"]


class Model(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Point(Model):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


class RideOption(Model):
    """One ride type as ride-service quoted it."""

    rideType: RideType
    fare: float = Field(gt=0, description="INR, at the current surge")
    baseFare: float | None = Field(None, gt=0, description="INR, the same trip without surge")
    etaMinutes: int | None = Field(None, ge=0, description="the closest free car; null when none is near")
    driversNearby: int = Field(0, ge=0)
    upgradeTo: RideType | None = None


class RouteOption(Model):
    distanceM: int = Field(ge=0)
    durationS: int = Field(ge=0, description="free-flow driving time (OSRM)")
    polyline: str | None = None


class PastRide(Model):
    rideType: RideType
    fare: float | None = None


class SuggestionRequest(Model):
    userId: UUID
    pickup: Point
    drop: Point
    requestedAt: datetime
    distanceM: int = Field(ge=0)
    durationS: int = Field(ge=0)
    surgeMultiplier: float = Field(1.0, ge=1)
    maxSurge: float = Field(2.5, ge=1)
    demand: int | None = Field(None, ge=0, description="ride requests around the pickup, last 15-30 min")
    supply: int | None = Field(None, ge=0, description="free drivers around the pickup")
    options: list[RideOption] = Field(min_length=1)
    routes: list[RouteOption] = Field(default_factory=list, description="the quoted route first, then OSRM's alternatives")
    history: list[PastRide] = Field(default_factory=list, max_length=50, description="the rider's recent trips, newest first")


class BestPickupTime(Model):
    offsetMinutes: int
    predictedWaitMin: int
    predictedFare: int
    savingsVsNow: int


class AlternativePickup(Model):
    lat: float
    lng: float
    walkMeters: int
    predictedWaitMin: int


class RouteChoice(Model):
    choice: str = Field(description='"fastest" or "alternative_N"')
    predictedDurationMin: int
    polyline: str | None = Field(None, description="only for an alternative")


class SuggestionResponse(Model):
    suggestionId: UUID
    recommendedRideType: RideType
    bestPickupTime: BestPickupTime
    alternativePickup: AlternativePickup | None = None
    route: RouteChoice | None = None
    reasons: list[str]
    highlights: list[Highlight]
    confidence: float = Field(ge=0, le=1)
    modelVersion: str
