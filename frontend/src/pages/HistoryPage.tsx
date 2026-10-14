import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { ridesApi } from '../api/endpoints';
import { errorMessage } from '../api/http';
import type { Ride, RideStatus } from '../api/types';
import { useAuth } from '../auth/store';
import { PaymentBadge } from '../components/PaymentPanel';
import { ErrorNote, Spinner } from '../components/ui';
import { inr, rideTypeLabel, shortAddress, when } from '../lib/format';

const PAGE = 15;

const statusLook: Record<RideStatus, [string, string]> = {
  REQUESTED: ['Finding driver', 'bg-auto/30'],
  DRIVER_ASSIGNED: ['Driver coming', 'bg-auto/30'],
  DRIVER_ARRIVED: ['Driver arrived', 'bg-auto/30'],
  IN_PROGRESS: ['On trip', 'bg-sea-soft text-sea'],
  COMPLETED: ['Completed', 'bg-go-soft text-go'],
  CANCELLED: ['Cancelled', 'bg-signal-soft text-signal'],
  NO_DRIVER_FOUND: ['No driver', 'bg-chalk text-slate'],
};

/** Past and current rides, newest first: the rider's bookings, or the driver's trips. */
export function HistoryPage() {
  const role = useAuth((s) => s.session?.user.role);
  const [page, setPage] = useState(0);
  const rides = useQuery({ queryKey: ['rides', 'history', page], queryFn: () => ridesApi.list(page, PAGE) });
  const pages = rides.data ? Math.max(1, Math.ceil(rides.data.total / PAGE)) : 1;

  return (
    <main className="h-full overflow-y-auto bg-chalk px-4 pt-20 pb-10">
      <div className="mx-auto max-w-2xl">
        <h1 className="font-display-wide text-3xl font-black">{role === 'DRIVER' ? 'Your trips' : 'Your rides'}</h1>
        <div className="mt-6 space-y-2">
          {rides.isPending && <Spinner label="Loading rides…" />}
          <ErrorNote>{rides.error && errorMessage(rides.error)}</ErrorNote>
          {rides.data?.items.length === 0 && (
            <div className="rounded-xl bg-paper p-6 text-center">
              <p className="font-bold">No rides yet.</p>
              <Link to={role === 'DRIVER' ? '/drive' : '/ride'} className="mt-2 inline-block font-bold underline underline-offset-4">
                {role === 'DRIVER' ? 'Go online to get your first trip' : 'Book your first ride'}
              </Link>
            </div>
          )}
          {rides.data?.items.map((r) => (
            <RideRow key={r.id} ride={r} linkable={role === 'RIDER'} />
          ))}
        </div>
        {pages > 1 && (
          <nav className="mt-4 flex items-center justify-center gap-3" aria-label="Pages">
            <button className="grid size-10 place-items-center rounded-lg bg-paper disabled:opacity-40" disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="Newer rides">
              <ChevronLeft className="size-5" />
            </button>
            <span className="font-mono text-sm tabular">
              {page + 1} / {pages}
            </span>
            <button className="grid size-10 place-items-center rounded-lg bg-paper disabled:opacity-40" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} aria-label="Older rides">
              <ChevronRight className="size-5" />
            </button>
          </nav>
        )}
      </div>
    </main>
  );
}

function RideRow({ ride, linkable }: { ride: Ride; linkable: boolean }) {
  const [label, cls] = statusLook[ride.status];
  const fare = ride.status === 'COMPLETED' ? ride.finalFare : ride.status === 'CANCELLED' ? ride.cancellationFee : ride.estFare;
  const body = (
    <div className="flex items-center gap-3 rounded-xl bg-paper p-3 transition-shadow hover:shadow-[var(--shadow-float)]">
      <div className="min-w-0 flex-1">
        <p className="truncate font-bold">
          {shortAddress(ride.pickup.address)} → {shortAddress(ride.drop.address)}
        </p>
        <p className="text-sm text-slate">
          {when(ride.requestedAt)} · {rideTypeLabel[ride.rideType]}
        </p>
      </div>
      <div className="flex flex-col items-end gap-1">
        <span className="font-mono font-bold tabular">{fare ? inr(fare) : '—'}</span>
        <span className="flex gap-1">
          <span className={`rounded-full px-2 py-0.5 text-xs font-bold ${cls}`}>{label}</span>
          <PaymentBadge status={ride.paymentStatus} />
        </span>
      </div>
    </div>
  );
  return linkable ? (
    <Link to={`/ride/${ride.id}`} className="block rounded-xl">
      {body}
    </Link>
  ) : (
    body
  );
}
