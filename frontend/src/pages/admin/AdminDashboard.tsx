import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Circle, Rectangle, Tooltip, useMapEvents } from 'react-leaflet';
import { adminApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { Grid, LiveDriver, Payment, RideType } from '../../api/types';
import { PaymentBadge } from '../../components/PaymentPanel';
import { Button, ErrorNote, Eyebrow } from '../../components/ui';
import { config } from '../../config';
import { inr, minutes, rideTypeLabel, when } from '../../lib/format';
import type { LatLng } from '../../lib/geo';
import { BaseMap, CarDot, MapMoves } from '../../map/MapView';

const TYPE_COLOR: Record<RideType, string> = { ECONOMY: '#ffc21a', COMFORT: '#3fb6c2', XL: '#f2f2ee' };

type Layer = 'drivers' | 'grid' | 'demand';

/** Ops view: live drivers, the matching grid around the map centre, where rides are requested, KPIs. */
export function AdminDashboard() {
  const [center, setCenter] = useState<LatLng>([config.center.lat, config.center.lng]);
  const [layers, setLayers] = useState<Record<Layer, boolean>>({ drivers: true, grid: true, demand: true });

  const drivers = useQuery({ queryKey: ['admin', 'drivers'], queryFn: adminApi.liveDrivers, refetchInterval: 3000 });
  const grid = useQuery({ queryKey: ['admin', 'grid', center[0].toFixed(3), center[1].toFixed(3)], queryFn: () => adminApi.grid(center[0], center[1]), refetchInterval: 10_000, enabled: layers.grid });
  const demand = useQuery({ queryKey: ['admin', 'demand'], queryFn: () => adminApi.demand(24), refetchInterval: 30_000, enabled: layers.demand });
  const stats = useQuery({ queryKey: ['admin', 'stats'], queryFn: () => adminApi.stats(24), refetchInterval: 10_000 });

  const maxDemand = useMemo(() => Math.max(1, ...(demand.data ?? []).map((c) => c.requests)), [demand.data]);
  const live = drivers.data ?? [];
  const online = live.filter((d) => d.status === 'AVAILABLE').length;
  const onTrip = live.filter((d) => d.status === 'ON_TRIP').length;

  return (
    <div className="flex h-full flex-col md:flex-row">
      <div className="relative h-[55dvh] shrink-0 md:h-full md:flex-1">
        <BaseMap zoom={13}>
          <MapMoves onMove={setCenter} />
          {layers.demand &&
            demand.data?.map((c) => (
              <Circle
                key={`${c.lat},${c.lng}`}
                center={[c.lat, c.lng]}
                radius={280}
                pathOptions={{ stroke: false, fillColor: '#d7362b', fillOpacity: 0.12 + 0.5 * (c.requests / maxDemand) }}
              >
                <Tooltip>{c.requests} ride requests (24 h)</Tooltip>
              </Circle>
            ))}
          {layers.grid && grid.data && <GridCells grid={grid.data} />}
          {layers.drivers && live.map((d) => <DriverDot key={d.driverId} d={d} />)}
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
      </div>

      <aside className="flex-1 space-y-5 overflow-y-auto bg-paper p-4 md:w-[400px] md:flex-none md:pt-20">
        <div>
          <h1 className="font-display-wide text-2xl font-black">Operations</h1>
          <p className="text-sm text-slate">Last 24 hours · refreshes every 10 s</p>
        </div>
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
            <span className="size-3 rounded-full bg-signal/50" /> Demand
          </span>
        </div>
        <Payments />
      </aside>
    </div>
  );
}

/**
 * The 3x3 geohash-6 cells matching searches first (ADR 0008), around the map centre. Labels
 * (available drivers · recent requests) show from zoom 14, where the ~1.2 x 0.6 km cells are big enough.
 */
function GridCells({ grid }: { grid: Grid }) {
  const [zoom, setZoom] = useState(13);
  const map = useMapEvents({ zoomend: () => setZoom(map.getZoom()) });
  return (
    <>
      {grid.cells.map((c) => (
        <Rectangle
          key={c.geohash + String(zoom >= 14)}
          bounds={[
            [c.minLat, c.minLng],
            [c.maxLat, c.maxLng],
          ]}
          pathOptions={{ color: '#16181d', weight: c.position === 'C' ? 3 : 1.5, dashArray: c.position === 'C' ? undefined : '4 4', fillOpacity: c.position === 'C' ? 0.08 : 0.03 }}
        >
          <Tooltip
            permanent={zoom >= 14}
            direction="center"
            className="!rounded-md !border-0 !bg-ink/85 !px-1.5 !py-0.5 !font-mono !text-[11px] !text-chalk !shadow-none"
          >
            {c.position === 'C' ? `${c.geohash} · ` : ''}
            {c.availableDrivers} drv · {c.recentRequests} req
          </Tooltip>
        </Rectangle>
      ))}
    </>
  );
}

function DriverDot({ d }: { d: LiveDriver }) {
  const color = d.rideType ? TYPE_COLOR[d.rideType] : '#999';
  return (
    <CarDot
      lat={d.lat}
      lng={d.lng}
      heading={d.heading}
      color={d.status === 'ON_TRIP' ? '#5b616b' : color}
      title={`${d.rideType ? rideTypeLabel[d.rideType] : 'Driver'} · ${d.status === 'ON_TRIP' ? 'on a trip' : (d.status?.toLowerCase() ?? 'unknown')}`}
    />
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
          className="mt-2 flex gap-2"
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
            className="h-10 min-w-0 flex-1 rounded-lg border border-mist bg-paper px-2"
          />
          <Button type="submit" variant="dark" className="!min-h-10 !px-3" busy={refund.isPending}>
            Refund
          </Button>
        </form>
      )}
      <ErrorNote>{refund.error && errorMessage(refund.error)}</ErrorNote>
    </div>
  );
}
