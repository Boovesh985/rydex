import type { Feature, FeatureCollection } from 'geojson';
import * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
// MapLibre parses tiles in a web worker; Vite builds it (with its shared chunk) and hands over the URL
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import { createContext, useContext, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import type { RideType } from '../api/types';
import { config } from '../config';
import { bearing, haversine, lerp, lerpHeading, metersPerPixel, PathIndex, type LatLng } from '../lib/geo';
import { decodePolyline } from '../lib/polyline';
import { topDownSvg, vehicleKind } from '../lib/vehicles';
import { Atmosphere } from './Atmosphere';
import type { CarLayer, CarPose } from './car3d';
import { cameraBus, dampAngle, FollowCamera } from './followCamera';
import { useMapPrefs, useNight } from './mapPrefs';
import { MapPrefsControl } from './MapPrefsControl';
import type { SceneryLayer } from './scenery';
import { applyLook, BUILDINGS_3D, ROUTE_COLORS, STYLE_URL, worldWidth, type Look } from './theme';
import { TrafficLayer } from './TrafficLayer';

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

/** 2D maps stop at 20; realistic mode goes down to street level, where cars and roads are life-size. */
const MAX_ZOOM_2D = 20;
const MAX_ZOOM_3D = 23;

/** Realistic mode's free camera (no car followed): the tilt presets the view button steps through. */
const FREE_TILTS = [72, 50, 0];

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
  /** the night look; by default the viewer's preference (auto by the clock) */
  night?: boolean;
}

