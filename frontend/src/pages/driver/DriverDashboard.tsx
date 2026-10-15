import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigation, Star } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { driversApi, placesApi, ridesApi } from '../../api/endpoints';
import { ApiError, errorMessage } from '../../api/http';
import type { DriverProfile, Offer, OfferMessage, Ride } from '../../api/types';
import { RealisticButton, RealisticHud, TripProgress } from '../../components/Realistic';
import { toast } from '../../components/toastStore';
import { Button, ErrorNote, Eyebrow, Meter, Plate, Sheet, Spinner } from '../../components/ui';
import { config } from '../../config';
import { distance, inr, rideTypeLabel, shortAddress } from '../../lib/format';
import { haversine, PathIndex, type LatLng } from '../../lib/geo';
import { decodePolyline } from '../../lib/polyline';
import { AnimatedCar, BaseMap, DropPin, FitTo, MapClicks, PickupPin, RouteLine } from '../../map/MapView';
import { useRealistic } from '../../map/useRealistic';
import { useOnReconnect, useSubscription } from '../../realtime/hooks';
import { realtime } from '../../realtime/stomp';
import { Onboarding } from './Onboarding';
import { OfferModal } from './OfferModal';
import { useGpsPosition, useLocationReporter, useSimulatedPosition, type Position } from './usePosition';

const SIM_KEY = 'rydex.simulate';
const readSim = () => {
  try {
    return config.simulator && localStorage.getItem(SIM_KEY) === 'on';
  } catch {
    return false;
  }
};

/** Re-anchors the offer's countdown on this device's clock (the server's may differ). */
function localOffer(o: Offer): Offer {
  const total = Date.parse(o.expiresAt) - Date.parse(o.offeredAt);
  const expires = Date.now() + o.secondsLeft * 1000;
  return { ...o, expiresAt: new Date(expires).toISOString(), offeredAt: new Date(expires - total).toISOString() };
}

export function DriverDashboard() {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['driver', 'me'], queryFn: driversApi.me });
  const profile = me.data;

  if (me.isPending) {
    return (
      <>
        <BaseMap />
        <Sheet>
          <Spinner label="Loading your profile…" />
        </Sheet>
      </>
    );
  }
  if (me.error instanceof ApiError && me.error.status === 404) {
    return (
      <>
        <BaseMap />
        <Sheet>
          <Onboarding />
        </Sheet>
      </>
    );
  }
  if (me.error || !profile) {
    return (
      <>
        <BaseMap />
        <Sheet>
          <ErrorNote>{errorMessage(me.error)}</ErrorNote>
          <Button className="mt-3" variant="quiet" onClick={() => void queryClient.invalidateQueries({ queryKey: ['driver', 'me'] })}>
            Try again
          </Button>
        </Sheet>
      </>
    );
  }
  return <Dashboard profile={profile} />;
}

