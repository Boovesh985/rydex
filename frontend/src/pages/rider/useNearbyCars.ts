import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { driversApi } from '../../api/endpoints';
import type { GeoPoint } from '../../api/types';
import { rideTypeLabel } from '../../lib/format';
import type { FleetCar } from '../../map/MapView';

/** about 22 km/h through town, roads 1.35x the straight line (as ride-service estimates) */
const CITY_MPS = 22 / 3.6;
const ROAD_FACTOR = 1.35;

/** The free cars around a pickup, refreshed every 3 s so they drive about (ADR 0016). */
export function useNearbyCars(at: Pick<GeoPoint, 'lat' | 'lng'> | null) {
  const nearby = useQuery({
    // ~100 m buckets: moving the pin a little does not start a new search
    queryKey: ['nearby', at?.lat.toFixed(3), at?.lng.toFixed(3)],
    queryFn: () => driversApi.nearby(at!.lat, at!.lng),
    enabled: !!at,
    refetchInterval: 3000,
    placeholderData: keepPreviousData,
  });
  const data = nearby.data;
  const cars = useMemo<FleetCar[]>(
    () =>
      (data ?? []).map((c) => ({
        id: c.id,
        lat: c.lat,
        lng: c.lng,
        heading: c.heading,
        type: c.rideType,
        duty: 'free',
        label: `${rideTypeLabel[c.rideType]} nearby`,
      })),
    [data],
  );
  const closest = data?.[0];
  return {
    cars,
    count: data?.length ?? 0,
    /** minutes for the closest car to come, roughly */
    closestMin: closest ? Math.max(1, Math.round((closest.distanceM * ROAD_FACTOR) / CITY_MPS / 60)) : null,
    /** this pickup's answer has come (not the previous pickup's, still shown meanwhile) */
    loaded: !!data && !nearby.isPlaceholderData,
  };
}
