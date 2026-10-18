import json

import numpy as np

from app.model import FEATURES, Predictor, feature_row
from training import train
from training.generate import generate, surge_formula


def test_surge_formula_matches_ride_service():
    demand = np.array([0, 5, 10, 20, 100, 8])
    supply = np.array([0, 5, 5, 5, 5, 0])
    # SurgeCalculator: none without supply or while supply meets demand, then 1 + 0.25 (d/s - 1), capped at 2.5
    assert surge_formula(demand, supply).tolist() == [1.0, 1.0, 1.3, 1.8, 2.5, 1.0]


def test_generated_data_has_a_rush_hour_and_sane_ranges():
    data = generate(4000, seed=3)
    assert data.x.shape == (4000, len(FEATURES))
    assert 1 <= data.wait.min() and data.wait.max() <= 35
    assert 1 <= data.surge.min() and data.surge.max() <= 2.5
    hour = data.x[:, FEATURES.index("hour")]
    demand = data.x[:, FEATURES.index("demand")]
    assert demand[(hour > 17.5) & (hour < 20)].mean() > 2 * demand[(hour > 2) & (hour < 5)].mean()


def test_training_writes_models_that_beat_a_constant_guess(tmp_path):
    metrics = train.main(["--rows", "6000", "--out", str(tmp_path)])
    saved = json.loads((tmp_path / "metrics.json").read_text())
    assert saved == metrics
    assert metrics["wait"]["mae"] < 0.8 * metrics["wait"]["baselineMae"]
    assert metrics["surge"]["mae"] <= metrics["surge"]["baselineMae"]
    assert Predictor.load(tmp_path).predict([feature_row(9, 1, False, 4, 6, "ECONOMY", 8, 1.4)])


def test_the_shipped_model_has_learnt_the_city():
    model = Predictor.load()
    assert model.info["wait"]["mae"] < model.info["wait"]["baselineMae"]

    def wait(**kw):
        args = dict(hour=14.0, dow=2, holiday=False, demand=4.0, supply=6.0, ride_type="ECONOMY", distance_km=8.0, traffic=1.2)
        args.update(kw)
        return model.predict([feature_row(**args)])[0]

    assert wait(supply=12).wait_min < wait(supply=6).wait_min < wait(supply=1).wait_min
    assert wait(demand=20, supply=4).surge > wait(demand=4, supply=4).surge
    assert wait(demand=2, supply=8).surge < 1.1
    assert wait(traffic=1.5).wait_min > wait(traffic=1.0).wait_min
    assert wait(ride_type="BIKE").wait_min < wait(ride_type="XL").wait_min