function Dashboard({ profile }: { profile: DriverProfile }) {
  const queryClient = useQueryClient();
  const online = profile.status !== 'OFFLINE';
  const [simulate, setSimulate] = useState(readSim);
  const [speedKmh, setSpeedKmh] = useState(40);
  const [offer, setOffer] = useState<Offer | null>(null);
  const [offerBusy, setOfferBusy] = useState<'accept' | 'reject' | null>(null);
  const [offerError, setOfferError] = useState('');
  const [refit, setRefit] = useState(0);
  const rx = useRealistic(online);

  const rideId = profile.currentRideId;
  const ride = useQuery({ queryKey: ['ride', rideId], queryFn: () => ridesApi.get(rideId!), enabled: !!rideId });
  const r = ride.data && ['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'].includes(ride.data.status) ? ride.data : null;
  const target = r?.status === 'DRIVER_ASSIGNED' ? r.pickup : r?.status === 'IN_PROGRESS' ? r.drop : null;
  const leg = r ? `${r.id}:${r.status}` : null;

  // --- where the car is
  const last: LatLng | null = profile.lastLocation ? [profile.lastLocation.lat, profile.lastLocation.lng] : null;
  const [legStart, setLegStart] = useState<{ leg: string; from: LatLng } | null>(null);
  const gps = useGpsPosition(!simulate && online);

  const route = useQuery({
    queryKey: ['drive-route', legStart?.leg],
    queryFn: () => placesApi.route({ lat: legStart!.from[0], lng: legStart!.from[1] }, target!),
    enabled: !!legStart && !!target && legStart.leg === leg,
    staleTime: Infinity,
  });
  const routePoints = useMemo<LatLng[] | null>(() => {
    if (!legStart || !target || legStart.leg !== leg || !route.data) return null;
    return route.data.polyline ? decodePolyline(route.data.polyline) : [legStart.from, [target.lat, target.lng]];
  }, [route.data, legStart, target, leg]);
  const pathIndex = useMemo(() => (routePoints && routePoints.length > 1 ? new PathIndex(routePoints) : null), [routePoints]);

  const sim = useSimulatedPosition(last, simulate ? routePoints : null, speedKmh);
  const lastLat = last?.[0];
  const lastLng = last?.[1];
  const pos = useMemo<Position | null>(
    () =>
      simulate ? sim.pos : (gps.pos ?? (lastLat != null && lastLng != null ? { lat: lastLat, lng: lastLng, heading: null, speed: null } : null)),
    [simulate, sim.pos, gps.pos, lastLat, lastLng],
  );

  // a new leg (to the pickup, then to the drop) starts where the car is now
  if (leg && target && pos && legStart?.leg !== leg) setLegStart({ leg, from: [pos.lat, pos.lng] });

  useLocationReporter(pos, online);

  // --- offers: pushed over the WebSocket; read over REST on load, on reconnect and every 10 s as a fallback
  const syncOffer = () =>
    driversApi
      .currentOffer()
      .catch(() => undefined)
      .then((current) => setOffer(current ? localOffer(current) : null));
  useSubscription<OfferMessage>(online ? '/user/queue/offers' : null, (m) => {
    if (m.type === 'OFFER' && m.offer) {
      setOfferError('');
      setOffer(localOffer(m.offer));
    } else if (m.type === 'OFFER_CLOSED') {
      setOffer((cur) => (cur?.rideId === m.rideId ? null : cur));
      if (m.reason === 'EXPIRED') toast('Offer expired', 'It went to the next driver.', 'info');
      if (m.reason === 'CANCELLED') toast('Ride cancelled', 'The rider cancelled before you accepted.', 'bad');
    }
  });
  useOnReconnect(() => {
    void queryClient.invalidateQueries({ queryKey: ['driver', 'me'] });
    if (profile.status === 'AVAILABLE') void syncOffer();
  });
  useEffect(() => {
    if (profile.status !== 'AVAILABLE') return;
    void syncOffer();
    const timer = setInterval(() => !realtime.connected && void syncOffer(), 10_000);
    return () => clearInterval(timer);
  }, [profile.status]);

  const respond = async (accept: boolean) => {
    if (!offer) return;
    setOfferBusy(accept ? 'accept' : 'reject');
    setOfferError('');
    try {
      if (accept) await driversApi.accept(offer.rideId);
      else await driversApi.reject(offer.rideId);
      setOffer(null);
      await queryClient.invalidateQueries({ queryKey: ['driver', 'me'] });
    } catch (e) {
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) {
        setOffer(null);
        toast('Offer no longer open', e.message, 'info');
      } else {
        setOfferError(errorMessage(e));
      }
    } finally {
      setOfferBusy(null);
    }
  };

  const toggle = useMutation({
    mutationFn: () => driversApi.setStatus(online ? 'OFFLINE' : 'AVAILABLE', pos ?? undefined),
    onSuccess: (p) => queryClient.setQueryData(['driver', 'me'], p),
  });

  const setSim = (on: boolean) => {
    setSimulate(on);
    try {
      localStorage.setItem(SIM_KEY, on ? 'on' : 'off');
    } catch {
      // private mode: the switch just won't be remembered
    }
  };

  const hasPos = !!pos;
  const fit = useMemo<LatLng[]>(() => {
    const pts: LatLng[] = [];
    if (pos) pts.push([pos.lat, pos.lng]);
    if (target) pts.push([target.lat, target.lng]);
    return pts;
  }, [leg, hasPos]); // eslint-disable-line react-hooks/exhaustive-deps

  const progress = pathIndex && pos && r?.status !== 'DRIVER_ARRIVED' && pathIndex.length > 0 ? pathIndex.project([pos.lat, pos.lng]).along / pathIndex.length : null;
  const legEnd = r ? (r.status === 'IN_PROGRESS' ? r.drop : r.pickup) : null;
  const away = legEnd && pos ? haversine([pos.lat, pos.lng], [legEnd.lat, legEnd.lng]) : null;

  return (
    <>
      <BaseMap center={last ?? undefined} realistic={rx.active} night={rx.night}>
        {simulate && !r && <MapClicks onClick={sim.moveTo} />}
        {r && r.status !== 'IN_PROGRESS' && <PickupPin at={[r.pickup.lat, r.pickup.lng]} />}
        {r && r.status === 'IN_PROGRESS' && <DropPin at={[r.drop.lat, r.drop.lng]} />}
        {routePoints && <RouteLine polyline={route.data?.polyline} fallback={routePoints} progress={progress} />}
        {pos && (
          <AnimatedCar
            lat={pos.lat}
            lng={pos.lng}
            heading={pos.heading}
            speed={pos.speed}
            glideMs={simulate ? 1050 : 3100}
            route={routePoints}
            type={profile.vehicleType}
            follow={rx.following}
            onFollowLost={rx.stopFollowing}
            label="Your car"
          />
        )}
        <FitTo points={fit} refit={refit} />
      </BaseMap>
      {online && !rx.active && <RealisticButton onClick={rx.enter} />}
      {rx.active ? (
        <RealisticHud
          eyebrow={!r ? 'Online' : r.status === 'DRIVER_ASSIGNED' ? 'To pickup' : r.status === 'DRIVER_ARRIVED' ? 'At pickup' : 'To drop'}
          value={!r ? 'LIVE' : r.status === 'DRIVER_ARRIVED' ? 'WAIT' : away == null ? '···' : distance(away).replace(/ (m|km)$/, '')}
          unit={!r || r.status === 'DRIVER_ARRIVED' || away == null ? undefined : away < 1000 ? 'm' : 'km'}
          sub={r && legEnd ? `${shortAddress(legEnd.address)} · ${inr(r.estFare)}` : 'Waiting for ride requests'}
          speedKmh={pos?.speed != null ? pos.speed * 3.6 : 0}
          following={rx.following}
          onFollow={rx.toggleFollow}
          onOverview={() => {
            rx.stopFollowing();
            setRefit((n) => n + 1);
          }}
          night={rx.night}
          onNight={rx.toggleNight}
          onExit={rx.exit}
        >
          {r ? (
            <div className="space-y-3">
              {r.status === 'IN_PROGRESS' && progress != null && (
                <TripProgress progress={progress} from={shortAddress(r.pickup.address)} to={shortAddress(r.drop.address)} />
              )}
              <TripActions ride={r} compact />
            </div>
          ) : (
            <p className="text-sm">
              <strong>You're online.</strong> Ride requests near you pop up here.
            </p>
          )}
        </RealisticHud>
      ) : (
        <Sheet>
          <div className="space-y-4">
            <StatusBar profile={profile} online={online} busy={toggle.isPending} onToggle={() => toggle.mutate()} />
            <ErrorNote>{toggle.error && errorMessage(toggle.error)}</ErrorNote>
            {!simulate && online && gps.error && <ErrorNote>{gps.error}</ErrorNote>}

            {r ? (
              <TripPanel ride={r} pos={pos} away={away} progress={progress} />
            ) : (
              <>
                {online ? (
                  <div className="flex items-center gap-3 rounded-xl bg-chalk p-3">
                    <span className="relative grid size-3 place-items-center" aria-hidden>
                      <span className="rx-pulse absolute size-3 rounded-full bg-go" />
                      <span className="size-3 rounded-full bg-go" />
                    </span>
                    <p className="text-sm">
                      <strong>You're online.</strong> Ride requests near you will pop up here.
                    </p>
                  </div>
                ) : (
                  <p className="text-sm text-slate">Go online to start getting ride requests.</p>
                )}
                <Earnings />
              </>
            )}

            {config.simulator && (
              <SimulatorControls on={simulate} onChange={setSim} speed={speedKmh} onSpeed={setSpeedKmh} driving={!!routePoints} />
            )}
          </div>
        </Sheet>
      )}
      {offer && profile.status === 'AVAILABLE' && (
        <OfferModal offer={offer} busy={offerBusy} error={offerError} onAccept={() => respond(true)} onReject={() => respond(false)} />
      )}
    </>
  );
}

