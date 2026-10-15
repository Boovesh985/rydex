import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { placesApi } from '../api/endpoints';
import { matchOnRoute, PathIndex, type LatLng, type RouteMatch } from '../lib/geo';
import { decodePolyline } from '../lib/polyline';

/** One stretch of a trip: `key` changes per leg (to the pickup, then to the drop). */
export interface Leg {
  key: string;
  /** where the leg starts; only its first value counts (later ones are where the car happens to be) */
  from: LatLng | null;
  to: LatLng;
}

/** Off the route this many fixes in a row (45 m+, beyond GPS noise): plan a new route from where the car is. */
const OFF_FIXES_TO_REROUTE = 1;
/** Fixes to wait after a new route before planning another (it is fetched from a free OSRM server). */
const FIXES_BETWEEN_REROUTES = 2;

interface Track {
  index: PathIndex;
  fix: object;
  match: RouteMatch;
  off: number;
  sinceReroute: number;
}

/**
 * The road a car follows on one leg, and where the car is along it. Fixes are matched to the
 * route ahead (never backwards); when the car leaves the route (a detour, a different turn), a
 * new route is planned from where it is, as navigation apps do.
 *
 * `fix` must be a new object for each new position (the live location messages are).
 */
export function useFollowedRoute(leg: Leg | null, fix: { lat: number; lng: number } | null) {
  // `before`: metres driven on this leg's earlier routes (before a detour made a new one)
  const [origin, setOrigin] = useState<{ key: string; from: LatLng; reroutes: number; before: number } | null>(null);
  if (leg?.from && origin?.key !== leg.key) setOrigin({ key: leg.key, from: leg.from, reroutes: 0, before: 0 });
  const from = leg && origin?.key === leg.key ? origin.from : null;

  const route = useQuery({
    queryKey: ['route', leg?.key, from?.map((v) => v.toFixed(5)).join(','), leg?.to.join(',')],
    queryFn: () => placesApi.route({ lat: from![0], lng: from![1] }, { lat: leg!.to[0], lng: leg!.to[1] }),
    enabled: !!leg && !!from,
    staleTime: Infinity,
    // a new route for the same leg: keep drawing the old one until it arrives
    placeholderData: (previous, query) => (query?.queryKey[1] === leg?.key ? previous : undefined),
  });
  const data = route.data;
  const points = useMemo<LatLng[] | null>(() => {
    if (!leg || !from || route.isPending) return null;
    if (data?.polyline) return decodePolyline(data.polyline);
    return [from, leg.to];
    // the route only changes with its query
  }, [data, route.isPending]); // eslint-disable-line react-hooks/exhaustive-deps
  const index = useMemo(() => (points && points.length > 1 ? new PathIndex(points) : null), [points]);

  // match each new fix to the route
  const [track, setTrack] = useState<Track | null>(null);
  if (index && fix && (track?.index !== index || track.fix !== fix)) {
    const same = track?.index === index;
    const match = matchOnRoute(index, [fix.lat, fix.lng], same ? track.match : null);
    const off = match.onRoute ? 0 : (same ? track.off : 0) + 1;
    const sinceReroute = (same ? track.sinceReroute : 0) + 1;
    setTrack({ index, fix, match, off, sinceReroute });
    if (leg && origin?.key === leg.key && data?.polyline && !route.isPlaceholderData && off >= OFF_FIXES_TO_REROUTE && sinceReroute > FIXES_BETWEEN_REROUTES) {
      setOrigin({ key: leg.key, from: [fix.lat, fix.lng], reroutes: origin.reroutes + 1, before: origin.before + match.along });
    }
  }
  const match = track && track.index === index ? track.match : null;

  // the share of the leg done: detours included, and never going down (the bar must not jump back)
  const raw = index && match && origin && !route.isPlaceholderData ? (origin.before + match.along) / Math.max(1, origin.before + index.length) : null;
  const [best, setBest] = useState<{ key: string; value: number } | null>(null);
  if (leg && raw != null && (best?.key !== leg.key || raw > best.value)) setBest({ key: leg.key, value: raw });
  const legProgress = leg && best?.key === leg.key ? Math.max(best.value, raw ?? 0) : raw;

  return {
    points,
    /** encoded polyline when the route came from OSRM (null: a straight-line guess) */
    polyline: data?.polyline ?? null,
    index,
    match,
    /** 0..1 of the drawn route already behind the car */
    lineProgress: index && match && index.length > 0 ? match.along / index.length : null,
    /** metres of road left to the end of the leg */
    remaining: index && match ? Math.max(0, index.length - match.along) : null,
    /** 0..1 of the whole leg done, detours included; it only goes up */
    legProgress,
    rerouted: (origin?.reroutes ?? 0) > 0,
  };
}
