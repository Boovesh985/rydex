import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { driversApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import { RIDE_TYPES, type DriverProfile, type RideType } from '../../api/types';
import { Button, ErrorNote, Field, Plate } from '../../components/ui';
import { VehicleIcon } from '../../components/VehicleIcon';
import { rideTypeBlurb, rideTypeLabel, vehicleModelHint } from '../../lib/format';

/** First visit of a new driver: register the vehicle before going online. */
export function Onboarding() {
  return <VehicleForm />;
}

/**
 * Registers a vehicle, or (with `profile`) changes it: a bike taxi, an auto or a cab. The server
 * only lets a driver change the ride type while offline.
 */
export function VehicleForm({ profile, onDone }: { profile?: DriverProfile; onDone?: () => void }) {
  const queryClient = useQueryClient();
  const [type, setType] = useState<RideType>(profile?.vehicleType ?? 'ECONOMY');
  const [number, setNumber] = useState(profile?.vehicleNumber ?? '');
  const [model, setModel] = useState(profile?.vehicleModel ?? '');
  const save = useMutation({
    mutationFn: () => driversApi.saveProfile({ vehicleType: type, vehicleNumber: number.trim(), vehicleModel: model.trim() }),
    onSuccess: (saved) => {
      queryClient.setQueryData(['driver', 'me'], saved);
      onDone?.();
    },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <h1 className="font-display-wide text-2xl font-black">{profile ? 'Change your vehicle' : 'Add your vehicle'}</h1>
        <p className="text-sm text-slate">Drive a bike taxi, an auto or a cab. Riders see these details when you pick them up.</p>
      </div>
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-bold">Ride type</legend>
        {RIDE_TYPES.map((t) => (
          <label
            key={t}
            className={`flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-sea ${type === t ? 'ring-[2.5px] ring-ink' : 'bg-chalk'}`}
          >
            <input type="radio" name="type" className="sr-only" checked={type === t} onChange={() => setType(t)} />
            <VehicleIcon type={t} className="h-7 w-12 shrink-0" />
            <span className="font-display-wide font-black">{rideTypeLabel[t]}</span>
            <span className="ml-auto truncate text-right text-sm text-slate">{rideTypeBlurb[t]}</span>
          </label>
        ))}
      </fieldset>
      <Field
        label="Vehicle number"
        placeholder="TN 09 AB 1234"
        required
        maxLength={20}
        pattern="[A-Za-z0-9 \-]+"
        value={number}
        onChange={(e) => setNumber(e.target.value.toUpperCase())}
      />
      {number.trim() && (
        <div aria-hidden>
          <Plate number={number.trim()} />
        </div>
      )}
      <Field label="Make and model" placeholder={vehicleModelHint[type]} required maxLength={60} value={model} onChange={(e) => setModel(e.target.value)} />
      <ErrorNote>{save.error && errorMessage(save.error)}</ErrorNote>
      <div className="flex gap-2">
        {onDone && (
          <Button type="button" variant="quiet" onClick={onDone}>
            Cancel
          </Button>
        )}
        <Button type="submit" block busy={save.isPending}>
          {profile ? 'Save vehicle' : 'Save and continue'}
        </Button>
      </div>
    </form>
  );
}
