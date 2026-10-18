import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowDownUp, Clock3, Users } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { aiApi, ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { Estimate, GeoPoint, Ride, RideOption, RideType, Suggestion } from '../../api/types';
import { PlaceSearch } from '../../components/PlaceSearch';
import { Button, ErrorNote, Eyebrow, Meter, Spinner } from '../../components/ui';
import { VehicleIcon } from '../../components/VehicleIcon';
import { distance, inr, minutes, rideTypeBlurb, rideTypeLabel, rideTypeSeats, vehicleNoun } from '../../lib/format';
import { Shortcuts } from './Shortcuts';
import { SuggestionCard } from './SuggestionCard';

const pickupDot = <span className="block size-3.5 rounded-full border-[3.5px] border-ink bg-paper" />;
const dropDot = <span className="block size-3.5 rounded-[3px] bg-ink" />;

export type Field = 'pickup' | 'drop';

interface Props {
  pickup: GeoPoint | null;
  drop: GeoPoint | null;
  onPickup: (p: GeoPoint) => void;
  onDrop: (p: GeoPoint) => void;
  onSwap: () => void;
  onFocusField: (f: Field) => void;
  onEstimate: (e: Estimate | null) => void;
  onBooked: (ride: Ride) => void;
  /** the ride type picked at first (Try again: the one that found no driver) */
  initialType?: RideType;
}

/** Where to, which car, what it costs, book. */
export function BookingPanel({ pickup, drop, onPickup, onDrop, onSwap, onFocusField, onEstimate, onBooked, initialType = 'ECONOMY' }: Props) {
  const [selected, setSelected] = useState<RideType>(initialType);
  // one key per booking attempt: a retry after a network error cannot book twice
  const idempotencyKey = useRef(crypto.randomUUID());

  const estimate = useQuery({
    queryKey: ['estimate', pickup?.lat, pickup?.lng, drop?.lat, drop?.lng],
    queryFn: () => ridesApi.estimate(pickup!, drop!),
    enabled: !!pickup && !!drop,
    staleTime: 60_000,
  });

  useEffect(() => {
    onEstimate(estimate.data ?? null);
  }, [estimate.data, onEstimate]);

  // the smart suggestion for this trip (a new drop is a new trip): used, dismissed, or still open
  const trip = drop ? `${drop.lat.toFixed(5)},${drop.lng.toFixed(5)}` : '';
  const [ai, setAi] = useState<{ trip: string; used?: Suggestion; dismissed?: boolean }>({ trip });
  const aiNow = ai.trip === trip ? ai : { trip };
  const suggestion = aiNow.used ?? (aiNow.dismissed ? null : (estimate.data?.suggestion ?? null));
  // booked with the suggestion: its ride type, after "Use suggestion"
  const bookedWith = aiNow.used && aiNow.used.recommendedRideType === selected ? aiNow.used.suggestionId : undefined;

  const use = (s: Suggestion) => {
    setSelected(s.recommendedRideType);
    if (s.alternativePickup) {
      const { lat, lng, walkMeters } = s.alternativePickup;
      onPickup({ lat, lng, address: `Suggested pickup · ${walkMeters} m walk` });
    }
    setAi({ trip, used: s });
  };
  const dismiss = (s: Suggestion) => {
    aiApi.feedback(s.suggestionId, false).catch(() => {});
    setAi({ trip, dismissed: true });
  };

  const book = useMutation({
    mutationFn: () => ridesApi.book(pickup!, drop!, selected, idempotencyKey.current, bookedWith),
    onSuccess: (ride) => {
      idempotencyKey.current = crypto.randomUUID();
      onBooked(ride);
    },
  });

  const options = estimate.data?.options ?? [];
  const chosen = options.find((o) => o.rideType === selected);
  const surge = estimate.data && estimate.data.surgeMultiplier > 1 ? estimate.data.surgeMultiplier : null;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-display-wide text-2xl font-black">Where to?</h1>
        <p className="text-sm text-slate">Search for a place, or tap the map to drop a pin.</p>
      </div>

      <div className="relative space-y-2">
        <PlaceSearch
          label="Pickup"
          marker={pickupDot}
          placeholder="Pickup point"
          value={pickup}
          near={pickup}
          onPick={onPickup}
          onFocus={() => onFocusField('pickup')}
        />
        <PlaceSearch
          label="Drop"
          marker={dropDot}
          placeholder="Where are you going?"
          value={drop}
          near={pickup}
          onPick={onDrop}
          onFocus={() => onFocusField('drop')}
        />
        <button
          type="button"
          onClick={onSwap}
          disabled={!pickup || !drop}
          className="absolute top-[34px] right-2 grid size-9 place-items-center rounded-full bg-paper shadow ring-1 ring-mist hover:ring-ink disabled:opacity-40"
          aria-label="Swap pickup and drop"
        >
          <ArrowDownUp className="size-4" />
        </button>
      </div>

      <Shortcuts pickup={pickup} drop={drop} onDrop={onDrop} />

      {estimate.isFetching && !estimate.data && <Spinner label="Pricing your trip…" />}
      <ErrorNote>{estimate.error && errorMessage(estimate.error)}</ErrorNote>

      {estimate.data && (
        <>
          <div className="flex items-center justify-between">
            <Eyebrow>Choose a ride</Eyebrow>
            <span className="text-sm text-slate">
              {distance(estimate.data.distanceM)} · {minutes(estimate.data.durationS)} drive
            </span>
          </div>
          {surge && (
            <p className="rounded-xl bg-auto/25 px-3 py-2 text-sm">
              <strong>Busy right now:</strong> fares are {surge.toFixed(2)}× the usual.
            </p>
          )}
          {suggestion && <SuggestionCard suggestion={suggestion} used={!!aiNow.used} onUse={() => use(suggestion)} onDismiss={() => dismiss(suggestion)} />}
          <div role="radiogroup" aria-label="Ride type" className="space-y-2">
            {options.map((o) => (
              <OptionCard key={o.rideType} option={o} selected={o.rideType === selected} onSelect={() => setSelected(o.rideType)} />
            ))}
          </div>

          {chosen && <Meter label="Fare, fixed upfront" value={inr(chosen.fare)} sub={`${rideTypeLabel[chosen.rideType]} · pay after the trip`} />}
          <ErrorNote>{book.error && errorMessage(book.error)}</ErrorNote>
          <Button block busy={book.isPending} onClick={() => book.mutate()} disabled={!chosen}>
            Book {chosen ? rideTypeLabel[chosen.rideType] : 'ride'}
          </Button>
        </>
      )}
    </div>
  );
}

