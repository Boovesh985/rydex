"""Rydex driver simulator: N fake drivers that sign in, go online, cruise the city, accept offers
and drive their trips along OSRM routes, reporting GPS every 3 s.

    python simulate.py --drivers 50 --city-center 13.0827,80.2707

Uses the seeded accounts driver01..driver50@rydex.dev (password Rydex@123). See README.md.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import logging
import os
import random
import sys

import httpx

from rydex_sim.api import Api, AuthBudget, Endpoints, Session
from rydex_sim.driver import Settings, SimDriver, Stats
from rydex_sim.otp import DEV_SECRET

SEEDED = 50
# seeded driver NN drives ECONOMY for 1-30, COMFORT for 31-42, XL for 43-50
TYPES = {"ECONOMY": range(1, 31), "COMFORT": range(31, 43), "XL": range(43, 51)}

log = logging.getLogger("rydex.sim")


def seeded_order() -> list[int]:
    """Driver numbers interleaved across ride types, so any --drivers N gets every type."""
    keyed = []
    for numbers in TYPES.values():
        n = len(numbers)
        keyed += [((i + 0.5) / n, num) for i, num in enumerate(numbers)]
    return [num for _, num in sorted(keyed)]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--drivers", type=int, default=10, help=f"how many drivers (1-{SEEDED}, default 10)")
    p.add_argument("--city-center", default="13.0827,80.2707", help="lat,lng drivers spread around (Chennai Central)")
    p.add_argument("--radius-km", type=float, default=4.0, help="how far from the centre drivers roam (default 4)")
    p.add_argument("--accept-rate", type=float, default=0.85, help="chance a driver accepts an offer (default 0.85)")
    p.add_argument("--speedup", type=float, default=1.0, help="drive this many times faster than real time (default 1)")
    p.add_argument("--board-seconds", type=float, default=20.0,
                   help="how long a driver waits at the pickup for the rider's PIN (real time; default 20)")
    p.add_argument("--park", action="store_true", help="idle drivers stay put instead of cruising (fewer route lookups)")
    p.add_argument("--api", default=os.environ.get("RYDEX_API"),
                   help="one origin that routes /api/* by prefix, e.g. https://rydex.duckdns.org "
                        "(default: the local service ports 8081-8084)")
    p.add_argument("--ws", default=os.environ.get("RYDEX_WS"), help="WebSocket URL (default: <api>/ws or ws://localhost:8083/ws)")
    p.add_argument("--password", default=os.environ.get("RYDEX_DRIVER_PASSWORD", "Rydex@123"))
    p.add_argument("--otp-secret", default=os.environ.get("OTP_SECRET", DEV_SECRET),
                   help="ride-service's OTP_SECRET, so drivers can start trips without a rider reading out the PIN "
                        "(default: the local development secret; pass '' to wait for riders instead)")
    p.add_argument("--auth-per-minute", type=int, default=15,
                   help="sign-ins per minute for all drivers together (ride-service allows 20 per IP; default 15)")
    p.add_argument("--seed", type=int, default=None, help="random seed for repeatable runs")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)
    if not 1 <= args.drivers <= SEEDED:
        p.error(f"--drivers must be 1-{SEEDED} (the seeded accounts)")
    try:
        lat, lng = (float(x) for x in args.city_center.split(","))
    except ValueError:
        p.error("--city-center must look like 13.0827,80.2707")
    args.center = (lat, lng)
    if not 0 <= args.accept_rate <= 1:
        p.error("--accept-rate must be between 0 and 1")
    if args.speedup <= 0:
        p.error("--speedup must be positive")
    return args


async def run(args: argparse.Namespace) -> None:
    endpoints = Endpoints(args.api, args.ws)
    settings = Settings(center=args.center, radius_m=args.radius_km * 1000, accept_rate=args.accept_rate,
                        speedup=args.speedup, cruise=not args.park, board_s=args.board_seconds, otp_secret=args.otp_secret or None)
    stats = Stats()
    budget = AuthBudget(args.auth_per_minute)
    rng = random.Random(args.seed)
    limits = httpx.Limits(max_connections=max(20, args.drivers * 2))
    async with httpx.AsyncClient(timeout=15, limits=limits) as http:
        api = Api(endpoints, budget, http)
        drivers = [
            SimDriver(f"driver{n:02d}", Session(api, f"driver{n:02d}@rydex.dev", args.password), endpoints, settings,
                      stats, random.Random(rng.random()))
            for n in seeded_order()[: args.drivers]
        ]
        minutes = args.drivers / args.auth_per_minute
        log.info("starting %d drivers around %.4f,%.4f (sign-ins are paced: about %.1f min until all are online)",
                 args.drivers, *args.center, minutes)
        tasks = [asyncio.create_task(_supervise(d, stats)) for d in drivers]
        reporter = asyncio.create_task(_report(stats))
        try:
            await asyncio.gather(*tasks)
        finally:
            reporter.cancel()
            for t in tasks:
                t.cancel()
            log.info("going offline...")
            with contextlib.suppress(Exception):
                await asyncio.wait_for(asyncio.gather(*(d.go_offline() for d in drivers), return_exceptions=True), 15)
            log.info("final: %s", stats.line())


async def _supervise(driver: SimDriver, stats: Stats) -> None:
    """Restarts a driver whose loop failed (the backend restarting, a network blip)."""
    backoff = 2.0
    while True:
        try:
            await driver.run()
        except asyncio.CancelledError:
            raise
        except httpx.TransportError as e:
            stats.errors += 1
            log.warning("%s cannot reach the backend (%s): are the services running? Retrying in %.0f s",
                        driver.label, type(e).__name__, backoff)
        except Exception as e:
            stats.errors += 1
            log.warning("%s stopped (%s), restarting in %.0f s", driver.label, e, backoff)
        await asyncio.sleep(backoff)
        backoff = min(60.0, backoff * 2)


async def _report(stats: Stats) -> None:
    while True:
        await asyncio.sleep(60)
        log.info("%s", stats.line())


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    for noisy in ("httpx", "httpcore", "websockets"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    with contextlib.suppress(KeyboardInterrupt):
        asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