function StatusBar({ profile, online, busy, onToggle }: { profile: DriverProfile; online: boolean; busy: boolean; onToggle: () => void }) {
  return (
    <div className="flex items-start gap-3">
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 font-display-wide text-lg font-black">
          {profile.name}
          <span className="inline-flex items-center gap-0.5 font-sans text-sm font-normal text-slate">
            <Star className="size-3.5 fill-auto text-auto-deep" aria-hidden />
            {Number(profile.rating).toFixed(1)}
          </span>
        </p>
        <p className="text-sm text-slate">
          {profile.vehicleModel} · {rideTypeLabel[profile.vehicleType]}
        </p>
        <div className="mt-1.5">
          <Plate number={profile.vehicleNumber} />
        </div>
      </div>
      <button
        role="switch"
        aria-checked={online}
        aria-label={online ? 'Go offline' : 'Go online'}
        disabled={busy || profile.status === 'ON_TRIP'}
        onClick={onToggle}
        className={`relative h-12 w-36 shrink-0 rounded-full font-display-wide text-[0.8rem] font-black transition-colors disabled:opacity-60 ${online ? 'bg-go text-paper' : 'bg-ink text-chalk'}`}
      >
        <span className={`absolute top-1 size-10 rounded-full bg-paper shadow transition-[left] ${online ? 'left-[calc(100%-2.75rem)]' : 'left-1'}`} aria-hidden />
        <span className={`block ${online ? 'pr-10' : 'pl-10'}`}>{profile.status === 'ON_TRIP' ? 'On trip' : online ? 'Online' : 'Go online'}</span>
      </button>
    </div>
  );
}

