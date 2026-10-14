import type { Ride } from '../api/types';
import { clock } from '../lib/format';

const STEPS = [
  { label: 'Booked', at: (r: Ride) => r.requestedAt },
  { label: 'Driver assigned', at: (r: Ride) => r.assignedAt },
  { label: 'Driver arrived', at: (r: Ride) => r.arrivedAt },
  { label: 'Trip started', at: (r: Ride) => r.startedAt },
  { label: 'Arrived at drop', at: (r: Ride) => r.completedAt },
];

/** The ride's steps with their times; an ended ride stops at its last step. */
export function RideTimeline({ ride }: { ride: Ride }) {
  const ended = ride.status === 'CANCELLED' || ride.status === 'NO_DRIVER_FOUND';
  const reached = STEPS.filter((s) => s.at(ride)).length;
  const steps = ended ? STEPS.slice(0, Math.max(1, reached)) : STEPS;
  return (
    <ol className="space-y-0" aria-label="Ride progress">
      {steps.map((s, i) => {
        const at = s.at(ride);
        const current = !ended && !at && i === reached;
        return (
          <li key={s.label} className="flex gap-3">
            <span className="flex flex-col items-center" aria-hidden>
              <span
                className={`mt-1 size-3 rounded-full ${at ? 'bg-ink' : current ? 'border-2 border-ink bg-auto' : 'border-2 border-mist bg-paper'}`}
              />
              {i < steps.length - 1 && <span className={`w-0.5 flex-1 ${at ? 'bg-ink' : 'bg-mist'}`} />}
            </span>
            <span className={`flex flex-1 justify-between pb-3 text-sm ${at || current ? 'text-ink' : 'text-slate/70'}`}>
              <span className={current ? 'font-bold' : ''}>{s.label}</span>
              <span className="font-mono tabular text-slate">{clock(at)}</span>
            </span>
          </li>
        );
      })}
      {ended && (
        <li className="flex gap-3 text-sm font-bold text-signal">
          <span className="mt-1 size-3 rounded-full bg-signal" aria-hidden />
          {ride.status === 'CANCELLED' ? `Cancelled ${clock(ride.cancelledAt)}` : 'No driver found'}
        </li>
      )}
    </ol>
  );
}
