import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Star } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { driversApi, placesApi, ridesApi, trackingApi } from '../../api/endpoints';
import { ApiError, errorMessage } from '../../api/http';
import { ACTIVE_STATUSES, type DriverCard, type LiveLocation, type Ride } from '../../api/types';
import { PaymentPanel } from '../../components/PaymentPanel';
import { RideTimeline } from '../../components/RideTimeline';
import { Button, ErrorNote, Eyebrow, Meter, PinTiles, Plate, Sheet, Spinner } from '../../components/ui';
import { distance, inr, minutes, rideTypeLabel, shortAddress } from '../../lib/format';
import type { LatLng } from '../../lib/geo';
import { AnimatedCar, BaseMap, DropPin, FitTo, PickupPin, RouteLine } from '../../map/MapView';
import { useOnReconnect, useSubscription } from '../../realtime/hooks';

const TRACKED = ['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'];

/** The rider's ride from booking to payment: live driver, ETA, PIN, cancel, pay. */
export function LiveRide() {
  const { rideId = '' } = useParams();
  const queryClient = useQueryClient();
  const [live, setLive] = useState<LiveLocation | null>(null);

  const ride = useQuery({
    queryKey: ['ride', rideId],
    queryFn: () => ridesApi.get(rideId),
    // pushes drive the screen; polling is the safety net for a dropped connection
    refetchInterval: (q) => (q.state.data && ACTIVE_STATUSES.includes(q.state.data.status) ? 15_000 : false),
  });
  const r = ride.data;
  const tracked = !!r && TRACKED.includes(r.status);

  const initial = useQuery({
    queryKey: ['location', rideId],
    queryFn: () => trackingApi.location(rideId),
    enabled: tracked,
    retry: false,
  });
  const location = live ?? initial.data ?? null;

  useSubscription<LiveLocation>(tracked ? `/topic/rides/${rideId}/location` : null, setLive);
  useOnReconnect(() => {
    void queryClient.invalidateQueries({ queryKey: ['ride', rideId] });
    void queryClient.invalidateQueries({ queryKey: ['location', rideId] });
  });

  const driver = useQuery({
    queryKey: ['driver-card', rideId],
    queryFn: () => driversApi.forRide(rideId),
    enabled: !!r?.driverId,
    staleTime: Infinity,
  });

  // the road ahead: driver -> pickup while they come, pickup -> drop otherwise
  const toPickup = r?.status === 'DRIVER_ASSIGNED' && location;
  const routeFrom = toPickup ? { lat: location.lat, lng: location.lng } : r?.pickup;
  const routeTo = toPickup ? r?.pickup : r?.drop;
  const route = useQuery({
    queryKey: ['route', rideId, toPickup ? 'to-pickup' : 'trip'],
    queryFn: () => placesApi.route(routeFrom!, routeTo!),
    enabled: !!routeFrom && !!routeTo && !!r && r.status !== 'COMPLETED' && r.status !== 'CANCELLED',
    staleTime: Infinity,
  });

  const hasLocation = !!location;
  const fit = useMemo<LatLng[]>(() => {
    if (!r) return [];
    const pts: LatLng[] = [[r.pickup.lat, r.pickup.lng]];
    if (r.status !== 'DRIVER_ASSIGNED' && r.status !== 'DRIVER_ARRIVED') pts.push([r.drop.lat, r.drop.lng]);
    if (location && tracked) pts.push([location.lat, location.lng]);
    return pts;
    // re-fit on phase changes, not on every location update
  }, [r?.status, hasLocation]); // eslint-disable-line react-hooks/exhaustive-deps

  if (ride.isPending) {
    return (
      <Sheet>
        <Spinner label="Loading your ride…" />
      </Sheet>
    );
  }
  if (ride.error || !r) {
    const missing = ride.error instanceof ApiError && ride.error.status === 404;
    return (
      <Sheet>
        <ErrorNote>{missing ? 'This ride does not exist or is not yours.' : errorMessage(ride.error)}</ErrorNote>
        <Link to="/ride" className="mt-3 inline-block font-bold underline">
          Book a ride
        </Link>
      </Sheet>
    );
  }

  return (
    <>
      <BaseMap>
        <PickupPin at={[r.pickup.lat, r.pickup.lng]} />
        {r.status !== 'DRIVER_ASSIGNED' && r.status !== 'DRIVER_ARRIVED' && <DropPin at={[r.drop.lat, r.drop.lng]} />}
        {route.data && routeFrom && routeTo && (
          <RouteLine polyline={route.data.polyline} fallback={[[routeFrom.lat, routeFrom.lng], [routeTo.lat, routeTo.lng]]} />
        )}
        {tracked && location && <AnimatedCar lat={location.lat} lng={location.lng} heading={location.heading} />}
        <FitTo points={fit} />
      </BaseMap>
      <Sheet>
        <RideSheet ride={r} location={tracked ? location : null} driver={driver.data ?? null} />
      </Sheet>
    </>
  );
}

