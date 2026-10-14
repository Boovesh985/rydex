import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { placesApi, ridesApi } from '../../api/endpoints';
import { ACTIVE_STATUSES, type Estimate, type GeoPoint } from '../../api/types';
import { Sheet } from '../../components/ui';
import { config } from '../../config';
import type { LatLng } from '../../lib/geo';
import { BaseMap, DropPin, FitTo, MapClicks, PickupPin, RouteLine } from '../../map/MapView';
import { BookingPanel, type Field } from './BookingPanel';

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

  const fit = useMemo<LatLng[]>(
    () => [pickup, drop].filter((p): p is GeoPoint => !!p).map((p) => [p.lat, p.lng]),
    [pickup, drop],
  );

  if (active) return <Navigate to={`/ride/${active.id}`} replace />;

  return (
    <>
      <BaseMap>
        <MapClicks onClick={tap} />
        {pickup && <PickupPin at={[pickup.lat, pickup.lng]} />}
        {drop && <DropPin at={[drop.lat, drop.lng]} />}
        {estimate && pickup && drop && (
          <RouteLine polyline={estimate.route.polyline} fallback={[[pickup.lat, pickup.lng], [drop.lat, drop.lng]]} />
        )}
        <FitTo points={fit} />
      </BaseMap>
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
