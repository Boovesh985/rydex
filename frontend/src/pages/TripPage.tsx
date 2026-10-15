import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Star } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { driversApi, placesApi, ridesApi, trackingApi } from '../api/endpoints';
import { ApiError, errorMessage } from '../api/http';
import { ACTIVE_STATUSES, type LiveLocation, type Ride } from '../api/types';
import { useAuth } from '../auth/store';
import { PaymentBadge } from '../components/PaymentPanel';
import { RealisticButton, RealisticHud, TripProgress } from '../components/Realistic';
import { RideStatusChip } from '../components/RideStatusChip';
import { RideTimeline } from '../components/RideTimeline';
import { ErrorNote, Eyebrow, Meter, Plate, Sheet, Spinner } from '../components/ui';
import { distance, inr, minutes, rideTypeLabel, shortAddress, when } from '../lib/format';
import { PathIndex, pathLength, type LatLng } from '../lib/geo';
import { decodePolyline } from '../lib/polyline';
import { AnimatedCar, BaseMap, DropPin, FitTo, PickupPin, RouteLine } from '../map/MapView';
import { useRealistic } from '../map/useRealistic';
import { newest } from '../lib/geo';
import { useOnReconnect, useSubscription } from '../realtime/hooks';

const TRACKED = ['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'];

/**
 * One ride for its driver (from their trip history) and for admins (from the operations view):
 * the planned route, the path actually driven, and the car live while the ride is on.
 */
export function TripPage() {
  const { rideId = '' } = useParams();
  return <Trip key={rideId} rideId={rideId} />;
}

