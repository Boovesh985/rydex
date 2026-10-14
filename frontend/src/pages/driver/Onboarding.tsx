import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { driversApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import { RIDE_TYPES, type RideType } from '../../api/types';
import { Button, ErrorNote, Field, Plate } from '../../components/ui';
import { rideTypeBlurb, rideTypeLabel } from '../../lib/format';

/** First visit of a new driver: register the car before going online. */
export function Onboarding() {
  const queryClient = useQueryClient();
  const [type, setType] = useState<RideType>('ECONOMY');
  const [number, setNumber] = useState('');
  const [model, setModel] = useState('');
  const save = useMutation({
    mutationFn: () => driversApi.saveProfile({ vehicleType: type, vehicleNumber: number.trim(), vehicleModel: model.trim() }),
    onSuccess: (profile) => queryClient.setQueryData(['driver', 'me'], profile),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <h1 className="font-display-wide text-2xl font-black">Add your car</h1>
        <p className="text-sm text-slate">Riders see these details when you pick them up.</p>
      </div>
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-bold">Ride type</legend>
        {RIDE_TYPES.map((t) => (
          <label
            key={t}
            className={`flex cursor-pointer items-center justify-between rounded-xl px-3 py-2.5 has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-sea ${type === t ? 'ring-[2.5px] ring-ink' : 'bg-chalk'}`}
          >
            <input type="radio" name="type" className="sr-only" checked={type === t} onChange={() => setType(t)} />
            <span className="font-display-wide font-black">{rideTypeLabel[t]}</span>
            <span className="text-sm text-slate">{rideTypeBlurb[t]}</span>
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
      <Field label="Make and model" placeholder="Maruti Suzuki Swift" required maxLength={60} value={model} onChange={(e) => setModel(e.target.value)} />
      <ErrorNote>{save.error && errorMessage(save.error)}</ErrorNote>
      <Button type="submit" block busy={save.isPending}>
        Save and continue
      </Button>
    </form>
  );
}