/** Full-bleed map; zoom/compass buttons sit bottom-right, clear of the sheets. */
export function BaseMap({
  center = [config.center.lat, config.center.lng],
  zoom = 14,
  children,
  className,
  realistic = false,
  night: nightProp,
}: BaseMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const [map, setMap] = useState<MlMap | null>(null);
  const [failed, setFailed] = useState(() => !supportsWebGL());
  const [cars, setCars] = useState<CarLayer | null>(null);
  const [scenery, setScenery] = useState<SceneryLayer | null>(null);
  const preferredNight = useNight();
  const night = nightProp ?? preferredNight;
  const traffic = useMapPrefs((s) => s.traffic);
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
        // realistic mode's chase camera tilts far enough to see the sky
        maxPitch: 80,
        maxZoom: MAX_ZOOM_2D,
        // past 2x the extra pixels cost frame rate and show nothing new
        pixelRatio: Math.min(window.devicePixelRatio || 1, 2),
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
    map.setMaxZoom(realistic ? MAX_ZOOM_3D : MAX_ZOOM_2D);
    map.getContainer().classList.toggle('rx-realistic', realistic);
    if (previous && previous.realistic !== realistic && !followerRef.current) {
      const duration = reducedMotion() ? 0 : 1400;
      // a game-like view: low over the street, the centre pushed down so the horizon and sky show
      const top = Math.round(map.getContainer().clientHeight * 0.3);
      if (realistic)
        map.easeTo({
          pitch: 72,
          bearing: map.getBearing() || -18,
          zoom: Math.max(map.getZoom(), 15.6),
          padding: { top, bottom: 0, left: 0, right: 0 },
          duration,
        });
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

  // trees and street lights, also only in realistic mode
  useEffect(() => {
    if (!map || !realistic) return;
    let layer: SceneryLayer | null = null;
    let cancelled = false;
    void import('./scenery').then(({ createSceneryLayer }) => {
      if (cancelled || !map.getStyle()) return;
      layer = createSceneryLayer();
      // under the labels, with the buildings (they share the depth buffer, so walls hide trees)
      map.addLayer(layer.layer, map.getStyle().layers?.find((l) => l.type === 'symbol')?.id);
      setScenery(layer);
    });
    return () => {
      cancelled = true;
      if (layer && map.getStyle() && map.getLayer(layer.layer.id)) map.removeLayer(layer.layer.id);
      setScenery(null);
    };
  }, [map, realistic]);

  useEffect(() => scenery?.setNight(night), [scenery, night]);

  // the HUD's zoom and view buttons, while no car is followed (the follow camera handles its own)
  useEffect(() => {
    if (!map) return;
    let tilt = 0;
    return cameraBus.listen((c) => {
      if (followerRef.current) return;
      const duration = reducedMotion() ? 0 : 450;
      if (c.type === 'zoom') map.easeTo({ zoom: map.getZoom() + c.by, duration });
      else {
        tilt = (tilt + 1) % FREE_TILTS.length;
        const pitch = FREE_TILTS[tilt];
        map.easeTo({ pitch, bearing: pitch === 0 ? 0 : map.getBearing(), duration: duration * 2 });
      }
    });
  }, [map]);

  const state = useMemo(() => (map ? { map, realistic, night, cars: realistic ? cars : null, followerRef } : null), [map, realistic, night, cars]);

  return (
    <div className={`relative ${className ?? 'h-full w-full'}`} style={realistic ? { background: night ? '#141820' : '#e3dfd3' } : undefined}>
      {/* the sky sits behind the (see-through above the horizon) map, so whatever stands up covers it */}
      {map && realistic && <Atmosphere map={map} night={night} />}
      <div ref={container} className="absolute inset-0 h-full w-full" data-testid="map-canvas" />
      {failed && (
        <div className="absolute inset-0 grid place-items-center bg-mist p-6 text-center text-sm text-slate">
          This browser cannot show the map (WebGL is turned off). Everything else still works.
        </div>
      )}
      {map && <MapPrefsControl map={map} />}
      {map && traffic && <TrafficLayer map={map} night={night} />}
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
      pitch: realistic ? 60 : 0,
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

/** A top-down car, bike or auto (flat on the road in 3D), with a soft shadow. */
function carElement(type: RideType | null | undefined, size: number, label: string): HTMLElement {
  const el = document.createElement('div');
  el.className = 'rx-car';
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', label);
  el.title = label;
  el.innerHTML = topDownSvg(type, size);
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
  /** how long to glide to each new fix until the real interval is known; then it follows the fixes' pace */
  glideMs?: number;
  /** the road ahead: fixes near it are snapped and the car follows its curves between them */
  route?: LatLng[] | null;
  type?: RideType | null;
  /** m/s */
  speed?: number | null;
  /** realistic mode: the chase camera rides behind the car */
  follow?: boolean;
  /** a rider on board (a bike's pillion, an auto's passengers are drawn) */
  carrying?: boolean;
  label?: string;
}

/**
 * The live car. Fixes arrive every ~3 s; it glides from where it is towards each new fix, along
 * the route when the fix is on it (so it takes the corners), and turns the short way round. The
 * glide lasts a little longer than the fixes' measured interval, so the car keeps rolling instead
 * of stopping to wait for a late fix. In realistic mode it is a 3D model and the chase camera
 * ({@link FollowCamera}) can ride behind it.
 */
export function AnimatedCar({
  lat,
  lng,
  heading,
  glideMs = 3100,
  route,
  type = null,
  speed = null,
  follow = false,
  carrying = false,
  label = 'Your driver',
}: AnimatedCarProps) {
  const { map, realistic, cars, followerRef } = useMapState();
  const id = useId();
  const shown = useRef<Pose | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const shownAlong = useRef<{ index: PathIndex; along: number } | null>(null);
  const lastFix = useRef<number | null>(null);
  const fixInterval = useRef<number | null>(null);
  const live = useRef({ cars, type, speed, carrying });
  useEffect(() => {
    live.current = { cars, type, speed, carrying };
  });
  const index = useMemo(() => (route && route.length > 1 ? new PathIndex(route) : null), [route]);

  const draw = (pose: Pose) => {
    const { cars: layer, type: t, speed: v, carrying: aboard } = live.current;
    if (layer) layer.set(id, { lat: pose.lat, lng: pose.lng, heading: pose.heading, speed: v ?? 0, type: t, focus: true, carrying: aboard } satisfies CarPose);
    else if (marker.current) {
      marker.current.setLngLat([pose.lng, pose.lat]);
      marker.current.setRotation(pose.heading);
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

  // the chase camera: it owns the camera (and the gestures) while it follows
  useEffect(() => {
    const active = realistic && follow;
    followerRef.current = active;
    if (!active) return;
    const camera = new FollowCamera(
      map,
      () => {
        const pose = shown.current ?? { lat, lng, heading: heading ?? 0 };
        return { ...pose, speed: live.current.speed ?? 0 };
      },
      // a bike or an auto is smaller than the car the views are tuned for: ride in closer
      { reducedMotion: reducedMotion(), zoomBias: { bike: 0.8, auto: 0.4, car: 0 }[vehicleKind(live.current.type)] },
    );
    return () => {
      camera.dispose();
      followerRef.current = false;
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
    // pace the glide by how often fixes really come, so the car neither stalls nor rushes
    if (lastFix.current != null) {
      const gap = start - lastFix.current;
      if (gap > 400 && gap < 15_000) fixInterval.current = fixInterval.current == null ? gap : fixInterval.current * 0.7 + gap * 0.3;
    }
    lastFix.current = start;
    const duration = fixInterval.current == null ? glideMs : Math.min(8000, Math.max(1000, fixInterval.current * 1.08));
    let frame = 0;
    let last = start;
    const step = (now: number) => {
      const t = reduced ? 1 : Math.min(1, (now - start) / duration);
      const dt = Math.max(0, (now - last) / 1000);
      last = now;
      const next = position(t);
      const prev = shown.current ?? next;
      // ease the heading round corners instead of snapping at every polyline vertex
      const pose = { ...next, heading: reduced ? next.heading : dampAngle(prev.heading, next.heading, 0.16, dt) };
      shown.current = pose;
      draw(pose);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    // background tabs pause animation frames: land on the fix anyway
    const snap = setTimeout(() => step(start + duration), duration + 250);
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
function planGlide(
  from: Pose,
  to: LatLng,
  heading: number | null,
  index: PathIndex | null,
  hint: number | null,
): { position: (t: number) => Pose; endAlong: number | null } {
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
export function RouteLine({
  polyline,
  fallback,
  progress,
  tone = 'route',
}: {
  polyline: string | null | undefined;
  fallback?: LatLng[];
  progress?: number | null;
  /** "trail": the path actually driven, solid yellow */ tone?: 'route' | 'trail';
}) {
  const { map, night, realistic } = useMapState();
  const id = `rx-route-${useId().replace(/:/g, '')}`;
  const points = useMemo(() => (polyline ? decodePolyline(polyline) : (fallback ?? [])), [polyline, fallback]);
  const dashed = !polyline && tone === 'route';
  const colors = tone === 'trail' ? ROUTE_COLORS.trail : night ? ROUTE_COLORS.night : ROUTE_COLORS.day;
  const data = useMemo<Feature | FeatureCollection>(
    () =>
      points.length < 2 ? emptyLine : { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: points.map(([la, ln]) => [ln, la]) } },
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
    // pixels when zoomed out; at street level a stripe of real width down the lane (metres): the road
    // ahead fills the lane, the path already driven stays a thin trace
    const trail = tone === 'trail';
    const width = (base: number, metres: number) =>
      worldWidth(
        [
          [11, base * 0.45],
          [15, base],
          [18, base * 2.2],
        ],
        trail ? metres * 0.35 : metres,
      );
    map.addLayer(
      {
        id: `${id}-glow`,
        type: 'line',
        source: id,
        layout: { 'line-cap': 'round', 'line-join': 'round', visibility: 'none' },
        paint: { 'line-width': width(16, 5), 'line-blur': ['interpolate', ['linear'], ['zoom'], 15, 10, 21, 30], 'line-opacity': 0.4 },
      },
      before,
    );
    map.addLayer(
      {
        id: `${id}-casing`,
        type: 'line',
        source: id,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-width': width(8.5, 3.6), 'line-opacity': 0.9 },
      },
      before,
    );
    map.addLayer(
      { id: `${id}-line`, type: 'line', source: id, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-width': width(4.5, 2.6) } },
      before,
    );
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
    map.setLayoutProperty(`${id}-glow`, 'visibility', (realistic || night) && tone !== 'trail' ? 'visible' : 'none');
    map.setPaintProperty(`${id}-casing`, 'line-dasharray', dashed ? [0.4, 1.6] : undefined);
    map.setPaintProperty(`${id}-line`, 'line-dasharray', dashed ? [0.4, 1.6] : undefined);
    const p = dashed || progress == null ? 0 : Math.min(0.999, Math.max(0, progress));
    if (dashed) {
      map.setPaintProperty(`${id}-line`, 'line-gradient', undefined);
      map.setPaintProperty(`${id}-line`, 'line-color', colors.line);
    } else {
      map.setPaintProperty(
        `${id}-line`,
        'line-gradient',
        p > 0 ? ['step', ['line-progress'], colors.done, p, colors.line] : ['step', ['line-progress'], colors.line, 1, colors.line],
      );
    }
  }, [map, id, colors, dashed, progress, realistic, night, tone]);

  return null;
}

const emptyLine: FeatureCollection = { type: 'FeatureCollection', features: [] };

// ------------------------------------------------------------------ admin layers

/**
 * Holds on to the realistic camera while `holding` (a car is being ridden along with) and eases
 * back to it when that ends, so closing a ride-along returns to the view it started from.
 */
export function RememberView({ holding }: { holding: boolean }) {
  const { map, realistic } = useMapState();
  const saved = useRef<{ center: maplibregl.LngLat; zoom: number; pitch: number; bearing: number } | null>(null);
  useEffect(() => {
    if (!realistic) {
      saved.current = null;
    } else if (holding) {
      saved.current ??= { center: map.getCenter(), zoom: map.getZoom(), pitch: map.getPitch(), bearing: map.getBearing() };
    } else if (saved.current) {
      map.easeTo({ ...saved.current, padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: reducedMotion() ? 0 : 1400 });
      saved.current = null;
    }
  }, [map, realistic, holding]);
  return null;
}

export type CarDuty = 'free' | 'pickup' | 'trip';

export interface FleetCar {
  id: string;
  lat: number;
  lng: number;
  heading: number | null;
  type: RideType | null;
  /** free, driving to a rider's pickup, or carrying a rider */
  duty: CarDuty;
  label: string;
  /** m/s, when the driver reports it */
  speed?: number | null;
}

/**
 * Every live driver: small car markers in 2D, 3D models in realistic mode; they glide between the
 * 3-second updates. Markers move on the main thread: re-sending a GeoJSON source every frame would
 * keep MapLibre's (single) tile worker busy and stall tile loading.
 */
export function Fleet({
  cars: all,
  glideMs = 2800,
  hide = null,
  onSelect,
}: {
  cars: FleetCar[];
  glideMs?: number;
  /** a car drawn elsewhere (the one being ridden along with) */
  hide?: string | null;
  /** a car was clicked: its marker in 2D, the model in realistic mode */
  onSelect?: (id: string) => void;
}) {
  const { map, cars: layer } = useMapState();
  const fleet = useMemo(() => (hide ? all.filter((c) => c.id !== hide) : all), [all, hide]);
  const select = useRef(onSelect);
  useEffect(() => {
    select.current = onSelect;
  });
  const selectable = !!onSelect;
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
        const look = `${car.type}:${car.duty}`;
        const existing = markers.current.get(car.id);
        if (existing && existing.look === look) continue;
        existing?.marker.remove();
        const el = carElement(car.type, 28, car.label);
        el.classList.add(`rx-car-${car.duty}`);
        if (selectable) {
          el.style.cursor = 'pointer';
          el.addEventListener('click', (e) => {
            e.stopPropagation();
            select.current?.(car.id);
          });
        }
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
          layer.set(car.id, { ...pose, speed: car.duty === 'free' ? 4 : 9, type: car.type, duty: car.duty, carrying: car.duty === 'trip' });
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
  }, [map, fleet, glideMs, layer, selectable]);

  // realistic mode: a click picks the nearest model under the pointer (they are drawn by three.js,
  // so there is no element to click); the pointer turns into a hand over one
  useEffect(() => {
    if (!layer || !selectable) return;
    const canvas = map.getCanvas();
    const under = (point: { x: number; y: number }): string | null => {
      const zoom = map.getZoom();
      const lift = Math.sin((map.getPitch() * Math.PI) / 180);
      let best: string | null = null;
      let bestD = Infinity;
      for (const [id, pose] of shown.current) {
        const at = map.project([pose.lng, pose.lat]);
        // fleet models are never shorter than 34 px; the body stands up off the road
        const size = Math.max(34, 4.4 / metersPerPixel(zoom, pose.lat));
        const d = Math.hypot(point.x - at.x, point.y - (at.y - size * 0.3 * lift));
        if (d < Math.max(22, size * 0.6) && d < bestD) {
          best = id;
          bestD = d;
        }
      }
      return best;
    };
    const click = (e: maplibregl.MapMouseEvent) => {
      const id = under(e.point);
      if (id) select.current?.(id);
    };
    let pending = 0;
    const move = (e: maplibregl.MapMouseEvent) => {
      if (pending) return;
      const point = e.point;
      pending = requestAnimationFrame(() => {
        pending = 0;
        canvas.style.cursor = under(point) ? 'pointer' : '';
      });
    };
    map.on('click', click);
    map.on('mousemove', move);
    return () => {
      map.off('click', click);
      map.off('mousemove', move);
      cancelAnimationFrame(pending);
      canvas.style.cursor = '';
    };
  }, [map, layer, selectable]);

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
      features: cells.map((c) => ({
        type: 'Feature',
        properties: { weight: c.requests / max, requests: c.requests },
        geometry: { type: 'Point', coordinates: [c.lng, c.lat] },
      })),
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
          'heatmap-color': [
            'interpolate',
            ['linear'],
            ['heatmap-density'],
            0,
            'rgba(255,194,26,0)',
            0.25,
            'rgba(255,194,26,0.45)',
            0.6,
            'rgba(240,120,40,0.7)',
            1,
            'rgba(215,54,43,0.85)',
          ],
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
          geometry: {
            type: 'Polygon',
            coordinates: [
              [
                [w, s],
                [e, s],
                [e, n],
                [w, n],
                [w, s],
              ],
            ],
          },
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
    map.addLayer(
      { id: `${source}-fill`, type: 'fill', source, paint: { 'fill-color': '#16181d', 'fill-opacity': ['case', ['get', 'center'], 0.08, 0.03] } },
      before,
    );
    map.addLayer(
      { id: `${source}-line`, type: 'line', source, paint: { 'line-width': ['case', ['get', 'center'], 2.5, 1.2], 'line-dasharray': [3, 2] } },
      before,
    );
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

// ------------------------------------------------------------------ the living city (admin)

export type RiderState = 'searching' | 'waiting' | 'boarding';
const RIDER_STATES: RiderState[] = ['searching', 'waiting', 'boarding'];

export interface WaitingRider {
  /** the ride */
  id: string;
  lat: number;
  lng: number;
  state: RiderState;
  label: string;
}

const RIDER_GLYPH =
  '<svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="4.6" r="3.1"/><path d="M2.2 15.2c0-3.4 2.6-5.8 5.8-5.8s5.8 2.4 5.8 5.8z"/></svg>';

/** Riders standing at their pickups: still searching (pulsing), a driver on the way, boarding. */
export function RiderMarkers({ riders, onSelect }: { riders: WaitingRider[]; onSelect?: (rideId: string) => void }) {
  const { map } = useMapState();
  const markers = useRef(new Map<string, { marker: maplibregl.Marker; el: HTMLElement }>());
  const select = useRef(onSelect);
  useEffect(() => {
    select.current = onSelect;
  }, [onSelect]);

  useEffect(() => {
    const all = markers.current;
    return () => {
      for (const { marker } of all.values()) marker.remove();
      all.clear();
    };
  }, [map]);

  useEffect(() => {
    const ids = new Set(riders.map((r) => r.id));
    for (const [id, { marker }] of markers.current) {
      if (!ids.has(id)) {
        marker.remove();
        markers.current.delete(id);
      }
    }
    for (const r of riders) {
      let entry = markers.current.get(r.id);
      if (!entry) {
        const el = document.createElement('button');
        el.type = 'button';
        el.className = 'rx-rider';
        el.innerHTML = RIDER_GLYPH;
        el.addEventListener('click', (e) => {
          e.stopPropagation();
          select.current?.(r.id);
        });
        entry = { marker: new maplibregl.Marker({ element: el }).setLngLat([r.lng, r.lat]).addTo(map), el };
        markers.current.set(r.id, entry);
      }
      // toggle the state classes only: replacing className would strip MapLibre's own marker classes
      // (position: absolute), and every marker would fall into a column down the page
      for (const state of RIDER_STATES) entry.el.classList.toggle(`rx-rider-${state}`, r.state === state);
      entry.el.setAttribute('aria-label', r.label);
      entry.el.title = r.label;
      entry.marker.setLngLat([r.lng, r.lat]);
    }
  }, [map, riders]);

  return null;
}

export interface DispatchLine {
  id: string;
  from: LatLng;
  to: LatLng;
  /** a car heading to its rider, or carrying them to the drop */
  kind: 'pickup' | 'trip';
}

/** Who is heading where: dashed lines from each busy car to its pickup (amber) or drop (teal). */
export function DispatchLines({ lines }: { lines: DispatchLine[] }) {
  const { map } = useMapState();
  const id = `rx-dispatch-${useId().replace(/:/g, '')}`;
  const data = useMemo<FeatureCollection>(
    () => ({
      type: 'FeatureCollection',
      features: lines.map((l) => ({
        type: 'Feature',
        properties: { kind: l.kind },
        geometry: {
          type: 'LineString',
          coordinates: [
            [l.from[1], l.from[0]],
            [l.to[1], l.to[0]],
          ],
        },
      })),
    }),
    [lines],
  );
  const sent = useRef<unknown>(null);
  useEffect(() => {
    map.addSource(id, { type: 'geojson', data });
    sent.current = data;
    map.addLayer(
      {
        id: `${id}-line`,
        type: 'line',
        source: id,
        layout: { 'line-cap': 'round' },
        paint: {
          'line-color': ['match', ['get', 'kind'], 'pickup', '#f08c00', '#0b6e79'],
          'line-width': ['interpolate', ['linear'], ['zoom'], 11, 1.2, 16, 2.6],
          'line-dasharray': [1.5, 2],
          'line-opacity': ['match', ['get', 'kind'], 'pickup', 0.9, 0.55],
        },
      },
      map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : undefined,
    );
    return () => {
      if (!map.getStyle()) return;
      if (map.getLayer(`${id}-line`)) map.removeLayer(`${id}-line`);
      if (map.getSource(id)) map.removeSource(id);
    };
  }, [map, id]); // eslint-disable-line react-hooks/exhaustive-deps -- later data goes through setData below
  useEffect(() => {
    if (sent.current === data) return;
    sent.current = data;
    (map.getSource(id) as GeoJSONSource | undefined)?.setData(data);
  }, [map, id, data]);
  return null;
}