function RideSheet({ ride, location, driver }: { ride: Ride; location: LiveLocation | null; driver: DriverCard | null }) {
  const navigate = useNavigate();
  const s = ride.status;
  return (
    <div className="space-y-4">
      <StatusMeter ride={ride} location={location} />

      {driver && (s === 'DRIVER_ASSIGNED' || s === 'DRIVER_ARRIVED' || s === 'IN_PROGRESS') && <DriverPanel driver={driver} />}

      {ride.otp && (s === 'DRIVER_ASSIGNED' || s === 'DRIVER_ARRIVED') && (
        <div className="flex items-center justify-between gap-3 rounded-xl bg-chalk p-3">
          <div>
            <p className="font-bold">Your ride PIN</p>
            <p className="text-sm text-slate">Tell it to your driver to start the trip.</p>
          </div>
          <PinTiles pin={ride.otp} />
        </div>
      )}

      <Trip ride={ride} />

      {(s === 'COMPLETED' || (s === 'CANCELLED' && (ride.cancellationFee ?? 0) > 0)) && <PaymentPanel rideId={ride.id} />}

      {ACTIVE_STATUSES.includes(s) && s !== 'IN_PROGRESS' && <CancelRide ride={ride} />}

      {(s === 'COMPLETED' || s === 'CANCELLED' || s === 'NO_DRIVER_FOUND') && (
        <Button
          block
          variant={s === 'NO_DRIVER_FOUND' ? 'primary' : 'dark'}
          onClick={() => navigate('/ride')}
        >
          {s === 'NO_DRIVER_FOUND' ? 'Try again' : 'Book another ride'}
        </Button>
      )}

      <RideTimeline ride={ride} />
    </div>
  );
}

function StatusMeter({ ride, location }: { ride: Ride; location: LiveLocation | null }) {
  switch (ride.status) {
    case 'REQUESTED':
      return (
        <div className="space-y-2">
          <Meter label="Finding your driver" value="···" sub={`Asking the nearest ${rideTypeLabel[ride.rideType]} drivers`} />
          <div className="h-1 overflow-hidden rounded-full bg-mist" aria-hidden>
            <div className="h-full w-1/3 animate-[rx-scan_1.4s_ease-in-out_infinite] rounded-full bg-auto motion-reduce:animate-none" />
          </div>
        </div>
      );
    case 'DRIVER_ASSIGNED':
      return location ? (
        <Meter label="Driver arriving" value={minutes(location.etaS).replace(' min', '')} unit="min" sub={`${distance(location.distanceM)} away`} />
      ) : (
        <Meter label="Driver on the way" value="···" sub="Waiting for their location" />
      );
    case 'DRIVER_ARRIVED':
      return <Meter label="Your driver is here" value="NOW" tone="go" sub={`At ${shortAddress(ride.pickup.address)}`} />;
    case 'IN_PROGRESS':
      return location && location.phase === 'TO_DROP' ? (
        <Meter label="On the way" value={minutes(location.etaS).replace(' min', '')} unit="min" sub={`${distance(location.distanceM)} to ${shortAddress(ride.drop.address)}`} />
      ) : (
        <Meter label="On the way" value="···" sub={`To ${shortAddress(ride.drop.address)}`} />
      );
    case 'COMPLETED':
      return <Meter label="You have arrived" value={inr(ride.finalFare ?? ride.estFare)} tone="go" sub="Trip fare" />;
    case 'CANCELLED':
      return (
        <div className="rounded-xl bg-signal-soft p-3">
          <p className="font-display-wide font-black text-signal">Ride cancelled</p>
          <p className="text-sm">
            {ride.cancelledBy === 'RIDER' ? 'You cancelled' : 'Cancelled'}
            {ride.cancelReason ? `: ${ride.cancelReason}` : '.'}
            {(ride.cancellationFee ?? 0) > 0 && ` A cancellation fee of ${inr(ride.cancellationFee)} applies.`}
          </p>
        </div>
      );
    case 'NO_DRIVER_FOUND':
      return (
        <div className="rounded-xl bg-chalk p-3">
          <p className="font-display-wide font-black">No drivers available</p>
          <p className="text-sm text-slate">Every nearby driver was busy. You were not charged. Try again in a minute.</p>
        </div>
      );
  }
}