function TripPanel({ ride, pos, away, progress }: { ride: Ride; pos: Position | null; away: number | null; progress: number | null }) {
  const target = ride.status === 'IN_PROGRESS' ? ride.drop : ride.pickup;
  const label = ride.status === 'DRIVER_ASSIGNED' ? 'To pickup' : ride.status === 'DRIVER_ARRIVED' ? 'At pickup' : 'To drop';

  return (
    <div className="space-y-4">
      <Meter
        label={label}
        value={ride.status === 'DRIVER_ARRIVED' ? 'WAIT' : away == null ? '···' : distance(away).replace(/ (m|km)$/, '')}
        unit={ride.status === 'DRIVER_ARRIVED' || away == null ? undefined : away < 1000 ? 'm' : 'km'}
        tone={ride.status === 'DRIVER_ARRIVED' ? 'go' : 'auto'}
        sub={`${shortAddress(target.address)} · ${inr(ride.estFare)}`}
      />
      {ride.status === 'IN_PROGRESS' && progress != null && (
        <TripProgress progress={progress} from={shortAddress(ride.pickup.address)} to={shortAddress(ride.drop.address)} />
      )}
      <div className="space-y-2 text-sm">
        <Eyebrow>{rideTypeLabel[ride.rideType]} trip</Eyebrow>
        <p className="flex items-start gap-3">
          <span className="mt-1 block size-3 shrink-0 rounded-full border-[3px] border-ink" aria-hidden />
          {ride.pickup.address ?? 'Pickup pin'}
        </p>
        <p className="flex items-start gap-3">
          <span className="mt-1 block size-3 shrink-0 rounded-[2px] bg-ink" aria-hidden />
          {ride.drop.address ?? 'Drop pin'}
        </p>
      </div>
      <a
        className="inline-flex items-center gap-1.5 text-sm font-bold text-sea underline underline-offset-4"
        href={`https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=${pos ? `${pos.lat}%2C${pos.lng}` : ''}%3B${target.lat}%2C${target.lng}`}
        target="_blank"
        rel="noreferrer"
      >
        <Navigation className="size-4" aria-hidden /> Turn-by-turn directions
      </a>
      <TripActions ride={ride} />
    </div>
  );
}

