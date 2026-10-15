"""Rydex simulator: fake drivers that sign in, go online, cruise the city, accept offers and drive
their trips along OSRM routes (reporting GPS every 3 s), and, in the living city (ADR 0016), fake
riders who book trips between places in Chennai, read out their PIN and pay.

    python simulate.py --city                    # the living city: 40 drivers, 30 riders
    python simulate.py --drivers 49 --riders 0   # drivers only

Uses the seeded NPC accounts driver02..driver50 and rider2..rider60@rydex.dev (password Rydex@123);
driver01 and rider1 are left for you to test with by hand. See README.md.
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
from rydex_sim.rider import PinBoard, RiderSettings, RiderStats, SimRider
from rydex_sim.traffic import ROADS_FILE, RoadIndex

SEEDED = 50
SEEDED_RIDERS = 60
# yours, for testing by hand: the simulator never signs in as these
RESERVED_DRIVER = 1
RESERVED_RIDER = 1
# --city: the living city, centred on T Nagar so it covers the old city, the beach and the IT corridor
CITY = {"drivers": 40, "riders": 30, "center": "13.0450,80.2450", "radius_km": 7.0}
# seeded driver NN drives ECONOMY for 1-30, COMFORT for 31-42, XL for 43-50
TYPES = {"ECONOMY": range(1, 31), "COMFORT": range(31, 43), "XL": range(43, 51)}

log = logging.getLogger("rydex.sim")


def seeded_order() -> list[int]:
    """Driver numbers interleaved across ride types, so any --drivers N gets every type."""
    keyed = []
    for numbers in TYPES.values():
        n = len(numbers)
        keyed += [((i + 0.5) / n, num) for i, num in enumerate(numbers)]
    return [num for _, num in sorted(keyed) if num != RESERVED_DRIVER]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--city", action="store_true",
                   help=f"the living city: {CITY['drivers']} drivers and {CITY['riders']} riders around {CITY['center']} "
                        "(other flags still apply on top)")
    p.add_argument("--drivers", type=int, default=None, help=f"how many drivers (0-{SEEDED - 1}, default 10)")
    p.add_argument("--riders", type=int, default=None,
                   help=f"how many NPC riders booking trips (0-{SEEDED_RIDERS - 1}, default 0)")
    p.add_argument("--city-center", default=None, help="lat,lng drivers spread around (default Chennai Central)")
    p.add_argument("--radius-km", type=float, default=None, help="how far from the centre drivers roam (default 4)")
    p.add_argument("--rider-idle", type=float, default=150.0,
                   help="mean seconds a rider stays put between trips (default 150; shortened by --speedup)")
    p.add_argument("--no-traffic", action="store_true", help="drive at free-flow speed (ignore the simulated traffic)")
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
    city = CITY if args.city else {"drivers": 10, "riders": 0, "center": "13.0827,80.2707", "radius_km": 4.0}
    args.drivers = city["drivers"] if args.drivers is None else args.drivers
    args.riders = city["riders"] if args.riders is None else args.riders
    args.city_center = args.city_center or city["center"]
    args.radius_km = args.radius_km or city["radius_km"]
    if not 0 <= args.drivers <= SEEDED - 1:
        p.error(f"--drivers must be 0-{SEEDED - 1} (the seeded NPC accounts; driver01 is yours)")
    if not 0 <= args.riders <= SEEDED_RIDERS - 1:
        p.error(f"--riders must be 0-{SEEDED_RIDERS - 1} (the seeded NPC accounts; rider1 is yours)")
    if args.drivers + args.riders == 0:
        p.error("nothing to simulate: give --drivers and/or --riders")
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
    roads = None if args.no_traffic else RoadIndex.load()
    if roads is None and not args.no_traffic:
        log.warning("no road data at %s: driving without traffic", ROADS_FILE)
    board = PinBoard()
    settings = Settings(center=args.center, radius_m=args.radius_km * 1000, accept_rate=args.accept_rate,
                        speedup=args.speedup, cruise=not args.park, board_s=args.board_seconds,
                        otp_secret=args.otp_secret or None, board=board, roads=roads, cruise_places=args.city)
    rider_settings = RiderSettings(speedup=args.speedup, idle_s=args.rider_idle, roads=roads or RoadIndex.load())
    stats = Stats()
    rider_stats = RiderStats()
    budget = AuthBudget(args.auth_per_minute)
    rng = random.Random(args.seed)
    limits = httpx.Limits(max_connections=max(20, args.drivers * 2 + args.riders))
    async with httpx.AsyncClient(timeout=15, limits=limits) as http:
        api = Api(endpoints, budget, http)
        drivers = [
            SimDriver(f"driver{n:02d}", Session(api, f"driver{n:02d}@rydex.dev", args.password), endpoints, settings,
                      stats, random.Random(rng.random()))
            for n in seeded_order()[: args.drivers]
        ]
        riders = [
            SimRider(f"rider{n}", Session(api, f"rider{n}@rydex.dev", args.password), board, rider_settings,
                     rider_stats, random.Random(rng.random()))
            for n in range(RESERVED_RIDER + 1, RESERVED_RIDER + 1 + args.riders)
        ]
        minutes = (args.drivers + args.riders) / args.auth_per_minute
        log.info("starting %d drivers and %d riders around %.4f,%.4f%s (sign-ins are paced: about %.1f min until all "
                 "are in)", args.drivers, args.riders, *args.center, "" if roads else ", no traffic", minutes)
        # drivers sign in first: riders booking into an empty city would find nobody
        tasks = [asyncio.create_task(_supervise(d, stats)) for d in drivers]
        tasks += [asyncio.create_task(_supervise(r, stats)) for r in riders]
        reporter = asyncio.create_task(_report(stats, rider_stats if riders else None))
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


async def _supervise(driver: SimDriver | SimRider, stats: Stats) -> None:
    """Restarts a driver or rider whose loop failed (the backend restarting, a network blip)."""
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


async def _report(stats: Stats, rider_stats: RiderStats | None) -> None:
    while True:
        await asyncio.sleep(60)
        log.info("%s", stats.line())
        if rider_stats:
            log.info("%s", rider_stats.line())


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
