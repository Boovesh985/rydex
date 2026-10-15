import type { Feature, FeatureCollection } from 'geojson';
import * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// MapLibre parses tiles in a web worker; Vite builds it (with its shared chunk) and hands over the URL
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { createContext, useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import type { RideType } from '../api/types';
import { config } from '../config';
import { bearing, haversine, lerp, lerpHeading, PathIndex, type LatLng } from '../lib/geo';
import { decodePolyline } from '../lib/polyline';
import type { CarLayer, CarPose } from './car3d';
import { applyLook, BUILDINGS_3D, ROUTE_COLORS, STYLE_URL, type Look } from './theme';

/*
 * The maps of every screen, on MapLibre GL (vector tiles, WebGL). The same map switches between the
 * clean 2D view and the realistic 3D view: pitched camera, extruded buildings, sky, and 3D cars drawn
 * with three.js (loaded only when realistic mode is first used).
 */

maplibregl.setWorkerUrl(workerUrl);

interface MapState {
  map: MlMap;
  realistic: boolean;
  night: boolean;
  /** the three.js car layer, while realistic mode is on */
  cars: CarLayer | null;
  /** set by a car that the camera follows, so the map does not move the camera itself */
  followerRef: { current: boolean };
}

const MapContext = createContext<MapState | null>(null);

function useMapState(): MapState {
  const state = useContext(MapContext);
  if (!state) throw new Error('map components must be inside <BaseMap>');
  return state;
}

function supportsWebGL(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') ?? canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

/** Space the sheet takes: on the left on wide screens, along the bottom on phones. */
function sheetPadding(realistic: boolean) {
  const wide = window.innerWidth >= 768;
  if (realistic) return { top: 120, bottom: 140, left: 60, right: 60 };
  return { top: 90, left: wide ? 450 : 40, right: wide ? 70 : 40, bottom: wide ? 50 : Math.round(window.innerHeight * 0.5) };
}

interface BaseMapProps {
  center?: LatLng;
  zoom?: number;
  children?: ReactNode;
  className?: string;
  /** 3D buildings, sky, pitched camera and 3D cars */
  realistic?: boolean;
  night?: boolean;
}

/** Full-bleed map; zoom/compass buttons sit bottom-right, clear of the sheets. */
export function BaseMap({ center = [config.center.lat, config.center.lng], zoom = 14, children, className, realistic = false, night = false }: BaseMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const [map, setMap] = useState<MlMap | null>(null);
  const [failed, setFailed] = useState(() => !supportsWebGL());
  const [cars, setCars] = useState<CarLayer | null>(null);
  const followerRef = useRef(false);
  const look = useRef<Look | undefined>(undefined);

  useEffect(() => {
    if (failed) return;
    let m: MlMap;
    try {
      m = new maplibregl.Map({
        container: container.current!,
        style: STYLE_URL,
        center: [center[1], center[0]],
        zoom,
        maxPitch: 72,
        attributionControl: { compact: true },
        // labels appear at once: fades need animation frames, which background tabs pause
        fadeDuration: 0,
        canvasContextAttributes: { antialias: true },
      });
    } catch {
      void Promise.resolve().then(() => setFailed(true)); // WebGL refused after all
      return;
    }
    m.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'bottom-right');
    m.on('load', () => setMap(m));
    // dev builds: the map is reachable from the console and from browser tests
    if (import.meta.env.DEV) (window as unknown as { __rxMap?: MlMap }).__rxMap = m;
    const resize = new ResizeObserver(() => m.resize());
    resize.observe(container.current!);
    return () => {
      resize.disconnect();
      m.remove();
    };
    // the initial view only; screens move the camera with FitTo and the car
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // palette, buildings, sky; and the camera when entering or leaving realistic mode
  useEffect(() => {
    if (!map) return;
    const next = { realistic, night };
    const previous = look.current;
    applyLook(map, next, previous);
    look.current = next;
    if (previous && previous.realistic !== realistic && !followerRef.current) {
      const duration = reducedMotion() ? 0 : 1400;
      if (realistic) map.easeTo({ pitch: 58, bearing: map.getBearing() || -18, zoom: Math.max(map.getZoom(), 15.4), duration });
      else map.easeTo({ pitch: 0, bearing: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: duration * 0.7 });
    }
  }, [map, realistic, night]);

  // the 3D car layer exists only in realistic mode
  useEffect(() => {
    if (!map || !realistic) return;
    let layer: CarLayer | null = null;
    let cancelled = false;
    void import('./car3d').then(({ createCarLayer }) => {
      if (cancelled || !map.getStyle()) return;
      layer = createCarLayer();
      map.addLayer(layer.layer);
      setCars(layer);
    });
    return () => {
      cancelled = true;
      if (layer && map.getStyle() && map.getLayer(layer.layer.id)) map.removeLayer(layer.layer.id);
      setCars(null);
    };
  }, [map, realistic]);

  useEffect(() => cars?.setNight(night), [cars, night]);

  const state = useMemo(() => (map ? { map, realistic, night, cars: realistic ? cars : null, followerRef } : null), [map, realistic, night, cars]);

  return (
    <div className={`relative ${className ?? 'h-full w-full'}`}>
      <div ref={container} className="absolute inset-0 h-full w-full" data-testid="map-canvas" />
      {failed && (
        <div className="absolute inset-0 grid place-items-center bg-mist p-6 text-center text-sm text-slate">
          This browser cannot show the map (WebGL is turned off). Everything else still works.
        </div>
      )}
      {state && <MapContext.Provider value={state}>{children}</MapContext.Provider>}
    </div>
  );
}

/**
 * Fits the view to the given points whenever they change (by value), keeping them clear of
 * the sheet. Skipped in realistic mode, where the camera follows the car.
 */
export function FitTo({ points, maxZoom = 16, refit = 0 }: { points: LatLng[]; maxZoom?: number; /** bump to fit again */ refit?: number }) {
  const { map, realistic, followerRef } = useMapState();
  const key = points.map((p) => p.map((n) => n.toFixed(4)).join(',')).join(';');
  useEffect(() => {
    if (points.length === 0 || followerRef.current) return;
    const bounds = new maplibregl.LngLatBounds();
    for (const [lat, lng] of points) bounds.extend([lng, lat]);
    // drop the follow camera's padding, frame the points from straight above, then tilt in realistic mode
    map.setPadding({ top: 0, bottom: 0, left: 0, right: 0 });
    const camera = map.cameraForBounds(bounds, { padding: sheetPadding(realistic), maxZoom: points.length === 1 ? 15.5 : maxZoom, bearing: 0 });
    if (!camera) return;
    map.easeTo({
      center: camera.center,
      zoom: realistic ? (camera.zoom ?? map.getZoom()) - 0.2 : camera.zoom,
      pitch: realistic ? 52 : 0,
      bearing: realistic ? map.getBearing() : 0,
      // instant in 2D: a GeoJSON source added during a camera animation can fail to draw (MapLibre load race)
      duration: realistic && !reducedMotion() ? 800 : 0,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-fit only when the points change by value
  }, [key, map, realistic, refit]);
  return null;
}

/** Calls back with taps on the map. */
export function MapClicks({ onClick }: { onClick: (p: LatLng) => void }) {
  const { map } = useMapState();
  const handler = useRef(onClick);
  useEffect(() => {
    handler.current = onClick;
  }, [onClick]);
  useEffect(() => {
    const listener = (e: maplibregl.MapMouseEvent) => handler.current([e.lngLat.lat, e.lngLat.lng]);
    map.on('click', listener);
    map.getCanvas().style.cursor = 'crosshair';
    return () => {
      map.off('click', listener);
      map.getCanvas().style.cursor = '';
    };
  }, [map]);
  return null;
}

/** Calls back when the user stops panning or zooming. */
export function MapMoves({ onMove }: { onMove: (center: LatLng) => void }) {
  const { map } = useMapState();
  const handler = useRef(onMove);
  useEffect(() => {
    handler.current = onMove;
  }, [onMove]);
  useEffect(() => {
    const listener = () => handler.current([map.getCenter().lat, map.getCenter().lng]);
    map.on('moveend', listener);
    return () => {
      map.off('moveend', listener);
    };
  }, [map]);
  return null;
}

// ------------------------------------------------------------------ markers

/** A DOM marker; `make` builds its element once. */
function useMarker(at: LatLng, make: () => HTMLElement, options: maplibregl.MarkerOptions = {}) {
  const { map } = useMapState();
  const marker = useRef<maplibregl.Marker | null>(null);
  useEffect(() => {
    const m = new maplibregl.Marker({ element: make(), ...options }).setLngLat([at[1], at[0]]).addTo(map);
    marker.current = m;
    return () => {
      m.remove();
      marker.current = null;
    };
  }, [map]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    marker.current?.setLngLat([at[1], at[0]]);
  }, [at]);
  return marker;
}

function pinElement(kind: 'pickup' | 'drop', label: string): HTMLElement {
  const el = document.createElement('div');
  el.className = `rx-pin rx-pin-${kind}`;
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', label);
  el.innerHTML = `<span class="rx-pin-flag"><i></i>${label}</span><span class="rx-pin-stem"></span><span class="rx-pin-dot"></span>`;
  return el;
}

/** Pickup: a flag on a stem, standing up in 3D. */
export function PickupPin({ at }: { at: LatLng }) {
  useMarker(at, () => pinElement('pickup', 'Pickup'), { anchor: 'bottom', offset: [0, 7] });
  return null;
}

export function DropPin({ at }: { at: LatLng }) {
  useMarker(at, () => pinElement('drop', 'Drop'), { anchor: 'bottom', offset: [0, 7] });
  return null;
}

const CAR_COLORS: Record<RideType, string> = { ECONOMY: '#ffc21a', COMFORT: '#f2f3ef', XL: '#2c313a' };

/** A top-down car (flat on the road in 3D), with a soft shadow. */
function carElement(type: RideType | null | undefined, size: number, label: string): HTMLElement {
  const color = type ? CAR_COLORS[type] : '#ffc21a';
  const el = document.createElement('div');
  el.className = 'rx-car';
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', label);
  el.title = label;
  el.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 40 40" aria-hidden="true">
    <ellipse cx="20" cy="21" rx="10" ry="17" fill="rgba(0,0,0,.22)"/>
    <rect x="11" y="3" width="18" height="34" rx="7" fill="${color}" stroke="#16181d" stroke-width="2"/>
    <path d="M13.5 12.5 Q20 9.5 26.5 12.5 L25.5 17 Q20 15.5 14.5 17Z" fill="#16181d"/>
    <path d="M14.5 27 Q20 28.5 25.5 27 L26 31 Q20 33 14 31Z" fill="#16181d" opacity=".8"/>
    <rect x="13" y="3.6" width="4" height="2" rx="1" fill="#fff6c8"/><rect x="23" y="3.6" width="4" height="2" rx="1" fill="#fff6c8"/>
  </svg>`;
  return el;
}

interface Pose {
  lat: number;
  lng: number;
  heading: number;
}

interface AnimatedCarProps {
  lat: number;
  lng: number;
  heading: number | null;
  /** how long to glide to each new fix; a little longer than the update interval keeps it moving */
  glideMs?: number;
  /** the road ahead: fixes near it are snapped and the car follows its curves between them */
  route?: LatLng[] | null;
  type?: RideType | null;
  /** m/s, for the 3D wheels */
  speed?: number | null;
  /** realistic mode: the camera rides behind the car */
  follow?: boolean;
  /** the user moved the map away while following */
  onFollowLost?: () => void;
  label?: string;
}

/**
 * The live car. Fixes arrive every ~3 s; it glides from where it is towards each new fix, along
 * the route when the fix is on it (so it takes the corners), and turns the short way round. In
 * realistic mode it is a 3D model and the camera can follow it.
 */
export function AnimatedCar({ lat, lng, heading, glideMs = 3100, route, type = null, speed = null, follow = false, onFollowLost, label = 'Your driver' }: AnimatedCarProps) {
  const { map, realistic, cars, followerRef } = useMapState();
  const id = useId();
  const shown = useRef<Pose | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const camBearing = useRef<number | null>(null);
  const shownAlong = useRef<{ index: PathIndex; along: number } | null>(null);
  const following = useRef(false);
  const live = useRef({ cars, type, speed, realistic, follow, onFollowLost });
  useEffect(() => {
    live.current = { cars, type, speed, realistic, follow, onFollowLost };
  });
  const index = useMemo(() => (route && route.length > 1 ? new PathIndex(route) : null), [route]);

  const draw = (pose: Pose) => {
    const { cars: layer, type: t, speed: v, realistic: r } = live.current;
    if (layer) layer.set(id, { lat: pose.lat, lng: pose.lng, heading: pose.heading, speed: v ?? 0, type: t, focus: true } satisfies CarPose);
    else if (marker.current) {
      marker.current.setLngLat([pose.lng, pose.lat]);
      marker.current.setRotation(pose.heading);
    }
    if (r && following.current) {
      camBearing.current = lerpHeading(camBearing.current ?? pose.heading, pose.heading, reducedMotion() ? 1 : 0.07);
      map.jumpTo({ center: [pose.lng, pose.lat], bearing: camBearing.current });
    }
  };

  // 2D: a DOM marker (3D: the car layer draws it)
  useEffect(() => {
    if (cars) return;
    const start = shown.current ?? { lat, lng, heading: heading ?? 0 };
    const m = new maplibregl.Marker({ element: carElement(type, 40, label), rotationAlignment: 'map', pitchAlignment: 'map' })
      .setLngLat([start.lng, start.lat])
      .setRotation(start.heading)
      .addTo(map);
    marker.current = m;
    return () => {
      m.remove();
      marker.current = null;
    };
  }, [map, cars, type, label]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!cars) return;
    if (shown.current) draw(shown.current);
    return () => cars.remove(id);
  }, [cars]); // eslint-disable-line react-hooks/exhaustive-deps

  // follow camera: swoop in behind the car, then ride along; dragging the map stops it
  useEffect(() => {
    const active = realistic && follow;
    followerRef.current = active;
    if (!active) {
      following.current = false;
      return;
    }
    const pose = shown.current ?? { lat, lng, heading: heading ?? 0 };
    camBearing.current = pose.heading;
    const h = map.getContainer().clientHeight;
    following.current = false;
    map.easeTo({
      center: [pose.lng, pose.lat],
      zoom: 17.4,
      pitch: 64,
      bearing: pose.heading,
      padding: { top: Math.round(h * 0.42), bottom: 30, left: 0, right: 0 },
      duration: reducedMotion() ? 0 : 1600,
    });
    const ready = () => {
      following.current = true;
    };
    map.once('moveend', ready);
    const lost = (e: { originalEvent?: unknown }) => {
      if (e.originalEvent && following.current) {
        following.current = false;
        live.current.onFollowLost?.();
      }
    };
    map.on('dragstart', lost);
    map.on('rotatestart', lost);
    map.on('pitchstart', lost);
    return () => {
      following.current = false;
      followerRef.current = false;
      map.off('moveend', ready);
      map.off('dragstart', lost);
      map.off('rotatestart', lost);
      map.off('pitchstart', lost);
    };
  }, [map, realistic, follow]); // eslint-disable-line react-hooks/exhaustive-deps

  // glide to each new fix
  useEffect(() => {
    const target: LatLng = [lat, lng];
    const from = shown.current ?? { lat, lng, heading: heading ?? 0 };
    const hint = shownAlong.current?.index === index ? shownAlong.current.along : null;
    const { position, endAlong } = planGlide(from, target, heading, index, hint);
    shownAlong.current = index && endAlong != null ? { index, along: endAlong } : null;
    const reduced = reducedMotion();
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const t = reduced ? 1 : Math.min(1, (now - start) / glideMs);
      const next = position(t);
      const prev = shown.current ?? next;
      // ease the heading round corners instead of snapping at every polyline vertex
      const pose = { ...next, heading: reduced ? next.heading : lerpHeading(prev.heading, next.heading, 0.18) };
      shown.current = pose;
      draw(pose);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    // background tabs pause animation frames: land on the fix anyway
    const snap = setTimeout(() => step(start + glideMs), glideMs + 250);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(snap);
    };
  }, [lat, lng, heading, glideMs, index]); // eslint-disable-line react-hooks/exhaustive-deps

  return null;
}

/**
 * Where the car is at t (0..1) between its shown pose and a new fix: along the road when both are
 * on the route, looking only ahead of where the car is (`hint`, metres along), so a route passing
 * the same spot twice cannot send it back to the first pass. `endAlong` is where it ends up.
 */
function planGlide(from: Pose, to: LatLng, heading: number | null, index: PathIndex | null, hint: number | null): { position: (t: number) => Pose; endAlong: number | null } {
  const start: LatLng = [from.lat, from.lng];
  const dist = haversine(start, to);
  if (index) {
    let a = hint != null ? index.project(start, { from: hint - 60, to: hint + 60 }) : index.project(start);
    if (a.offset >= 40 && hint != null) a = index.project(start);
    const b = index.project(to, { from: a.along - 20, to: a.along + dist * 2.5 + 300 });
    const forward = b.along - a.along;
    if (a.offset < 40 && b.offset < 40 && forward > -3 && forward < dist * 2.5 + 300) {
      if (dist <= 1) return { position: () => from, endAlong: a.along };
      return {
        position: (t) => {
          const p = index.at(a.along + forward * t);
          return { lat: p.point[0], lng: p.point[1], heading: forward > 0.5 ? p.heading : from.heading };
        },
        endAlong: b.along,
      };
    }
  }
  const finalHeading = heading ?? (dist > 3 ? bearing(start, to) : from.heading);
  return {
    position: (t) => {
      const [la, ln] = lerp(start, to, t);
      return { lat: la, lng: ln, heading: lerpHeading(from.heading, finalHeading, Math.min(1, t * 3)) };
    },
    endAlong: null,
  };
}

// ------------------------------------------------------------------ routes

/**
 * The planned route: an ink casing under a teal line (dashed when it is only a straight-line
 * guess). `progress` (0..1) greys out the part already driven. Drawn under the 3D buildings.
 */
export function RouteLine({ polyline, fallback, progress, tone = 'route' }: { polyline: string | null | undefined; fallback?: LatLng[]; progress?: number | null; /** "trail": the path actually driven, solid yellow */ tone?: 'route' | 'trail' }) {
  const { map, night, realistic } = useMapState();
  const id = `rx-route-${useId().replace(/:/g, '')}`;
  const points = useMemo(() => (polyline ? decodePolyline(polyline) : (fallback ?? [])), [polyline, fallback]);
  const dashed = !polyline && tone === 'route';
  const colors = tone === 'trail' ? ROUTE_COLORS.trail : night ? ROUTE_COLORS.night : ROUTE_COLORS.day;
  const data = useMemo<Feature | FeatureCollection>(
    () => (points.length < 2 ? emptyLine : { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: points.map(([la, ln]) => [ln, la]) } }),
    [points],
  );
  const sent = useRef<unknown>(null);

  useEffect(() => {
    const teardown = () => {
      if (!map.getStyle()) return;
      for (const layer of ['glow', 'casing', 'line']) if (map.getLayer(`${id}-${layer}`)) map.removeLayer(`${id}-${layer}`);
      if (map.getSource(id)) map.removeSource(id);
    };
    // created with its data: an empty source updated at once can keep drawing nothing (a load race)
    map.addSource(id, { type: 'geojson', lineMetrics: true, data });
    sent.current = data;
    const before = map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : undefined;
    const width = (base: number): maplibregl.ExpressionSpecification => ['interpolate', ['exponential', 1.6], ['zoom'], 11, base * 0.45, 15, base, 18, base * 2.2];
    map.addLayer({ id: `${id}-glow`, type: 'line', source: id, layout: { 'line-cap': 'round', 'line-join': 'round', visibility: 'none' }, paint: { 'line-width': width(16), 'line-blur': 10, 'line-opacity': 0.45 } }, before);
    map.addLayer({ id: `${id}-casing`, type: 'line', source: id, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-width': width(8.5), 'line-opacity': 0.9 } }, before);
    map.addLayer({ id: `${id}-line`, type: 'line', source: id, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-width': width(4.5) } }, before);
    return teardown;
  }, [map, id]); // eslint-disable-line react-hooks/exhaustive-deps -- later data goes through setData below

  useEffect(() => {
    if (sent.current === data) return;
    sent.current = data;
    (map.getSource(id) as GeoJSONSource | undefined)?.setData(data);
  }, [map, id, data]);

  useEffect(() => {
    if (!map.getLayer(`${id}-line`)) return;
    map.setPaintProperty(`${id}-casing`, 'line-color', colors.casing);
    map.setPaintProperty(`${id}-glow`, 'line-color', colors.glow);
    map.setLayoutProperty(`${id}-glow`, 'visibility', realistic || night ? 'visible' : 'none');
    map.setPaintProperty(`${id}-casing`, 'line-dasharray', dashed ? [0.4, 1.6] : undefined);
    map.setPaintProperty(`${id}-line`, 'line-dasharray', dashed ? [0.4, 1.6] : undefined);
    const p = dashed || progress == null ? 0 : Math.min(0.999, Math.max(0, progress));
    if (dashed) {
      map.setPaintProperty(`${id}-line`, 'line-gradient', undefined);
      map.setPaintProperty(`${id}-line`, 'line-color', colors.line);
    } else {
      map.setPaintProperty(`${id}-line`, 'line-gradient', p > 0 ? ['step', ['line-progress'], colors.done, p, colors.line] : ['step', ['line-progress'], colors.line, 1, colors.line]);
    }
  }, [map, id, colors, dashed, progress, realistic, night]);

  return null;
}

const emptyLine: FeatureCollection = { type: 'FeatureCollection', features: [] };

// ------------------------------------------------------------------ admin layers

export interface FleetCar {
  id: string;
  lat: number;
  lng: number;
  heading: number | null;
  type: RideType | null;
  onTrip: boolean;
  label: string;
}

/**
 * Every live driver: small car markers in 2D, 3D models in realistic mode; they glide between the
 * 3-second updates. Markers move on the main thread: re-sending a GeoJSON source every frame would
 * keep MapLibre's (single) tile worker busy and stall tile loading.
 */
export function Fleet({ cars: fleet, glideMs = 2800 }: { cars: FleetCar[]; glideMs?: number }) {
  const { map, cars: layer } = useMapState();
  const shown = useRef(new Map<string, Pose>());
  const markers = useRef(new Map<string, { marker: maplibregl.Marker; look: string }>());
  /** ids the 3D layer currently draws (mutated, never replaced, so cleanups see the latest) */
  const drawn = useRef(new Set<string>());

  // markers exist only in 2D; the car layer draws the fleet in realistic mode
  useEffect(() => {
    const all = markers.current;
    return () => {
      for (const { marker } of all.values()) marker.remove();
      all.clear();
    };
  }, [map, layer]);

  useEffect(() => {
    const from = new Map(shown.current);
    const reduced = reducedMotion();
    const start = performance.now();
    const ids = new Set(fleet.map((c) => c.id));
    for (const [id, { marker }] of markers.current) {
      if (!ids.has(id)) {
        marker.remove();
        markers.current.delete(id);
      }
    }
    for (const id of [...drawn.current]) {
      if (!ids.has(id)) {
        layer?.remove(id);
        drawn.current.delete(id);
      }
    }
    if (!layer) {
      for (const car of fleet) {
        const look = `${car.type}:${car.onTrip}`;
        const existing = markers.current.get(car.id);
        if (existing && existing.look === look) continue;
        existing?.marker.remove();
        const el = carElement(car.type, 28, car.label);
        if (car.onTrip) el.style.opacity = '0.55';
        const marker = new maplibregl.Marker({ element: el, rotationAlignment: 'map', pitchAlignment: 'map' }).setLngLat([car.lng, car.lat]).addTo(map);
        markers.current.set(car.id, { marker, look });
      }
    }
    let frame = 0;
    const step = (now: number) => {
      const t = reduced ? 1 : Math.min(1, (now - start) / glideMs);
      const next = new Map<string, Pose>();
      for (const car of fleet) {
        const prev = from.get(car.id) ?? { lat: car.lat, lng: car.lng, heading: car.heading ?? 0 };
        const [la, ln] = lerp([prev.lat, prev.lng], [car.lat, car.lng], t);
        const pose = { lat: la, lng: ln, heading: lerpHeading(prev.heading, car.heading ?? prev.heading, Math.min(1, t * 2)) };
        next.set(car.id, pose);
        const m = markers.current.get(car.id)?.marker;
        if (m) {
          m.setLngLat([ln, la]);
          m.setRotation(pose.heading);
        }
        if (layer) {
          layer.set(car.id, { ...pose, speed: 0, type: car.type, dim: car.onTrip });
          drawn.current.add(car.id);
        }
      }
      shown.current = next;
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    const snap = setTimeout(() => step(start + glideMs), glideMs + 250);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(snap);
    };
  }, [map, fleet, glideMs, layer]);

  // the 3D models leave with realistic mode or with the fleet layer
  useEffect(() => {
    if (!layer) return;
    const ids = drawn.current;
    return () => {
      ids.forEach((id) => layer.remove(id));
      ids.clear();
    };
  }, [layer]);

  return null;
}

/** Ride requests as a heatmap, turning into counted circles when zoomed in. */
export function DemandHeat({ cells }: { cells: { lat: number; lng: number; requests: number }[] }) {
  const { map } = useMapState();
  const source = `rx-demand-${useId().replace(/:/g, '')}`;
  const data = useMemo<FeatureCollection>(() => {
    const max = Math.max(1, ...cells.map((c) => c.requests));
    return {
      type: 'FeatureCollection',
      features: cells.map((c) => ({ type: 'Feature', properties: { weight: c.requests / max, requests: c.requests }, geometry: { type: 'Point', coordinates: [c.lng, c.lat] } })),
    };
  }, [cells]);
  const sent = useRef<unknown>(null);
  useEffect(() => {
    const teardown = () => {
      if (!map.getStyle()) return;
      for (const l of ['heat', 'dots']) if (map.getLayer(`${source}-${l}`)) map.removeLayer(`${source}-${l}`);
      if (map.getSource(source)) map.removeSource(source);
    };
    const before = map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : undefined;
    map.addSource(source, { type: 'geojson', data });
    sent.current = data;
    map.addLayer(
      {
        id: `${source}-heat`,
        type: 'heatmap',
        source,
        maxzoom: 16,
        paint: {
          'heatmap-weight': ['get', 'weight'],
          'heatmap-intensity': ['interpolate', ['linear'], ['zoom'], 10, 0.8, 15, 1.6],
          'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 10, 18, 15, 48],
          'heatmap-opacity': ['interpolate', ['linear'], ['zoom'], 14, 0.75, 16, 0.25],
          'heatmap-color': ['interpolate', ['linear'], ['heatmap-density'], 0, 'rgba(255,194,26,0)', 0.25, 'rgba(255,194,26,0.45)', 0.6, 'rgba(240,120,40,0.7)', 1, 'rgba(215,54,43,0.85)'],
        },
      },
      before,
    );
    map.addLayer(
      {
        id: `${source}-dots`,
        type: 'circle',
        source,
        minzoom: 14,
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['get', 'weight'], 0, 6, 1, 16],
          'circle-color': '#d7362b',
          'circle-opacity': ['interpolate', ['linear'], ['zoom'], 14, 0, 15, 0.55],
          'circle-stroke-color': '#fff',
          'circle-stroke-width': 1,
        },
      },
      before,
    );
    return teardown;
  }, [map, source]); // eslint-disable-line react-hooks/exhaustive-deps -- later data goes through setData below
  useEffect(() => {
    if (sent.current === data) return;
    sent.current = data;
    (map.getSource(source) as GeoJSONSource | undefined)?.setData(data);
  }, [map, source, data]);
  return null;
}

export interface GridCellShape {
  geohash: string;
  center: boolean;
  bounds: [number, number, number, number];
  label: string;
}

/** The matching grid: outlined cells (the centre one bold) with their counts, labelled from zoom 14. */
export function GridOverlay({ cells }: { cells: GridCellShape[] }) {
  const { map, night } = useMapState();
  const source = `rx-grid-${useId().replace(/:/g, '')}`;
  const data = useMemo<FeatureCollection>(
    () => ({
      type: 'FeatureCollection',
      features: cells.map((c) => {
        const [s, w, n, e] = c.bounds;
        return {
          type: 'Feature',
          properties: { center: c.center, label: c.label },
          geometry: { type: 'Polygon', coordinates: [[[w, s], [e, s], [e, n], [w, n], [w, s]]] },
        };
      }),
    }),
    [cells],
  );
  const sent = useRef<unknown>(null);
  useEffect(() => {
    const teardown = () => {
      if (!map.getStyle()) return;
      for (const l of ['fill', 'line', 'label']) if (map.getLayer(`${source}-${l}`)) map.removeLayer(`${source}-${l}`);
      if (map.getSource(source)) map.removeSource(source);
    };
    const before = map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : undefined;
    map.addSource(source, { type: 'geojson', data });
    sent.current = data;
    map.addLayer({ id: `${source}-fill`, type: 'fill', source, paint: { 'fill-color': '#16181d', 'fill-opacity': ['case', ['get', 'center'], 0.08, 0.03] } }, before);
    map.addLayer({ id: `${source}-line`, type: 'line', source, paint: { 'line-width': ['case', ['get', 'center'], 2.5, 1.2], 'line-dasharray': [3, 2] } }, before);
    map.addLayer({
      id: `${source}-label`,
      type: 'symbol',
      source,
      minzoom: 14,
      layout: { 'text-field': ['get', 'label'], 'text-font': ['Noto Sans Bold'], 'text-size': 11, 'text-allow-overlap': true },
      paint: { 'text-halo-width': 1.5 },
    });
    return teardown;
  }, [map, source]); // eslint-disable-line react-hooks/exhaustive-deps -- later data goes through setData below
  useEffect(() => {
    if (!map.getLayer(`${source}-line`)) return;
    map.setPaintProperty(`${source}-line`, 'line-color', night ? '#c9ced6' : '#16181d');
    map.setPaintProperty(`${source}-label`, 'text-color', night ? '#f4f5f0' : '#16181d');
    map.setPaintProperty(`${source}-label`, 'text-halo-color', night ? '#16181d' : '#ffffff');
  }, [map, source, night]);
  useEffect(() => {
    if (sent.current === data) return;
    sent.current = data;
    (map.getSource(source) as GeoJSONSource | undefined)?.setData(data);
  }, [map, source, data]);
  return null;
}