/** The trip's next step (arrived, start with the PIN, complete), and cancelling, which asks first. */
function TripActions({ ride, compact = false }: { ride: Ride; compact?: boolean }) {
  const queryClient = useQueryClient();
  const [otp, setOtp] = useState('');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const done = (updated: Ride) => {
    queryClient.setQueryData(['ride', ride.id], updated);
    void queryClient.invalidateQueries({ queryKey: ['driver', 'me'] });
    void queryClient.invalidateQueries({ queryKey: ['rides'] });
  };
  const arrived = useMutation({ mutationFn: () => ridesApi.arrived(ride.id), onSuccess: done });
  const start = useMutation({ mutationFn: () => ridesApi.start(ride.id, otp), onSuccess: done });
  // the "Trip completed" notification comes from the server
  const complete = useMutation({ mutationFn: () => ridesApi.complete(ride.id), onSuccess: done });
  const cancel = useMutation({ mutationFn: () => ridesApi.cancel(ride.id, 'Cancelled by driver'), onSuccess: done });
  const error = arrived.error ?? start.error ?? complete.error ?? cancel.error;
  const otpId = compact ? 'otp-hud' : 'otp';

  return (
    <div className={compact ? 'space-y-2' : 'space-y-4'}>
      <ErrorNote>{error && errorMessage(error)}</ErrorNote>

      {ride.status === 'DRIVER_ASSIGNED' && (
        <Button block busy={arrived.isPending} onClick={() => arrived.mutate()}>
          I've arrived at pickup
        </Button>
      )}
      {ride.status === 'DRIVER_ARRIVED' && (
        <form
          className="space-y-2"
          onSubmit={(e) => {
            e.preventDefault();
            start.mutate();
          }}
        >
          <label className="block text-sm font-bold" htmlFor={otpId}>
            Ask the rider for their 4-digit PIN
          </label>
          <div className="flex gap-2">
            <input
              id={otpId}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="\d{4}"
              maxLength={4}
              required
              value={otp}
              onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))}
              className="h-12 w-32 rounded-xl border border-mist bg-paper px-3 text-center font-mono text-2xl font-bold tracking-[0.4em] focus:border-ink focus:outline-none"
            />
            <Button type="submit" className="flex-1" busy={start.isPending} disabled={otp.length !== 4}>
              Start trip
            </Button>
          </div>
        </form>
      )}
      {ride.status === 'IN_PROGRESS' && (
        <Button block variant="dark" busy={complete.isPending} onClick={() => complete.mutate()}>
          Complete trip
        </Button>
      )}
      {ride.status !== 'IN_PROGRESS' &&
        (confirmCancel ? (
          <div className="space-y-2 rounded-xl bg-signal-soft p-3">
            <p className="text-sm font-bold">Cancel this ride? The rider will have to book again.</p>
            <div className="grid grid-cols-2 gap-2">
              <Button variant="quiet" onClick={() => setConfirmCancel(false)}>
                Keep ride
              </Button>
              <Button variant="danger" busy={cancel.isPending} onClick={() => cancel.mutate()}>
                Yes, cancel
              </Button>
            </div>
          </div>
        ) : (
          <button type="button" className="text-sm font-bold text-signal underline underline-offset-4" onClick={() => setConfirmCancel(true)}>
            Cancel this ride
          </button>
        ))}
    </div>
  );
}

/** Today's completed trips and their fares (this device's calendar day). */
function Earnings() {
  const rides = useQuery({ queryKey: ['rides', 'driver-today'], queryFn: () => ridesApi.list(0, 100) });
  const today = new Date().toDateString();
  const done = (rides.data?.items ?? []).filter((r) => r.status === 'COMPLETED' && r.completedAt && new Date(r.completedAt).toDateString() === today);
  const total = done.reduce((sum, r) => sum + Number(r.finalFare ?? r.estFare), 0);
  return (
    <Meter label="Earned today" value={inr(total)} tone="go" sub={rides.isPending ? 'Counting…' : `${done.length} ${done.length === 1 ? 'trip' : 'trips'}`} />
  );
}

function SimulatorControls({ on, onChange, speed, onSpeed, driving }: { on: boolean; onChange: (v: boolean) => void; speed: number; onSpeed: (v: number) => void; driving: boolean }) {
  return (
    <div className="space-y-2 rounded-xl border border-dashed border-mist p-3">
      <label className="flex items-center justify-between gap-3">
        <span>
          <span className="block text-sm font-bold">Simulate driving</span>
          <span className="block text-xs text-slate">Demo mode: the car drives the route by itself.</span>
        </span>
        <input type="checkbox" role="switch" aria-label="Simulate driving" className="size-5 accent-[#16181d]" checked={on} onChange={(e) => onChange(e.target.checked)} />
      </label>
      {on && (
        <>
          <label className="flex items-center justify-between text-sm">
            Speed
            <select className="rounded-lg bg-chalk px-2 py-1" value={speed} onChange={(e) => onSpeed(Number(e.target.value))}>
              <option value={40}>40 km/h (real time)</option>
              <option value={120}>3× faster</option>
              <option value={400}>10× faster</option>
            </select>
          </label>
          {!driving && <p className="text-xs text-slate">Tap the map to put your car somewhere else.</p>}
        </>
      )}
    </div>
  );
}
