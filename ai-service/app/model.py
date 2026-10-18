"""The two LightGBM regressors: wait time (booking to the car at the pickup) and surge.

Both take the same feature row. ``demand`` and ``supply`` describe the 3x3 block of geohash-6 cells
around the pickup, as ride-service measures them for its surge price: ride requests over 30 minutes
and available drivers right now. For a later pickup time they are forecasts (features.py).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import lightgbm as lgb
import numpy as np

RIDE_TYPES = ("BIKE", "AUTO", "ECONOMY", "COMFORT", "XL", "PREMIUM")
FEATURES = ("hour", "dow", "holiday", "demand", "supply", "ratio", "ride_type", "distance_km", "traffic")
CATEGORICAL = ("ride_type",)

MODELS_DIR = Path(__file__).resolve().parents[1] / "models"


def ratio(demand: float, supply: float) -> float:
    return demand / max(supply, 0.5)


def feature_row(hour: float, dow: int, holiday: bool, demand: float, supply: float, ride_type: str,
                distance_km: float, traffic: float) -> list[float]:
    return [hour, float(dow), 1.0 if holiday else 0.0, demand, supply, ratio(demand, supply),
            float(RIDE_TYPES.index(ride_type)), distance_km, traffic]


@dataclass(frozen=True)
class Prediction:
    wait_min: float
    surge: float


class Predictor:
    def __init__(self, wait: lgb.Booster, surge: lgb.Booster, info: dict):
        self.wait = wait
        self.surge = surge
        self.info = info

    @classmethod
    def load(cls, directory: Path = MODELS_DIR) -> Predictor:
        info = json.loads((directory / "metrics.json").read_text(encoding="utf-8"))
        return cls(lgb.Booster(model_file=str(directory / "wait.txt")),
                   lgb.Booster(model_file=str(directory / "surge.txt")), info)

    @property
    def version(self) -> str:
        return self.info["version"]

    def predict(self, rows: list[list[float]], max_surge: float = 2.5) -> list[Prediction]:
        if not rows:
            return []
        x = np.asarray(rows, dtype=np.float64)
        waits = self.wait.predict(x)
        surges = self.surge.predict(x)
        return [Prediction(max(1.0, float(w)), min(max_surge, max(1.0, float(s)))) for w, s in zip(waits, surges)]
