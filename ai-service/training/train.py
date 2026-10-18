"""Train the wait-time and surge regressors: ``python -m training.train``.

Writes models/wait.txt, models/surge.txt and models/metrics.json (version, rows, MAE on a held-out
fifth, and the MAE of always guessing the training mean, for scale). Training is deterministic: the
same data seed and parameters give the same models.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import lightgbm as lgb
import numpy as np

from app import calendar
from app.model import CATEGORICAL, FEATURES, MODELS_DIR
from training.generate import generate

PARAMS = {
    "objective": "regression_l1",
    "learning_rate": 0.08,
    "num_leaves": 15,
    "min_data_in_leaf": 40,
    "feature_fraction": 0.9,
    "bagging_fraction": 0.8,
    "bagging_freq": 1,
    "seed": 7,
    "deterministic": True,
    "force_row_wise": True,
    "num_threads": 1,
    "verbosity": -1,
}
ROUNDS = 400


def fit(x_train, y_train, x_valid, y_valid) -> tuple[lgb.Booster, float, float]:
    names = list(FEATURES)
    train = lgb.Dataset(x_train, y_train, feature_name=names, categorical_feature=list(CATEGORICAL))
    valid = lgb.Dataset(x_valid, y_valid, reference=train)
    booster = lgb.train(PARAMS, train, ROUNDS, valid_sets=[valid], callbacks=[lgb.early_stopping(30, verbose=False)])
    mae = float(np.mean(np.abs(booster.predict(x_valid, num_iteration=booster.best_iteration) - y_valid)))
    baseline = float(np.mean(np.abs(np.median(y_train) - y_valid)))
    return booster, mae, baseline


def main(argv: list[str] | None = None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=80_000)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--out", type=Path, default=MODELS_DIR)
    args = parser.parse_args(argv)

    data = generate(args.rows, args.seed)
    cut = int(len(data) * 0.8)
    metrics: dict = {
        "version": datetime.now(calendar.IST).date().isoformat(),
        "rows": len(data),
        "seed": args.seed,
        "features": list(FEATURES),
        "data": "synthetic (training/generate.py)",
    }
    args.out.mkdir(parents=True, exist_ok=True)
    for name, y in (("wait", data.wait), ("surge", data.surge)):
        booster, mae, baseline = fit(data.x[:cut], y[:cut], data.x[cut:], y[cut:])
        booster.save_model(str(args.out / f"{name}.txt"), num_iteration=booster.best_iteration)
        metrics[name] = {"mae": round(mae, 3), "baselineMae": round(baseline, 3), "trees": booster.best_iteration,
                         "unit": "minutes" if name == "wait" else "multiplier"}
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metrics, indent=2))
    return metrics


if __name__ == "__main__":
    main()