function DriverPanel({ driver }: { driver: DriverCard }) {
  return (
    <div className="flex items-start gap-3">
      <span className="grid size-12 shrink-0 place-items-center rounded-full bg-ink font-display-wide text-lg font-black text-auto" aria-hidden>
        {driver.name.charAt(0)}
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-1.5 font-bold">
          {driver.name}
          <span className="inline-flex items-center gap-0.5 text-sm font-normal text-slate">
            <Star className="size-3.5 fill-auto text-auto-deep" aria-hidden />
            {Number(driver.rating).toFixed(1)}
          </span>
        </p>
        <p className="truncate text-sm text-slate">
          {driver.vehicleModel} · {rideTypeLabel[driver.vehicleType]}
        </p>
        <div className="mt-1.5">
          <Plate number={driver.vehicleNumber} />
        </div>
      </div>
    </div>
  );
}

function Trip({ ride }: { ride: Ride }) {
  return (
    <div className="space-y-2 text-sm">
      <Eyebrow>Trip</Eyebrow>
      <p className="flex items-start gap-3">
        <span className="mt-1 block size-3 shrink-0 rounded-full border-[3px] border-ink" aria-hidden />
        <span>
          <span className="sr-only">From </span>
          {ride.pickup.address ?? 'Pinned pickup'}
        </span>
      </p>
      <p className="flex items-start gap-3">
        <span className="mt-1 block size-3 shrink-0 rounded-[2px] bg-ink" aria-hidden />
        <span>
          <span className="sr-only">To </span>
          {ride.drop.address ?? 'Pinned drop'}
        </span>
      </p>
      <p className="text-slate">
        {rideTypeLabel[ride.rideType]} · {distance(ride.estDistanceM)} · {inr(ride.finalFare ?? ride.estFare)}
      </p>
    </div>
  );
}

function CancelRide({ ride }: { ride: Ride }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const cancel = useMutation({
    mutationFn: () => ridesApi.cancel(ride.id, 'Cancelled by rider'),
    onSuccess: (updated) => queryClient.setQueryData(['ride', ride.id], updated),
  });
  const costs = ride.status === 'DRIVER_ARRIVED';

  if (!confirming) {
    return (
      <Button variant="danger" block onClick={() => setConfirming(true)}>
        Cancel ride
      </Button>
    );
  }
  return (
    <div className="space-y-2 rounded-xl bg-signal-soft p-3">
      <p className="text-sm font-bold">
        {costs ? 'Your driver is already here, so a cancellation fee applies.' : 'Cancelling now is free.'}
      </p>
      <ErrorNote>{cancel.error && errorMessage(cancel.error)}</ErrorNote>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="quiet" onClick={() => setConfirming(false)}>
          Keep ride
        </Button>
        <Button variant="danger" busy={cancel.isPending} onClick={() => cancel.mutate()}>
          {costs ? 'Cancel and pay fee' : 'Yes, cancel'}
        </Button>
      </div>
    </div>
  );
}
