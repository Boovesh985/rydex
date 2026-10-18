import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ChevronRight } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { adminApi, ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import { RIDE_TYPES, type Page, type Payment, type Ride } from '../../api/types';
import { PaymentBadge } from '../../components/PaymentPanel';
import { RealisticBar, RealisticButton, SpeedDial } from '../../components/Realistic';
import { RideStatusChip } from '../../components/RideStatusChip';
import { Button, ErrorNote, Eyebrow } from '../../components/ui';
import { clock, inr, minutes, rideTypeLabel, shortAddress, when } from '../../lib/format';
import type { LatLng } from '../../lib/geo';
import { VEHICLE_PAINT } from '../../lib/vehicles';
import { AnimatedCar, BaseMap, DemandHeat, DispatchLines, Fleet, GridOverlay, MapMoves, RememberView, RiderMarkers, type GridCellShape } from '../../map/MapView';
import { TRAFFIC_COLORS } from '../../map/traffic';
import { useRealistic } from '../../map/useRealistic';
import { cityView } from './city';
import { RideAlong } from './RideAlong';

/** a driver's ride while it is theirs: on the way, waiting at the pickup, or under way */
const RIDING = ['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'];

/** the living city's centre (ADR 0016): T Nagar, so the old city, the beach and the IT corridor are in view */
const CITY: LatLng = [13.045, 80.245];

type Layer = 'drivers' | 'riders' | 'trips' | 'grid' | 'demand';
type Tab = 'overview' | 'rides' | 'payments';
const LAYER_LABEL: Record<Layer, string> = { drivers: 'Drivers', riders: 'Riders', trips: 'Trips', grid: 'Geohash grid', demand: 'Demand' };

/**
 * Ops view of the living city: every car (free, heading to a pickup, carrying a rider), riders
 * waiting at their pickups, who is heading where, traffic, the matching grid, demand, KPIs, rides
 * and payments; in 2D or in realistic 3D.
 */
export function AdminDashboard() {
  const navigate = useNavigate();
  const [center, setCenter] = useState<LatLng>(CITY);
  const [layers, setLayers] = useState<Record<Layer, boolean>>({ drivers: true, riders: true, trips: true, grid: false, demand: false });
  const [tab, setTab] = useState<Tab>('overview');
  const rx = useRealistic(true);

  const drivers = useQuery({ queryKey: ['admin', 'drivers'], queryFn: adminApi.liveDrivers, refetchInterval: 3000 });
  const inFlight = useQuery({ queryKey: ['admin', 'rides', 'active'], queryFn: () => ridesApi.all(0, 100, true), refetchInterval: 4000 });
  const grid = useQuery({ queryKey: ['admin', 'grid', center[0].toFixed(3), center[1].toFixed(3)], queryFn: () => adminApi.grid(center[0], center[1]), refetchInterval: 10_000, enabled: layers.grid });
  const demand = useQuery({ queryKey: ['admin', 'demand'], queryFn: () => adminApi.demand(24), refetchInterval: 30_000, enabled: layers.demand });
  const stats = useQuery({ queryKey: ['admin', 'stats'], queryFn: () => adminApi.stats(24), refetchInterval: 10_000 });

  const city = useMemo(() => cityView(drivers.data ?? [], inFlight.data?.items ?? []), [drivers.data, inFlight.data]);

  // ride along with one vehicle: click it on the map; in realistic mode the chase camera rides behind it
  const [picked, setRiding] = useState<string | null>(null);
  // a driver who went offline leaves nothing to follow
  const online = !drivers.data || drivers.data.some((d) => d.driverId === picked && (d.status === 'AVAILABLE' || d.status === 'ON_TRIP'));
  // and let go of them, so they do not pull the camera back when they come online again
  if (picked && !online) setRiding(null);
  const riding = picked && online ? picked : null;
  const ridingCar = riding ? (city.fleet.find((c) => c.id === riding) ?? null) : null;
  const ridingRide = riding ? (inFlight.data?.items.find((r) => r.driverId === riding && RIDING.includes(r.status)) ?? null) : null;
  useEffect(() => {
    if (!riding) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setRiding(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [riding]);
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
        <BaseMap center={CITY} zoom={12.6} realistic={rx.active} night={rx.night}>
          <MapMoves onMove={setCenter} />
          {layers.demand && demand.data && <DemandHeat cells={demand.data} />}
          {layers.grid && <GridOverlay cells={cells} />}
          {layers.trips && <DispatchLines lines={city.lines} />}
          {layers.drivers && <Fleet cars={city.fleet} hide={riding} onSelect={setRiding} />}
          {ridingCar && (
            <AnimatedCar
              // a new vehicle starts where it is, not gliding over from the last one
              key={ridingCar.id}
              lat={ridingCar.lat}
              lng={ridingCar.lng}
              heading={ridingCar.heading}
              speed={ridingCar.speed ?? null}
              type={ridingCar.type}
              carrying={ridingCar.duty === 'trip'}
              follow={rx.active}
              label={ridingCar.label}
            />
          )}
          <RememberView holding={!!ridingCar} />
          {layers.riders && <RiderMarkers riders={city.riders} onSelect={(id) => navigate(`/trips/${id}`)} />}
        </BaseMap>
        <div className="absolute top-20 left-3 z-[1000] flex max-w-[calc(100%-1.5rem)] flex-wrap gap-1 rounded-xl bg-paper p-1 shadow-[var(--shadow-float)] md:left-4">
          {(Object.keys(LAYER_LABEL) as Layer[]).map((l) => (
            <button
              key={l}
              aria-pressed={layers[l]}
              onClick={() => setLayers({ ...layers, [l]: !layers[l] })}
              className={`rounded-lg px-3 py-1.5 text-sm font-bold ${layers[l] ? 'bg-ink text-chalk' : 'text-slate hover:bg-chalk'}`}
            >
              {LAYER_LABEL[l]}
            </button>
          ))}
        </div>
        {rx.active ? (
          <RealisticBar night={rx.night} onNight={rx.toggleNight} onExit={rx.exit} following={!!ridingCar} />
        ) : (
          <RealisticButton onClick={rx.enter} />
        )}
        {rx.active && ridingCar && (
          <div className="absolute bottom-[max(0.75rem,env(safe-area-inset-bottom))] left-3 z-[1100] md:left-4">
            <SpeedDial kmh={(ridingCar.speed ?? 0) * 3.6} />
          </div>
        )}
        {rx.active && !ridingCar && layers.drivers && (
          <p className="pointer-events-none absolute bottom-12 left-1/2 z-[1100] -translate-x-1/2 rounded-full bg-ink/85 px-3 py-1.5 text-sm font-bold whitespace-nowrap text-chalk shadow-[var(--shadow-float)]">
            Click any vehicle to ride along
          </p>
        )}
      </div>

      <aside className="flex-1 space-y-5 overflow-y-auto bg-paper p-4 md:w-[400px] md:flex-none md:pt-20">
        {riding ? (
          <RideAlong
            key={riding}
            driverId={riding}
            duty={ridingCar?.duty ?? 'free'}
            speedKmh={ridingCar?.speed != null ? ridingCar.speed * 3.6 : null}
            ride={ridingRide}
            onClose={() => setRiding(null)}
          />
        ) : (
          <>
            <div>
              <h1 className="font-display-wide text-2xl font-black">Operations</h1>
              <p className="text-sm text-slate">The living city · last 24 hours · live</p>
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
                <ErrorNote>{(stats.error || drivers.error) && errorMessage(stats.error ?? drivers.error)}</ErrorNote>
                <section aria-label="Right now" className="grid grid-cols-2 gap-2">
                  <Kpi label="Free drivers" value={`${city.counts.free}`} note="cruising, waiting for a request" />
                  <Kpi label="To pickups" value={`${city.counts.toPickup}`} note="driving to their rider" />
                  <Kpi label="With riders" value={`${city.counts.carrying}`} note="trips under way" />
                  <Kpi label="Riders waiting" value={`${city.counts.searching + city.counts.waiting}`} note={`${city.counts.searching} still searching`} />
                </section>
                <section aria-label="Last 24 hours" className="grid grid-cols-2 gap-2">
                  <Kpi label="Requested" value={stats.data ? `${stats.data.requested}` : '–'} note={stats.data ? `${stats.data.completed} done · ${stats.data.cancelled} cancelled` : ''} />
                  <Kpi label="Match rate" value={stats.data?.matchRate == null ? '–' : `${Math.round(stats.data.matchRate * 100)}%`} note={stats.data?.avgAssignS == null ? 'no searches yet' : `${stats.data.avgAssignS} s to assign (median)`} />
                  <Kpi label="Revenue" value={stats.data ? inr(stats.data.revenue) : '–'} note={stats.data?.avgFare == null ? '' : `${inr(stats.data.avgFare)} avg fare`} />
                  <Kpi label="Pickup wait" value={stats.data?.avgPickupS == null ? '–' : stats.data.avgPickupS < 60 ? `${stats.data.avgPickupS} s` : minutes(stats.data.avgPickupS)} note="median, assigned → arrived" />
                </section>
                {grid.data && layers.grid && (
                  <p className="text-sm text-slate">
                    Map centre cell <span className="font-mono font-bold text-ink">{grid.data.center}</span>: {grid.data.availableDrivers} drivers available and {grid.data.recentRequests} requests in the 3×3 search area.
                  </p>
                )}
                <Legend />
                <p className="text-xs text-slate">
                  Start the city with <code className="rounded bg-chalk px-1 font-mono">python simulate.py --city</code>: automated drivers and riders (NPCs) book, drive and pay on their own. rider1 and driver01 are yours to test with.
                </p>
          </>
        )}
        {tab === 'rides' && <LiveRides active={inFlight} />}
        {tab === 'payments' && <Payments />}
          </>
        )}
      </aside>
    </div>
  );
}

