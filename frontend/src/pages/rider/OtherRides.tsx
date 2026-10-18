import { useMutation, useQuery } from '@tanstack/react-query';
import { Clock3 } from 'lucide-react';
import { useRef } from 'react';
import { ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { Ride, RideOption } from '../../api/types';
import { Button, ErrorNote, Eyebrow, Spinner } from '../../components/ui';
import { VehicleIcon } from '../../components/VehicleIcon';
import { inr, rideTypeLabel, vehicleNoun } from '../../lib/format';

/**
 * After no driver was found: the same trip priced again, and the ride types with a driver near the
 * pickup right now (the one that failed too, if a driver has come free), quickest first, each one
 * tap to book.
 */
export function OtherRides({ ride: r, onBooked }: { ride: Ride; onBooked: (ride: Ride) => void }) {
  const estimate = useQuery({
    queryKey: ['estimate', r.pickup.lat, r.pickup.lng, r.drop.lat, r.drop.lng, 'retry'],
    queryFn: () => ridesApi.estimate(r.pickup, r.drop),
    // drivers keep moving: look again now and then while the rider decides
    refetchInterval: 20_000,
  });
  // one key per booking attempt: a retry after a network error cannot book twice
  const idempotencyKey = useRef(crypto.randomUUID());
  const book = useMutation({
    mutationFn: (option: RideOption) => ridesApi.book(r.pickup, r.drop, option.rideType, idempotencyKey.current),
    onSuccess: (booked) => {
      idempotencyKey.current = crypto.randomUUID();
      onBooked(booked);
    },
  });

  if (estimate.isPending) return <Spinner label="Looking for other rides nearby…" />;
  if (estimate.isError) return null;
  const free = estimate.data.options
    .filter((o) => o.etaMinutes != null)
    .sort((a, b) => a.etaMinutes! - b.etaMinutes! || a.fare - b.fare)
    .slice(0, 3);
  if (free.length === 0) {
    return <p className="text-sm text-slate">Nothing else is free near your pickup right now either.</p>;
  }

  return (
    <section aria-label="Rides nearby now" className="space-y-2">
      <Eyebrow>Free near your pickup now</Eyebrow>
      {free.map((o) => {
        const same = o.rideType === r.rideType;
        return (
          <div key={o.rideType} className="flex items-center gap-3 rounded-xl bg-chalk p-3">
            <VehicleIcon type={o.rideType} className="h-8 w-14 shrink-0" />
            <div className="min-w-0 flex-1">
              <p className="font-display-wide font-black">
                {rideTypeLabel[o.rideType]} <span className="font-mono text-base font-bold tabular">{inr(o.fare)}</span>
              </p>
              <p className="flex items-center gap-1 text-xs text-slate">
                <Clock3 className="size-3" aria-hidden />
                {Math.max(1, o.etaMinutes!)} min away{same && ` · a ${vehicleNoun(o.rideType)} came free`}
                {o.upgradeTo && ` · may come as a ${rideTypeLabel[o.upgradeTo]}`}
              </p>
            </div>
            <Button
              variant="dark"
              className="min-h-10 px-4"
              busy={book.isPending && book.variables?.rideType === o.rideType}
              disabled={book.isPending}
              onClick={() => book.mutate(o)}
            >
              Book {rideTypeLabel[o.rideType]}
            </Button>
          </div>
        );
      })}
      <ErrorNote>{book.error && errorMessage(book.error)}</ErrorNote>
    </section>
  );
}