function Trip({ rideId }: { rideId: string }) {
  const queryClient = useQueryClient();
  const role = useAuth((s) => s.session?.user.role);
  const [live, setLive] = useState<LiveLocation | null>(null);
  const [refit, setRefit] = useState(0);

  const ride = useQuery({
    queryKey: ['ride', rideId],
    queryFn: () => ridesApi.get(rideId),
    refetchInterval: (q) => (q.state.data && ACTIVE_STATUSES.includes(q.state.data.status) ? 10_000 : false),
  });
  const r = ride.data;
  const tracked = !!r && TRACKED.includes(r.status);
  const rx = useRealistic(tracked);

  const initial = useQuery({ queryKey: ['location', rideId], queryFn: () => trackingApi.location(rideId), enabled: tracked, retry: false, refetchInterval: 10_000 });
  const location = tracked ? newest(live, initial.data) : null;
  useSubscription<LiveLocation>(tracked ? `/topic/rides/${rideId}/location` : null, setLive);
  useOnReconnect(() => {
    void queryClient.invalidateQueries({ queryKey: ['ride', rideId] });
    void queryClient.invalidateQueries({ queryKey: ['location', rideId] });
  });

  const driver = useQuery({ queryKey: ['driver-card', rideId], queryFn: () => driversApi.forRide(rideId), enabled: !!r?.driverId, staleTime: Infinity });
  const planned = useQuery({
    queryKey: ['route', rideId, 'trip'],
    queryFn: () => placesApi.route(r!.pickup, r!.drop),
    enabled: !!r,
    staleTime: Infinity,
  });
  const path = useQuery({
    queryKey: ['path', rideId],
    queryFn: () => trackingApi.path(rideId),
    enabled: !!r && (r.status === 'IN_PROGRESS' || r.status === 'COMPLETED'),
    refetchInterval: r?.status === 'IN_PROGRESS' ? 10_000 : false,
    retry: false,
  });

  const plannedPoints = useMemo<LatLng[] | null>(() => {
    if (!r || !planned.data) return null;
    return planned.data.polyline ? decodePolyline(planned.data.polyline) : [[r.pickup.lat, r.pickup.lng], [r.drop.lat, r.drop.lng]];
  }, [planned.data, r?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const trail = useMemo<LatLng[]>(() => (path.data ?? []).map((p) => [p.lat, p.lng]), [path.data]);
  const index = useMemo(() => (plannedPoints && plannedPoints.length > 1 ? new PathIndex(plannedPoints) : null), [plannedPoints]);
  const progress = index && location && r?.status === 'IN_PROGRESS' && index.length > 0 ? index.project([location.lat, location.lng]).along / index.length : null;

  const hasLocation = !!location;
  const fit = useMemo<LatLng[]>(() => {
    if (!r) return [];
    const pts: LatLng[] = [[r.pickup.lat, r.pickup.lng], [r.drop.lat, r.drop.lng]];
    if (location) pts.push([location.lat, location.lng]);
    return pts;
  }, [r?.status, hasLocation]); // eslint-disable-line react-hooks/exhaustive-deps

  const back = role === 'ADMIN' ? { to: '/admin', label: 'Operations' } : { to: '/history', label: 'Your trips' };

  const missing = ride.error instanceof ApiError && ride.error.status === 404;
  const meter = r ? tripMeter(r, location) : null;
  // one map for the whole page: it is not rebuilt when the ride arrives
  return (
    <>
      <BaseMap realistic={rx.active} night={rx.night}>
        {r && <PickupPin at={[r.pickup.lat, r.pickup.lng]} />}
        {r && <DropPin at={[r.drop.lat, r.drop.lng]} />}
        {plannedPoints && <RouteLine polyline={planned.data?.polyline} fallback={plannedPoints} progress={progress} />}
        {trail.length > 1 && <RouteLine polyline={null} fallback={trail} tone="trail" />}
        {location && (
          <AnimatedCar
            lat={location.lat}
            lng={location.lng}
            heading={location.heading}
            speed={location.speed}
            route={r?.status === 'IN_PROGRESS' ? plannedPoints : null}
            type={driver.data?.vehicleType ?? r?.rideType}
            follow={rx.following}
            onFollowLost={rx.stopFollowing}
            label={driver.data ? `${driver.data.name}'s car` : 'The car'}
          />
        )}
        <FitTo points={fit} refit={refit} />
      </BaseMap>
      {tracked && !rx.active && <RealisticButton onClick={rx.enter} />}
      {!r || !meter ? (
        <Sheet>
          {ride.isPending ? (
            <Spinner label="Loading the ride…" />
          ) : (
            <ErrorNote>{missing ? 'This ride does not exist, or it is not one of yours.' : errorMessage(ride.error)}</ErrorNote>
          )}
          <Link to={back.to} className="mt-3 inline-flex items-center gap-1 text-sm font-bold underline underline-offset-4">
            <ArrowLeft className="size-4" aria-hidden /> {back.label}
          </Link>
        </Sheet>
      ) : rx.active ? (
        <RealisticHud
          eyebrow={meter.label}
          value={meter.value}
          unit={meter.unit}
          sub={meter.sub}
          speedKmh={location?.speed != null ? location.speed * 3.6 : null}
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
          <div className="space-y-2">
            {driver.data && (
              <p className="flex items-center justify-between gap-2 text-sm font-bold">
                {driver.data.name} <Plate number={driver.data.vehicleNumber} />
              </p>
            )}
            {progress != null && <TripProgress progress={progress} from={shortAddress(r.pickup.address)} to={shortAddress(r.drop.address)} />}
          </div>
        </RealisticHud>
      ) : (
        <Sheet>
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-2">
              <Link to={back.to} className="inline-flex items-center gap-1 text-sm font-bold underline underline-offset-4">
                <ArrowLeft className="size-4" aria-hidden /> {back.label}
              </Link>
              <span className="flex gap-1">
                <RideStatusChip status={r.status} />
                <PaymentBadge status={r.paymentStatus} />
              </span>
            </div>
            <Meter label={meter.label} value={meter.value} unit={meter.unit} sub={meter.sub} tone={meter.tone} />
            {progress != null && <TripProgress progress={progress} from={shortAddress(r.pickup.address)} to={shortAddress(r.drop.address)} />}

            {driver.data && (
              <div className="flex items-start gap-3">
                <span className="grid size-11 shrink-0 place-items-center rounded-full bg-ink font-display-wide text-lg font-black text-auto" aria-hidden>
                  {driver.data.name.charAt(0)}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-1.5 font-bold">
                    {driver.data.name}
                    <span className="inline-flex items-center gap-0.5 text-sm font-normal text-slate">
                      <Star className="size-3.5 fill-auto text-auto-deep" aria-hidden />
                      {Number(driver.data.rating).toFixed(1)}
                    </span>
                  </p>
                  <p className="truncate text-sm text-slate">
                    {driver.data.vehicleModel} · {rideTypeLabel[driver.data.vehicleType]}
                  </p>
                  <div className="mt-1.5">
                    <Plate number={driver.data.vehicleNumber} />
                  </div>
                </div>
              </div>
            )}

            <div className="space-y-2 text-sm">
              <Eyebrow>
                {rideTypeLabel[r.rideType]} · booked {when(r.requestedAt)}
              </Eyebrow>
              <p className="flex items-start gap-3">
                <span className="mt-1 block size-3 shrink-0 rounded-full border-[3px] border-ink" aria-hidden />
                {r.pickup.address ?? 'Pinned pickup'}
              </p>
              <p className="flex items-start gap-3">
                <span className="mt-1 block size-3 shrink-0 rounded-[2px] bg-ink" aria-hidden />
                {r.drop.address ?? 'Pinned drop'}
              </p>
              <p className="text-slate">
                Planned {distance(r.estDistanceM)} · {minutes(r.estDurationS)}
                {trail.length > 1 && ` · driven ${distance(pathLength(trail))}`}
                {' · '}
                {inr(r.finalFare ?? r.estFare)}
              </p>
              {trail.length > 1 && (
                <p className="flex items-center gap-2 text-xs text-slate">
                  <span className="h-1 w-6 rounded-full bg-auto ring-1 ring-ink" aria-hidden /> Path driven
                  <span className="ml-2 h-1 w-6 rounded-full bg-sea" aria-hidden /> Planned route
                </p>
              )}
            </div>
            <RideTimeline ride={r} />
          </div>
        </Sheet>
      )}
    </>
  );
}

function tripMeter(r: Ride, location: LiveLocation | null): { label: string; value: string; unit?: string; sub?: string; tone?: 'auto' | 'go' } {
  switch (r.status) {
    case 'REQUESTED':
      return { label: 'Finding a driver', value: '···', sub: `${rideTypeLabel[r.rideType]} · ${inr(r.estFare)}` };
    case 'DRIVER_ASSIGNED':
      return location
        ? { label: 'To pickup', value: minutes(location.etaS).replace(' min', ''), unit: 'min', sub: `${distance(location.distanceM)} from ${shortAddress(r.pickup.address)}` }
        : { label: 'To pickup', value: '···', sub: 'Waiting for the car’s position' };
    case 'DRIVER_ARRIVED':
      return { label: 'At pickup', value: 'WAIT', tone: 'go', sub: `Waiting at ${shortAddress(r.pickup.address)}` };
    case 'IN_PROGRESS':
      return location && location.phase === 'TO_DROP'
        ? { label: 'On trip', value: minutes(location.etaS).replace(' min', ''), unit: 'min', sub: `${distance(location.distanceM)} to ${shortAddress(r.drop.address)}` }
        : { label: 'On trip', value: '···', sub: `To ${shortAddress(r.drop.address)}` };
    case 'COMPLETED':
      return { label: 'Completed', value: inr(r.finalFare ?? r.estFare), tone: 'go', sub: r.completedAt ? `Dropped ${when(r.completedAt)}` : undefined };
    case 'CANCELLED':
      return { label: 'Cancelled', value: (r.cancellationFee ?? 0) > 0 ? inr(r.cancellationFee) : '—', sub: r.cancelReason ?? `By ${r.cancelledBy?.toLowerCase() ?? 'the system'}` };
    case 'NO_DRIVER_FOUND':
      return { label: 'No driver found', value: '—', sub: 'Nobody accepted in time' };
  }
}
