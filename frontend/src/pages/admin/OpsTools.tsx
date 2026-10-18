import { useQuery } from '@tanstack/react-query';
import { ChevronRight, Search, TriangleAlert } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { adminApi } from '../../api/endpoints';
import type { Ride } from '../../api/types';
import { VehicleIcon } from '../../components/VehicleIcon';
import { rideTypeLabel } from '../../lib/format';
import { alertsFor } from './alerts';

const SHOWN = 5;

/** What needs a look now: riders waiting too long, trips running long, failed payments. */
export function Alerts({ rides, onRideAlong }: { rides: Ride[]; onRideAlong: (driverId: string) => void }) {
  const payments = useQuery({ queryKey: ['admin', 'payments', 'alerts'], queryFn: () => adminApi.payments(0, 50), refetchInterval: 30_000 });
  // the clock moves on between data refreshes: "waiting for 6 min" must keep counting
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(timer);
  }, []);
  const alerts = alertsFor(rides, payments.data?.items ?? [], now);
  if (alerts.length === 0) return null;

  return (
    <section aria-label="Alerts" className="space-y-2 rounded-xl bg-signal-soft p-3">
      <p className="flex items-center gap-1.5 text-sm font-bold text-signal">
        <TriangleAlert className="size-4" aria-hidden /> {alerts.length} {alerts.length === 1 ? 'thing needs' : 'things need'} a look
      </p>
      {alerts.slice(0, SHOWN).map((a) => (
        <div key={a.key} className="flex items-center gap-2 rounded-lg bg-paper p-2 text-sm">
          <Link to={`/trips/${a.rideId}`} className="min-w-0 flex-1">
            <span className="block truncate font-bold">{a.title}</span>
            <span className="block truncate text-xs text-slate">{a.detail}</span>
          </Link>
          {a.driverId && (
            <button
              type="button"
              onClick={() => onRideAlong(a.driverId!)}
              className="shrink-0 rounded-full bg-ink px-3 py-1.5 text-xs font-bold text-chalk hover:bg-ink-2"
            >
              Ride along
            </button>
          )}
        </div>
      ))}
      {alerts.length > SHOWN && <p className="text-xs text-slate">and {alerts.length - SHOWN} more</p>}
    </section>
  );
}

/**
 * Find a vehicle by its driver's name or its plate, and ride along with it. `live`: the drivers on
 * the map now; the database's status alone can say "free" for a driver whose app went quiet.
 */
export function FindVehicle({ live, onRideAlong }: { live: Set<string>; onRideAlong: (driverId: string) => void }) {
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setTerm(q.trim()), 250);
    return () => clearTimeout(timer);
  }, [q]);
  const found = useQuery({
    queryKey: ['admin', 'driver-search', term],
    queryFn: ({ signal }) => adminApi.searchDrivers(term, signal),
    enabled: term.length >= 2,
    staleTime: 10_000,
  });

  return (
    <section aria-label="Find a vehicle" className="space-y-2">
      <label className="flex items-center gap-2 rounded-xl bg-chalk px-3 py-2.5 ring-1 ring-transparent focus-within:ring-ink">
        <Search className="size-4 shrink-0 text-slate" aria-hidden />
        <span className="sr-only">Find a vehicle by driver or plate</span>
        <input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Find a vehicle: driver or plate"
          className="w-full bg-transparent text-sm outline-none"
        />
      </label>
      {term.length >= 2 && found.data?.length === 0 && <p className="text-sm text-slate">No driver or plate matches “{term}”.</p>}
      {term.length >= 2 &&
        found.data?.map((d) => {
          const online = live.has(d.id);
          return (
            <div key={d.id} className="flex items-center gap-3 rounded-xl bg-chalk p-2.5 text-sm">
              <VehicleIcon type={d.vehicleType} className="h-7 w-12 shrink-0" />
              <span className="min-w-0 flex-1">
                <span className="block truncate font-bold">{d.name}</span>
                <span className="block truncate text-xs text-slate">
                  <span className="font-mono">{d.vehicleNumber}</span> · {rideTypeLabel[d.vehicleType]} ·{' '}
                  {!online ? 'not on the map' : d.status === 'ON_TRIP' ? 'on a trip' : 'free'}
                </span>
              </span>
              {online ? (
                <button
                  type="button"
                  onClick={() => onRideAlong(d.id)}
                  className="inline-flex shrink-0 items-center gap-0.5 rounded-full bg-ink px-3 py-1.5 text-xs font-bold text-chalk hover:bg-ink-2"
                >
                  Ride along <ChevronRight className="size-3.5" aria-hidden />
                </button>
              ) : (
                <span className="shrink-0 text-xs text-slate">Offline</span>
              )}
            </div>
          );
        })}
    </section>
  );
}
