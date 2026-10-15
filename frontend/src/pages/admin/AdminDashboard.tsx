import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronRight } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { adminApi, ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { Payment, Ride, RideType } from '../../api/types';
import { PaymentBadge } from '../../components/PaymentPanel';
import { RealisticBar, RealisticButton } from '../../components/Realistic';
import { RideStatusChip } from '../../components/RideStatusChip';
import { Button, ErrorNote, Eyebrow } from '../../components/ui';
import { config } from '../../config';
import { clock, inr, minutes, rideTypeLabel, shortAddress, when } from '../../lib/format';
import type { LatLng } from '../../lib/geo';
import { BaseMap, DemandHeat, Fleet, GridOverlay, MapMoves, type FleetCar, type GridCellShape } from '../../map/MapView';
import { useRealistic } from '../../map/useRealistic';

const TYPE_COLOR: Record<RideType, string> = { ECONOMY: '#ffc21a', COMFORT: '#f2f3ef', XL: '#2c313a' };

type Layer = 'drivers' | 'grid' | 'demand';
type Tab = 'overview' | 'rides' | 'payments';

/** Ops view: live drivers, the matching grid around the map centre, where rides are requested, KPIs, rides, payments. */
export function AdminDashboard() {
  const [center, setCenter] = useState<LatLng>([config.center.lat, config.center.lng]);
  const [layers, setLayers] = useState<Record<Layer, boolean>>({ drivers: true, grid: true, demand: true });
  const [tab, setTab] = useState<Tab>('overview');
  const rx = useRealistic(true);

  const drivers = useQuery({ queryKey: ['admin', 'drivers'], queryFn: adminApi.liveDrivers, refetchInterval: 3000 });
  const grid = useQuery({ queryKey: ['admin', 'grid', center[0].toFixed(3), center[1].toFixed(3)], queryFn: () => adminApi.grid(center[0], center[1]), refetchInterval: 10_000, enabled: layers.grid });
  const demand = useQuery({ queryKey: ['admin', 'demand'], queryFn: () => adminApi.demand(24), refetchInterval: 30_000, enabled: layers.demand });
  const stats = useQuery({ queryKey: ['admin', 'stats'], queryFn: () => adminApi.stats(24), refetchInterval: 10_000 });

  const live = useMemo(() => drivers.data ?? [], [drivers.data]);
  const online = live.filter((d) => d.status === 'AVAILABLE').length;
  const onTrip = live.filter((d) => d.status === 'ON_TRIP').length;
  const fleet = useMemo<FleetCar[]>(
    () =>
      live.map((d) => ({
        id: d.driverId,
        lat: d.lat,
        lng: d.lng,
        heading: d.heading,
        type: d.rideType,
        onTrip: d.status === 'ON_TRIP',
        label: `${d.rideType ? rideTypeLabel[d.rideType] : 'Driver'} · ${d.status === 'ON_TRIP' ? 'on a trip' : (d.status?.toLowerCase() ?? 'unknown')}`,
      })),
    [live],
  );
  const cells = useMemo<GridCellShape[]>(
    () =>
      (grid.data?.cells ?? []).map((c) => ({
        geohash: c.geohash,
        center: c.position === 'C',
        bounds: [c.minLat, c.minLng, c.maxLat, c.maxLng],
        label: `${c.position === 'C' ? `${c.geohash}\n` : ''}${c.availableDrivers} drv · ${c.recentRequests} req`,
      })),
    [grid.data],
  );

  return (
    <div className="flex h-full flex-col md:flex-row">
      <div className="relative h-[55dvh] shrink-0 md:h-full md:flex-1">
        <BaseMap zoom={13} realistic={rx.active} night={rx.night}>
          <MapMoves onMove={setCenter} />
          {layers.demand && demand.data && <DemandHeat cells={demand.data} />}
          {layers.grid && <GridOverlay cells={cells} />}
          {layers.drivers && <Fleet cars={fleet} />}
        </BaseMap>
        <div className="absolute top-20 left-3 z-[1000] flex gap-1 rounded-xl bg-paper p-1 shadow-[var(--shadow-float)] md:left-4">
          {(['drivers', 'grid', 'demand'] as Layer[]).map((l) => (
            <button
              key={l}
              aria-pressed={layers[l]}
              onClick={() => setLayers({ ...layers, [l]: !layers[l] })}
              className={`rounded-lg px-3 py-1.5 text-sm font-bold capitalize ${layers[l] ? 'bg-ink text-chalk' : 'text-slate hover:bg-chalk'}`}
            >
              {l === 'grid' ? 'Geohash grid' : l}
            </button>
          ))}
        </div>
        {rx.active ? <RealisticBar night={rx.night} onNight={rx.toggleNight} onExit={rx.exit} /> : <RealisticButton onClick={rx.enter} />}
      </div>

      <aside className="flex-1 space-y-5 overflow-y-auto bg-paper p-4 md:w-[400px] md:flex-none md:pt-20">
        <div>
          <h1 className="font-display-wide text-2xl font-black">Operations</h1>
          <p className="text-sm text-slate">Last 24 hours · live</p>
        </div>
        <div className="grid grid-cols-3 gap-1 rounded-xl bg-chalk p-1" role="tablist" aria-label="Operations views">
          {(
            [
              ['overview', 'Overview'],
              ['rides', 'Live rides'],
              ['payments', 'Payments'],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              role="tab"
              aria-selected={tab === value}
              onClick={() => setTab(value)}
              className={`rounded-lg py-2 text-sm font-bold ${tab === value ? 'bg-ink text-chalk' : 'text-slate hover:text-ink'}`}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === 'overview' && (
          <>
            <ErrorNote>{stats.error && errorMessage(stats.error)}</ErrorNote>
            <div className="grid grid-cols-2 gap-2">
              <Kpi label="Drivers online" value={`${online}`} note={`${onTrip} on a trip`} />
              <Kpi label="Rides in flight" value={stats.data ? `${Object.values(stats.data.inFlight).reduce((a, b) => a + b, 0)}` : '–'} note={stats.data ? `${stats.data.inFlight.REQUESTED ?? 0} searching` : ''} />
              <Kpi label="Requested" value={stats.data ? `${stats.data.requested}` : '–'} note={stats.data ? `${stats.data.completed} done · ${stats.data.cancelled} cancelled` : ''} />
              <Kpi label="Match rate" value={stats.data?.matchRate == null ? '–' : `${Math.round(stats.data.matchRate * 100)}%`} note={stats.data?.avgAssignS == null ? 'no searches yet' : `${stats.data.avgAssignS} s to assign`} />
              <Kpi label="Revenue" value={stats.data ? inr(stats.data.revenue) : '–'} note={stats.data?.avgFare == null ? '' : `${inr(stats.data.avgFare)} avg fare`} />
              <Kpi label="Pickup wait" value={stats.data?.avgPickupS == null ? '–' : stats.data.avgPickupS < 60 ? `${stats.data.avgPickupS} s` : minutes(stats.data.avgPickupS)} note="assigned → arrived" />
            </div>
            {grid.data && layers.grid && (
              <p className="text-sm text-slate">
                Map centre cell <span className="font-mono font-bold text-ink">{grid.data.center}</span>: {grid.data.availableDrivers} drivers available and {grid.data.recentRequests} requests in the 3×3 search area.
              </p>
            )}
            <div className="flex flex-wrap gap-3 text-xs text-slate" aria-label="Legend">
              {(Object.keys(TYPE_COLOR) as RideType[]).map((t) => (
                <span key={t} className="flex items-center gap-1">
                  <span className="size-3 rounded-sm border border-ink" style={{ background: TYPE_COLOR[t] }} /> {rideTypeLabel[t]}
                </span>
              ))}
              <span className="flex items-center gap-1">
                <span className="size-3 rounded-sm border border-ink bg-[#7d838c]" /> On a trip
              </span>
              <span className="flex items-center gap-1">
                <span className="size-3 rounded-full bg-gradient-to-r from-auto to-signal" /> Demand
              </span>
            </div>
          </>
        )}
        {tab === 'rides' && <LiveRides />}
        {tab === 'payments' && <Payments />}
      </aside>
    </div>
  );
}

function Kpi({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="rounded-xl bg-chalk p-3">
      <Eyebrow>{label}</Eyebrow>
      <p className="mt-1 font-mono text-2xl font-bold tabular">{value}</p>
      {note && <p className="truncate text-xs text-slate">{note}</p>}
    </div>
  );
}

/** Rides in flight (watch any of them live), then the latest ones. */
function LiveRides() {
  const active = useQuery({ queryKey: ['admin', 'rides', 'active'], queryFn: () => ridesApi.all(0, 50, true), refetchInterval: 5000 });
  const recent = useQuery({ queryKey: ['admin', 'rides', 'recent'], queryFn: () => ridesApi.all(0, 10, false), refetchInterval: 15_000 });
  const finished = (recent.data?.items ?? []).filter((r) => !['REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'].includes(r.status));
  return (
    <section className="space-y-4">
      <div className="space-y-2">
        <Eyebrow>In flight · {active.data?.total ?? '–'}</Eyebrow>
        <ErrorNote>{active.error && errorMessage(active.error)}</ErrorNote>
        {active.data?.items.length === 0 && <p className="text-sm text-slate">No rides in flight right now.</p>}
        {active.data?.items.map((r) => <RideRow key={r.id} ride={r} />)}
      </div>
      {finished.length > 0 && (
        <div className="space-y-2">
          <Eyebrow>Latest finished</Eyebrow>
          {finished.map((r) => (
            <RideRow key={r.id} ride={r} />
          ))}
        </div>
      )}
    </section>
  );
}

function RideRow({ ride }: { ride: Ride }) {
  return (
    <Link to={`/trips/${ride.id}`} className="flex items-center gap-3 rounded-xl bg-chalk p-3 text-sm transition-colors hover:bg-mist">
      <div className="min-w-0 flex-1">
        <p className="truncate font-bold">
          {shortAddress(ride.pickup.address)} → {shortAddress(ride.drop.address)}
        </p>
        <p className="flex items-center gap-1.5 text-xs text-slate">
          <RideStatusChip status={ride.status} />
          {rideTypeLabel[ride.rideType]} · {clock(ride.requestedAt)} · {inr(ride.finalFare ?? ride.estFare)}
        </p>
      </div>
      <ChevronRight className="size-4 shrink-0 text-slate" aria-hidden />
    </Link>
  );
}

function Payments() {
  const payments = useQuery({ queryKey: ['admin', 'payments'], queryFn: () => adminApi.payments(0, 10), refetchInterval: 15_000 });
  return (
    <section className="space-y-2">
      <Eyebrow>Latest payments</Eyebrow>
      <ErrorNote>{payments.error && errorMessage(payments.error)}</ErrorNote>
      {payments.data?.items.length === 0 && <p className="text-sm text-slate">No payments yet. They appear when rides complete.</p>}
      {payments.data?.items.map((p) => (
        <PaymentRow key={p.id} p={p} />
      ))}
    </section>
  );
}

function PaymentRow({ p }: { p: Payment }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const refund = useMutation({
    mutationFn: () => adminApi.refund(p.id, reason.trim()),
    onSuccess: () => {
      setOpen(false);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] });
    },
  });
  return (
    <div className="rounded-xl bg-chalk p-3 text-sm">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="font-bold">
            {p.kind === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Fare'} · <span className="font-mono">{inr(p.amount)}</span>
          </p>
          <p className="truncate text-xs text-slate">
            {when(p.createdAt)} · {p.provider === 'RAZORPAY' ? 'Razorpay' : 'Mock'}
            {p.refundedAmount > 0 && ` · ${inr(p.refundedAmount)} refunded`}
          </p>
          <Link to={`/trips/${p.rideId}`} className="text-xs font-bold underline underline-offset-4">
            View ride
          </Link>
        </div>
        <PaymentBadge status={p.status} />
      </div>
      {p.refundable > 0 && p.status === 'SUCCEEDED' && !open && (
        <button className="mt-2 text-xs font-bold underline underline-offset-4" onClick={() => setOpen(true)}>
          Refund {inr(p.refundable)}
        </button>
      )}
      {open && (
        <form
          className="mt-2 space-y-2"
          onSubmit={(e) => {
            e.preventDefault();
            refund.mutate();
          }}
        >
          <input
            aria-label="Refund reason"
            required
            maxLength={300}
            placeholder="Reason, e.g. driver no-show"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="h-10 w-full rounded-lg border border-mist bg-paper px-2"
          />
          <div className="flex gap-2">
            <Button type="button" variant="quiet" className="!min-h-10 flex-1 !px-3" onClick={() => setOpen(false)}>
              Keep
            </Button>
            <Button type="submit" variant="dark" className="!min-h-10 flex-1 !px-3" busy={refund.isPending}>
              Refund {inr(p.refundable)}
            </Button>
          </div>
        </form>
      )}
      <ErrorNote>{refund.error && errorMessage(refund.error)}</ErrorNote>
    </div>
  );
}
