"""ai-service: smart ride suggestions for ride-service. Internal only (never exposed by the proxy).

Run: ``uvicorn app.main:app --port 8085`` (from ai-service/). Settings come from the environment:
REDIS_HOST, REDIS_PORT, KAFKA_BOOTSTRAP_SERVERS, AI_KAFKA_ENABLED (true), MODELS_DIR, ROADS_FILE.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from prometheus_client import Counter, Histogram, make_asgi_app

from . import features, geo
from .engine import Engine, Live
from .model import MODELS_DIR, Predictor, feature_row
from .roads import RoadPoints
from .schemas import SuggestionRequest, SuggestionResponse

log = logging.getLogger("ai-service")
LATENCY = Histogram("ai_suggestion_latency_seconds", "Time to build one suggestion",
                    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.3, 0.5, 1))
SUGGESTIONS = Counter("ai_suggestions_total", "Suggestions made", ["live_features"])
# the feature store gets this long per suggestion; ride-service waits 300 ms for the whole answer
FEATURES_BUDGET_S = 0.12


def load_predictor(directory: Path) -> Predictor:
    if not (directory / "wait.txt").exists():
        from training import train

        log.warning("No trained models in %s: training them now", directory)
        train.main(["--out", str(directory)])
    return Predictor.load(directory)


async def live_features(store: features.FeatureStore, req: SuggestionRequest) -> Live:
    at = req.requestedAt
    cell = geo.encode(req.pickup.lat, req.pickup.lng)
    areas = await asyncio.gather(*(features.area(store, c, at) for c in geo.grid3x3(cell)))
    return Live(pickup=areas[0], neighbours={a.cell: a for a in areas[1:]},
                fresh=features.fresh(await store.last_event_at()))


def create_app(store: features.FeatureStore | None = None, kafka: str | None = None) -> FastAPI:
    """``store``/``kafka`` override the environment (tests pass a MemoryStore and no Kafka)."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.predictor = load_predictor(Path(os.environ.get("MODELS_DIR", MODELS_DIR)))
        app.state.engine = Engine(app.state.predictor, RoadPoints.load())
        # warm up: the first prediction loads LightGBM's code paths, too slow for ride-service's 300 ms
        app.state.predictor.predict([feature_row(12.0, 2, False, 4.0, 6.0, "ECONOMY", 8.0, 1.2)])
        redis = None
        if store is None:
            import redis.asyncio as aioredis

            # 127.0.0.1, not localhost: on Windows localhost tries IPv6 first and the local containers
            # listen on IPv4 only. A suggestion's own budget is FEATURES_BUDGET_S, whatever these allow.
            redis = aioredis.Redis(host=os.environ.get("REDIS_HOST", "127.0.0.1"),
                                   port=int(os.environ.get("REDIS_PORT", "6379")), decode_responses=True,
                                   socket_timeout=1.0, socket_connect_timeout=1.0)
            app.state.store = features.RedisStore(redis)
        else:
            app.state.store = store
        consumer = None
        bootstrap = kafka if store is not None else os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092")
        if bootstrap and os.environ.get("AI_KAFKA_ENABLED", "true").lower() == "true":
            from .consumer import run

            consumer = asyncio.create_task(run(app.state.store, bootstrap))
        log.info("Model %s loaded, %d road points", app.state.predictor.version, len(app.state.engine.roads))
        yield
        if consumer:
            consumer.cancel()
            await asyncio.gather(consumer, return_exceptions=True)
        if redis is not None:
            await redis.aclose()

    app = FastAPI(title="Rydex ai-service", version="1.0", lifespan=lifespan,
                  description="Smart ride suggestions: ride type, pickup time, pickup point and route.")
    app.mount("/metrics", make_asgi_app())

    @app.post("/suggestions", response_model=SuggestionResponse)
    async def suggestions(req: SuggestionRequest) -> SuggestionResponse:
        started = time.perf_counter()
        try:
            live = await asyncio.wait_for(live_features(app.state.store, req), FEATURES_BUDGET_S)
        except Exception as e:  # Redis slow or down: suggest from ride-service's counts and the daily curve
            log.debug("No live features: %s", e)
            live = Live()
        result = app.state.engine.suggest(req, live)
        SUGGESTIONS.labels("fresh" if live.fresh else "none").inc()
        LATENCY.observe(time.perf_counter() - started)
        return result

    @app.get("/model/info")
    async def model_info() -> dict:
        try:
            fresh = features.fresh(await asyncio.wait_for(app.state.store.last_event_at(), FEATURES_BUDGET_S))
        except Exception:
            fresh = False
        return {**app.state.predictor.info, "liveFeatures": fresh, "roadPoints": len(app.state.engine.roads)}

    @app.get("/health")
    async def health() -> dict:
        return {"status": "UP", "model": app.state.predictor.version}

    return app


logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
app = create_app()