function OptionCard({ option, selected, onSelect }: { option: RideOption; selected: boolean; onSelect: () => void }) {
  const seats = rideTypeSeats[option.rideType];
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={`flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors ${
        selected ? 'bg-paper ring-[2.5px] ring-ink' : 'bg-chalk ring-1 ring-transparent hover:ring-mist'
      }`}
    >
      <VehicleIcon type={option.rideType} className="h-8 w-14 shrink-0" />
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="font-display-wide text-base font-black">{rideTypeLabel[option.rideType]}</span>
          <span className="flex items-center gap-0.5 text-xs text-slate">
            <Users className="size-3.5" aria-hidden /> {seats}
          </span>
        </span>
        <span className="block truncate text-sm text-slate">{rideTypeBlurb[option.rideType]}</span>
      </span>
      <span className="text-right">
        <span className="block font-mono text-lg font-bold tabular">{inr(option.fare)}</span>
        <span className={`flex items-center justify-end gap-1 text-xs ${option.etaMinutes == null ? 'text-signal' : 'text-slate'}`}>
          <Clock3 className="size-3" aria-hidden />
          {option.etaMinutes == null ? `No ${vehicleNoun(option.rideType)}s nearby` : `${Math.max(1, option.etaMinutes)} min away`}
        </span>
        {option.upgradeTo && <span className="block text-xs font-bold text-go">May come as a {rideTypeLabel[option.upgradeTo]}</span>}
      </span>
    </button>
  );
}
