import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Star, X } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { adminApi, trackingApi } from '../../api/endpoints';
import type { LiveLocation, Ride } from '../../api/types';
import { PaymentBadge } from '../../components/PaymentPanel';
import { TripProgress } from '../../components/Realistic';
import { RideStatusChip } from '../../components/RideStatusChip';
import { RideTimeline } from '../../components/RideTimeline';
import { Eyebrow, Meter, Plate, Spinner } from '../../components/ui';
import { VehicleIcon } from '../../components/VehicleIcon';
import { distance, inr, minutes, rideTypeLabel, shortAddress, when } from '../../lib/format';
import { newest } from '../../lib/geo';
import type { CarDuty } from '../../map/MapView';
import { useOnReconnect, useSubscription } from '../../realtime/hooks';
import { tripMeter } from '../tripMeter';

const DOING: Record<CarDuty, string> = { free: 'Free, cruising for a request', pickup: 'Heading to a pickup', trip: 'Carrying a rider' };

interface Props {
  driverId: string;
  duty: CarDuty;
  /** km/h, live */
  speedKmh: number | null;
  /** the ride this driver is on, if any */
  ride: Ride | null;
  onClose: () => void;
}

/**
 * The operations map's ride-along (the camera chases this vehicle in realistic mode): who is
 * driving what, and the trip they are on, live.
 */
export function RideAlong({ driverId, duty, speedKmh, ride, onClose }: Props) {
  const driver = useQuery({ queryKey: ['admin', 'driver', driverId], queryFn: () => adminApi.driver(driverId), refetchInterval: 15_000 });
  const d = driver.data;
  return (
    <section aria-label="Ride along" className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div>
          <Eyebrow>Riding along</Eyebrow>
          <p className="text-sm text-slate">
            {DOING[duty]}
            {speedKmh != null && ` · ${Math.round(speedKmh)} km/h`}
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Stop riding along"
          title="Stop riding along (Esc)"
          className="grid size-10 shrink-0 place-items-center rounded-full bg-ink text-chalk shadow-[var(--shadow-float)] hover:bg-ink/85"
        >
          <X className="size-5" />
        </button>
      </div>

      {d ? (
        <div className="flex items-start gap-3">
          <span className="grid size-11 shrink-0 place-items-center rounded-full bg-ink font-display-wide text-lg font-black text-auto" aria-hidden>
            {d.name.charAt(0)}
          </span>
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5 font-bold">
              {d.name}
              <span className="inline-flex items-center gap-0.5 text-sm font-normal text-slate">
                <Star className="size-3.5 fill-auto text-auto-deep" aria-hidden />
                {Number(d.rating).toFixed(1)}
              </span>
            </p>
            <p className="truncate text-sm text-slate">
              {d.vehicleModel} · {rideTypeLabel[d.vehicleType]}
            </p>
            <div className="mt-1.5">
              <Plate number={d.vehicleNumber} />
            </div>
          </div>
          <VehicleIcon type={d.vehicleType} className="h-9 w-16 shrink-0" />
        </div>
      ) : (
        <Spinner label="Loading the driver…" />
      )}

      {ride ? <Trip key={ride.id} ride={ride} /> : <p className="rounded-xl bg-chalk p-3 text-sm">No rider yet. Ride requests within reach go to this driver through matching.</p>}
    </section>
  );
}

function Trip({ ride: r }: { ride: Ride }) {
  const queryClient = useQueryClient();
  const [live, setLive] = useState<LiveLocation | null>(null);
  const polled = useQuery({ queryKey: ['location', r.id], queryFn: () => trackingApi.location(r.id), retry: false, refetchInterval: 10_000 });
  useSubscription<LiveLocation>(`/topic/rides/${r.id}/location`, setLive);
  useOnReconnect(() => void queryClient.invalidateQueries({ queryKey: ['location', r.id] }));
  const location = newest(live, polled.data);
  const meter = tripMeter(r, location);
  // remaining road distance against the planned trip: good enough for a glance
  const progress = r.status === 'IN_PROGRESS' && location?.phase === 'TO_DROP' ? 1 - location.distanceM / Math.max(location.distanceM, r.estDistanceM, 1) : null;

  return (
    <div className="space-y-4">
      <Meter label={meter.label} value={meter.value} unit={meter.unit} sub={meter.sub} tone={meter.tone} />
      {progress != null && <TripProgress progress={progress} from={shortAddress(r.pickup.address)} to={shortAddress(r.drop.address)} />}
      <div className="space-y-2 text-sm">
        <div className="flex items-center justify-between gap-2">
          <Eyebrow>
            {rideTypeLabel[r.rideType]} · {r.simulated ? 'NPC rider' : 'Rider'} · booked {when(r.requestedAt)}
          </Eyebrow>
          <span className="flex shrink-0 gap-1">
            <RideStatusChip status={r.status} />
            <PaymentBadge status={r.paymentStatus} />
          </span>
        </div>
        <p className="flex items-start gap-3">
          <span className="mt-1 block size-3 shrink-0 rounded-full border-[3px] border-ink" aria-hidden />
          {r.pickup.address ?? 'Pinned pickup'}
        </p>
        <p className="flex items-start gap-3">
          <span className="mt-1 block size-3 shrink-0 rounded-[2px] bg-ink" aria-hidden />
          {r.drop.address ?? 'Pinned drop'}
        </p>
        <p className="text-slate">
          Planned {distance(r.estDistanceM)} · {minutes(r.estDurationS)} · {inr(r.finalFare ?? r.estFare)}
        </p>
      </div>
      <RideTimeline ride={r} />
      <Link to={`/trips/${r.id}`} className="inline-flex items-center gap-1 text-sm font-bold underline underline-offset-4">
        Open the trip page <ArrowRight className="size-4" aria-hidden />
      </Link>
    </div>
  );
}