function Legend() {
  const swatch = 'size-3 rounded-sm border border-ink';
  return (
    <div className="space-y-2 text-xs text-slate" aria-label="Legend">
      <div className="flex flex-wrap gap-3">
        {RIDE_TYPES.map((t) => (
          <span key={t} className="flex items-center gap-1">
            <span className={swatch} style={{ background: VEHICLE_PAINT[t].body }} /> {rideTypeLabel[t]}
          </span>
        ))}
        <span className="flex items-center gap-1">
          <span className={`${swatch} border-2 border-[#f08c00]`} /> To a pickup
        </span>
        <span className="flex items-center gap-1">
          <span className={`${swatch} border-2 border-[#0b6e79]`} /> With a rider
        </span>
      </div>
      <div className="flex flex-wrap gap-3">
        <span className="flex items-center gap-1">
          <span className="size-3 rounded-full border-[3px] border-auto bg-paper" /> Rider searching
        </span>
        <span className="flex items-center gap-1">
          <span className="size-3 rounded-full border-[3px] border-[#3fb6c2] bg-paper" /> Driver on the way
        </span>
        <span className="flex items-center gap-1">
          <span className="size-3 rounded-full border-[3px] border-[#1fae63] bg-paper" /> Boarding
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <span>Traffic</span>
        {Object.entries(TRAFFIC_COLORS).map(([band, color]) => (
          <span key={band} className="flex items-center gap-1 capitalize">
            <span className="h-1.5 w-4 rounded-full" style={{ background: color }} /> {band}
          </span>
        ))}
      </div>
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
function LiveRides({ active }: { active: UseQueryResult<Page<Ride>> }) {
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
          {ride.simulated && (
            <span className="rounded bg-mist px-1 font-mono text-[10px] font-bold text-ink" title="Booked by an automated rider of the living city">
              NPC
            </span>
          )}
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
