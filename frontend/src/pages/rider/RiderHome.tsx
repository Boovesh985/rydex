import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { placesApi, ridesApi } from '../../api/endpoints';
import { ACTIVE_STATUSES, type Estimate, type GeoPoint } from '../../api/types';
import { RealisticBar, RealisticButton } from '../../components/Realistic';
import { Sheet } from '../../components/ui';
import { config } from '../../config';
import type { LatLng } from '../../lib/geo';
import { BaseMap, DropPin, FitTo, Fleet, MapClicks, PickupPin, RouteLine } from '../../map/MapView';
import { useRealistic } from '../../map/useRealistic';
import { BookingPanel, type Field } from './BookingPanel';
import { useNearbyCars } from './useNearbyCars';

/** Where the rider is: the browser's position if they allow it, else Chennai Central. */
function useStartPoint(onFound: (p: GeoPoint) => void) {
  useEffect(() => {
    let cancelled = false;
    const fallback = () => {
      if (!cancelled) onFound({ ...config.center, address: 'Chennai Central' });
    };
    if (!navigator.geolocation) {
      fallback();
      return;
    }
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const p = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        const place = await placesApi.reverse(p.lat, p.lng).catch(() => null);
        if (!cancelled) onFound({ ...p, address: place ? `Your location · ${place.name}` : 'Your location' });
      },
      fallback,
      { timeout: 8000, maximumAge: 60_000 },
    );
    return () => {
      cancelled = true;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
}

export function RiderHome() {
  const navigate = useNavigate();
  const [pickup, setPickup] = useState<GeoPoint | null>(null);
  const [drop, setDrop] = useState<GeoPoint | null>(null);
  const [field, setField] = useState<Field>('drop');
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const rx = useRealistic(true);
  const nearby = useNearbyCars(pickup);

  useStartPoint((p) => setPickup((cur) => cur ?? p));

  // a ride still in progress (e.g. after a reload): go straight back to it
  const latest = useQuery({ queryKey: ['rides', 'latest'], queryFn: () => ridesApi.list(0, 1) });
  const active = latest.data?.items.find((r) => ACTIVE_STATUSES.includes(r.status));

  const tap = useCallback(
    async ([lat, lng]: LatLng) => {
      const pinned: GeoPoint = { lat, lng, address: 'Pinned location' };
      const set = field === 'pickup' ? setPickup : setDrop;
      set(pinned);
      const place = await placesApi.reverse(lat, lng).catch(() => null);
      if (place) set((cur) => (cur && cur.lat === lat && cur.lng === lng ? { ...cur, address: place.name } : cur));
      if (field === 'pickup' && !drop) setField('drop');
    },
    [field, drop],
  );

  // before a drop is chosen, frame the pickup with the closest cars (once per pickup: they keep moving)
  const pickupKey = pickup ? `${pickup.lat.toFixed(4)},${pickup.lng.toFixed(4)}` : '';
  const [framed, setFramed] = useState<{ key: string; cars: LatLng[] } | null>(null);
  if (pickup && nearby.loaded && framed?.key !== pickupKey) {
    setFramed({ key: pickupKey, cars: nearby.cars.slice(0, 3).map((c): LatLng => [c.lat, c.lng]) });
  }
  const fit = useMemo<LatLng[]>(() => {
    const pins = [pickup, drop].filter((p): p is GeoPoint => !!p).map((p): LatLng => [p.lat, p.lng]);
    return !drop && framed?.key === pickupKey ? [...pins, ...framed.cars] : pins;
  }, [pickup, drop, framed, pickupKey]);

  if (active) return <Navigate to={`/ride/${active.id}`} replace />;

  return (
    <>
      <BaseMap realistic={rx.active} night={rx.night}>
        <MapClicks onClick={tap} />
        <Fleet cars={nearby.cars} />
        {pickup && <PickupPin at={[pickup.lat, pickup.lng]} />}
        {drop && <DropPin at={[drop.lat, drop.lng]} />}
        {estimate && pickup && drop && (
          <RouteLine polyline={estimate.route.polyline} fallback={[[pickup.lat, pickup.lng], [drop.lat, drop.lng]]} />
        )}
        <FitTo points={fit} />
      </BaseMap>
      {rx.active ? <RealisticBar night={rx.night} onNight={rx.toggleNight} onExit={rx.exit} /> : <RealisticButton onClick={rx.enter} />}
      {pickup && nearby.loaded && <NearbyChip count={nearby.count} closestMin={nearby.closestMin} />}
      <Sheet>
        <BookingPanel
          pickup={pickup}
          drop={drop}
          onPickup={setPickup}
          onDrop={setDrop}
          onSwap={() => {
            setPickup(drop);
            setDrop(pickup);
          }}
          onFocusField={setField}
          onEstimate={setEstimate}
          onBooked={(ride) => navigate(`/ride/${ride.id}`)}
        />
      </Sheet>
    </>
  );
}

/** "6 cars nearby · closest 2 min", over the map. */
function NearbyChip({ count, closestMin }: { count: number; closestMin: number | null }) {
  return (
    <p
      role="status"
      className="pointer-events-none absolute top-[4.6rem] left-1/2 z-[1000] flex -translate-x-1/2 items-center gap-2 whitespace-nowrap rounded-full bg-ink px-3 py-1.5 text-sm font-bold text-chalk shadow-[var(--shadow-float)] max-md:top-auto max-md:bottom-[52dvh] md:top-20 md:left-[calc(50%+210px)]"
    >
      <span className={`size-2 rounded-full ${count > 0 ? 'bg-go' : 'bg-slate'}`} aria-hidden />
      {count === 0 ? 'No cars nearby right now' : `${count} ${count === 1 ? 'car' : 'cars'} nearby · closest ${closestMin} min`}
    </p>
  );
}
