import type { ExpressionSpecification, Map as MlMap } from 'maplibre-gl';
import { lightPosition, skyLight } from './sky';

/**
 * The base map: OpenFreeMap's "Positron" vector style (OpenStreetMap data, free, no key), recoloured
 * at runtime to the Rydex palette. "Realistic" adds extruded 3D buildings, a sky with fog and a
 * sun (or moon) light from where it really is over Chennai; night swaps every colour for a dark city.
 */
export const STYLE_URL = 'https://tiles.openfreemap.org/styles/positron';
export interface Look {
  realistic: boolean;
  night: boolean;
}

interface Palette {
  land: string;
  residential: string;
  park: string;
  wood: string;
  water: string;
  waterway: string;
  building: string;
  buildingLine: string;
  /** extruded buildings: low, then tall */
  tower: [string, string];
  minor: string;
  path: string;
  majorInner: string;
  casing: string;
  motorway: string;
  rail: string;
  label: string;
  labelHalo: string;
  waterLabel: string;
  sky: string;
  horizon: string;
  light: { color: string; intensity: number };
}

const DAY: Palette = {
  land: '#eceee6',
  residential: '#e7e9e1',
  park: '#d7e6cd',
  wood: '#cfe0c4',
  water: '#a9d3da',
  waterway: '#94c6ce',
  building: '#e0ded5',
  buildingLine: '#d2d0c6',
  tower: ['#d8d2c4', '#b9c2cc'],
  minor: '#ffffff',
  path: '#f6f6f2',
  majorInner: '#ffffff',
  casing: '#d3d6cc',
  motorway: '#ffe39a',
  rail: '#cfd2c8',
  label: '#4a4f57',
  labelHalo: 'rgba(255,255,255,0.85)',
  waterLabel: '#3f6f78',
  sky: '#4f9bd9',
  horizon: '#cfe5f3',
  light: { color: '#fff8e8', intensity: 0.55 },
};

const NIGHT: Palette = {
  land: '#141820',
  residential: '#171b24',
  park: '#16241d',
  wood: '#15231b',
  water: '#0b2630',
  waterway: '#0f3340',
  building: '#1e232c',
  buildingLine: '#272d38',
  tower: ['#1c222c', '#28303c'],
  minor: '#2a303b',
  path: '#222731',
  majorInner: '#3a4250',
  casing: '#0d1015',
  motorway: '#8a6a22',
  rail: '#2c323d',
  label: '#a9b2bd',
  labelHalo: 'rgba(10,12,16,0.85)',
  waterLabel: '#5ea5b3',
  sky: '#0a1022',
  horizon: '#2b2a48',
  light: { color: '#3b4660', intensity: 0.3 },
};

/**
 * Realistic mode at street level (ADR 0017): asphalt roads with pale pavements instead of the
 * clean map's white ribbons, and greener parks.
 */
const STREET: Record<'day' | 'night', Partial<Palette>> = {
  day: {
    land: '#e3dfd3',
    residential: '#dfdbcf',
    minor: '#a9adb1',
    majorInner: '#9a9fa5',
    motorway: '#959aa0',
    casing: '#d2cfc6',
    path: '#d6d2c7',
    park: '#bcd6a6',
    wood: '#b0cf99',
    water: '#8cc3cf',
  },
  night: { minor: '#2b313b', majorInner: '#30363f', motorway: '#343a43', casing: '#3a404a', path: '#2d333c' },
};

/**
 * Walls of low buildings, one per building (stable as tiles reload): Chennai's creams, sands and
 * pastels. Set once: a data-driven colour that changed with day and night would make MapLibre
 * re-parse every tile. Night darkens them through the light instead.
 */
const FACADES = ['#ece3cd', '#e3d2bd', '#f0e7d2', '#dccdb4', '#e6ddd3', '#d6dcdf', '#e8dcc0', '#dfe3d4'];
const TOWER = '#b9c2cc';

/** Realistic mode's sky, drawn behind the map (see Atmosphere): top and horizon colours. */
export function skyColors(night: boolean): { sky: string; horizon: string } {
  const p = night ? NIGHT : DAY;
  return { sky: p.sky, horizon: p.horizon };
}

export const BUILDINGS_3D = 'rx-buildings-3d';
export const LANES = 'rx-lanes';

/** Pixels per metre at zoom 0 in Chennai (about 13 degrees north). */
const PX_PER_METRE_Z0 = 1 / (156543.03 * Math.cos((13.06 * Math.PI) / 180));

/**
 * A line width that is `stops` (zoom, pixels) when zoomed out and `metres` wide on the ground at
 * street level: doubling with every zoom level, as the ground does.
 */
export function worldWidth(stops: [number, number][], metres: number): ExpressionSpecification {
  const z24 = metres * 2 ** 24 * PX_PER_METRE_Z0;
  const flat = stops.filter(([z]) => z < 24).flatMap(([z, w]) => [z, Math.min(w, z24)]);
  return ['interpolate', ['exponential', 2], ['zoom'], ...flat, 24, z24] as ExpressionSpecification;
}

/** Real widths (metres, one carriageway with its pavements for the casings) of the base map's lines. */
const ROAD_METRES: Record<string, number> = {
  highway_minor: 6.5,
  highway_major_inner: 12,
  highway_major_casing: 17,
  highway_motorway_inner: 14,
  highway_motorway_casing: 19,
  highway_motorway_bridge_inner: 14,
  highway_motorway_bridge_casing: 20,
  tunnel_motorway_inner: 14,
  tunnel_motorway_casing: 19,
  highway_path: 2.2,
  road_pier: 3,
  railway: 3.2,
  railway_dashline: 2.6,
  railway_transit: 3.2,
  railway_transit_dashline: 2.6,
  railway_service: 3,
  railway_service_dashline: 2.4,
};

/** A zoom-only width expression's value at `zoom` (the style's simple interpolate form), or null. */
export function widthAt(value: unknown, zoom: number): number | null {
  if (typeof value === 'number') return value;
  if (!Array.isArray(value) || value[0] !== 'interpolate' || !Array.isArray(value[2]) || value[2][0] !== 'zoom') return null;
  const kind = value[1] as [string, number?];
  const base = kind[0] === 'exponential' ? (kind[1] ?? 1) : 1;
  const stops: [number, number][] = [];
  for (let i = 3; i + 1 < value.length; i += 2) {
    if (typeof value[i] !== 'number' || typeof value[i + 1] !== 'number') return null;
    stops.push([value[i] as number, value[i + 1] as number]);
  }
  if (stops.length === 0) return null;
  if (zoom <= stops[0][0]) return stops[0][1];
  for (let i = 1; i < stops.length; i++) {
    const [z0, v0] = stops[i - 1];
    const [z1, v1] = stops[i];
    if (zoom <= z1) {
      const t = base === 1 ? (zoom - z0) / (z1 - z0) : (base ** (zoom - z0) - 1) / (base ** (z1 - z0) - 1);
      return v0 + (v1 - v0) * t;
    }
  }
  return stops[stops.length - 1][1];
}

const originalWidths = new WeakMap<MlMap, Map<string, unknown>>();

/**
 * Street level needs real road widths: the base style's roads stop widening at zoom 20, so a
 * life-size car would be wider than the street. Zoom-only expressions, so no tile is re-parsed.
 */
function setRoadWidths(map: MlMap, realistic: boolean) {
  let saved = originalWidths.get(map);
  if (!saved) originalWidths.set(map, (saved = new Map()));
  for (const [id, metres] of Object.entries(ROAD_METRES)) {
    if (!map.getLayer(id)) continue;
    if (!saved.has(id)) saved.set(id, map.getPaintProperty(id, 'line-width'));
    const original = saved.get(id);
    if (!realistic) {
      map.setPaintProperty(id, 'line-width', original as never);
      continue;
    }
    const stops = [12, 14, 16].map((z) => [z, widthAt(original, z)] as [number, number | null]).filter((s): s is [number, number] => s[1] != null);
    map.setPaintProperty(id, 'line-width', worldWidth(stops.length ? stops : [[16, 2]], metres));
  }
}

/** Dashed lane lines down the main roads, at street level in realistic mode. */
function ensureLanes(map: MlMap, night: boolean, realistic: boolean) {
  if (!map.getSource('openmaptiles')) return;
  if (!map.getLayer(LANES)) {
    map.addLayer(
      {
        id: LANES,
        type: 'line',
        source: 'openmaptiles',
        'source-layer': 'transportation',
        minzoom: 17,
        filter: [
          'all',
          ['match', ['geometry-type'], ['LineString', 'MultiLineString'], true, false],
          ['match', ['get', 'class'], ['motorway', 'trunk', 'primary', 'secondary', 'tertiary'], true, false],
          ['!=', ['get', 'brunnel'], 'tunnel'],
        ],
        layout: { visibility: 'none', 'line-cap': 'butt', 'line-join': 'round' },
        // 15 cm paint, 3 m dashes with 4.5 m gaps (dashes are measured in line widths)
        paint: {
          'line-width': worldWidth([[17, 0.5]], 0.15),
          'line-dasharray': [20, 30],
          'line-opacity': ['interpolate', ['linear'], ['zoom'], 17, 0, 18, 0.85],
        },
      },
      map.getLayer(BUILDINGS_3D) ? BUILDINGS_3D : firstSymbolLayer(map),
    );
  }
  map.setPaintProperty(LANES, 'line-color', night ? '#8b939e' : '#f7f7f2');
  map.setLayoutProperty(LANES, 'visibility', realistic ? 'visible' : 'none');
}

/** One colour per base-style layer id (Positron's ids), or by pattern for the road families. */
function colorsFor(p: Palette): [RegExp, Record<string, string>][] {
  return [
    [/^background$/, { 'background-color': p.land }],
    [/^park$/, { 'fill-color': p.park }],
    [/^landcover_wood$/, { 'fill-color': p.wood }],
    [/^landuse_residential$/, { 'fill-color': p.residential }],
    [/^water$/, { 'fill-color': p.water }],
    [/^waterway$/, { 'line-color': p.waterway }],
    [/^building$/, { 'fill-color': p.building, 'fill-outline-color': p.buildingLine }],
    [/^(highway_path|road_pier)$/, { 'line-color': p.path }],
    [/^highway_minor$/, { 'line-color': p.minor }],
    [/casing$/, { 'line-color': p.casing }],
    [/^highway_major_(inner|subtle)$/, { 'line-color': p.majorInner }],
    [/^(highway_motorway_(inner|subtle)|highway_motorway_bridge_inner|tunnel_motorway_inner)$/, { 'line-color': p.motorway }],
    [/^railway/, { 'line-color': p.rail }],
    [/^road_area_pier$/, { 'fill-color': p.land }],
    [/^water_name/, { 'text-color': p.waterLabel, 'text-halo-color': p.labelHalo }],
    [/^(label_|highway-name|airport|waterway_line_label)/, { 'text-color': p.label, 'text-halo-color': p.labelHalo }],
  ];
}

function recolor(map: MlMap, p: Palette) {
  const rules = colorsFor(p);
  for (const layer of map.getStyle().layers ?? []) {
    if (layer.id.startsWith('rx-')) continue;
    const rule = rules.find(([re]) => re.test(layer.id));
    if (!rule) continue;
    for (const [prop, value] of Object.entries(rule[1])) {
      try {
        map.setPaintProperty(layer.id, prop as never, value as never);
      } catch {
        // a property this layer type does not have
      }
    }
  }
}

/** The first label layer: our layers go under the labels so street names stay readable. */
export function firstSymbolLayer(map: MlMap): string | undefined {
  return map.getStyle().layers?.find((l) => l.type === 'symbol')?.id;
}

function ensureBuildings(map: MlMap) {
  if (!map.getLayer(BUILDINGS_3D)) {
    // low buildings get one of the facade colours; towers turn glassy
    const height: ExpressionSpecification = ['coalesce', ['get', 'render_height'], 6];
    const pick: ExpressionSpecification = ['%', ['+', ['to-number', ['id'], 0], ['floor', ['*', height, 3.7]]], FACADES.length];
    const facade = ['match', pick, ...FACADES.slice(0, -1).flatMap((c, i) => [i, c]), FACADES[FACADES.length - 1]] as ExpressionSpecification;
    map.addLayer(
      {
        id: BUILDINGS_3D,
        type: 'fill-extrusion',
        source: 'openmaptiles',
        'source-layer': 'building',
        minzoom: 13,
        layout: { visibility: 'none' },
        paint: {
          'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 6],
          'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
          'fill-extrusion-opacity': 0,
          'fill-extrusion-opacity-transition': { duration: 900, delay: 0 },
          'fill-extrusion-vertical-gradient': true,
          'fill-extrusion-color': ['interpolate', ['linear'], height, 0, facade, 18, facade, 60, TOWER],
        },
      },
      firstSymbolLayer(map),
    );
  }
}

const hiding = new WeakMap<MlMap, ReturnType<typeof setTimeout>>();

/**
 * Buildings fade in (or out) over ~0.9 s with a paint transition. Only plain paint properties may
 * change here: changing a data-driven one (e.g. the height) makes MapLibre re-process every tile
 * in its worker, which stalls tile loading.
 */
function showBuildings(map: MlMap, show: boolean) {
  clearTimeout(hiding.get(map));
  if (show) {
    map.setLayoutProperty(BUILDINGS_3D, 'visibility', 'visible');
    map.setPaintProperty(BUILDINGS_3D, 'fill-extrusion-opacity', 0.92);
  } else {
    map.setPaintProperty(BUILDINGS_3D, 'fill-extrusion-opacity', 0);
    hiding.set(
      map,
      setTimeout(() => map.getLayer(BUILDINGS_3D) && map.setLayoutProperty(BUILDINGS_3D, 'visibility', 'none'), 950),
    );
  }
}

/** Applies the palette and, for realistic mode, the 3D buildings, sky and light. Safe to call repeatedly. */
export function applyLook(map: MlMap, look: Look, previous?: Look) {
  const base = look.night ? NIGHT : DAY;
  const p = look.realistic ? { ...base, ...STREET[look.night ? 'night' : 'day'] } : base;
  recolor(map, p);
  ensureBuildings(map);
  ensureLanes(map, look.night, look.realistic);
  if (!previous || previous.realistic !== look.realistic) {
    showBuildings(map, look.realistic);
    setRoadWidths(map, look.realistic);
    if (map.getLayer('building')) map.setLayoutProperty('building', 'visibility', look.realistic ? 'none' : 'visible');
  }
  // the sun (or moon) where it is over Chennai now: buildings are lit from that side
  map.setLight({ anchor: 'map', color: p.light.color, intensity: p.light.intensity, position: lightPosition(skyLight(new Date(), look.night)) });
  // realistic: the map's own sky is see-through and the sky is drawn behind the canvas, so trees,
  // lamps and buildings standing above the horizon cover the clouds instead of the other way round;
  // the ground fades into the sky's horizon colour
  map.setSky(
    look.realistic
      ? {
          'sky-color': 'rgba(0,0,0,0)',
          'horizon-color': 'rgba(0,0,0,0)',
          'fog-color': p.horizon,
          'sky-horizon-blend': 0,
          'horizon-fog-blend': 0.6,
          'fog-ground-blend': 0.7,
          'atmosphere-blend': 0,
        }
      : { 'sky-color': p.land, 'horizon-color': p.land, 'fog-color': p.land, 'fog-ground-blend': 1, 'atmosphere-blend': 0 },
  );
  map.getContainer().style.background = look.realistic ? 'transparent' : p.land;
}

/** When the maps' automatic look turns to day (6 am) and to night (6 pm), in the city's time. */
export const DAY_STARTS = 6;
export const NIGHT_STARTS = 18;

/**
 * The city's clock (Chennai: India Standard Time, UTC+5:30, no daylight saving), so the map shows
 * Chennai's day or night to every viewer, wherever their own computer's clock is set.
 */
const CITY_UTC_OFFSET_MS = 330 * 60_000;

/** The hour (0-23) in the city at this moment. */
export function cityHour(at: Date | number = new Date()): number {
  return new Date(+at + CITY_UTC_OFFSET_MS).getUTCHours();
}

/** Night by the city's clock: 6 pm to 6 am. */
export function isNightNow(date: Date | number = new Date()): boolean {
  const h = cityHour(date);
  return h >= NIGHT_STARTS || h < DAY_STARTS;
}

/** The next 6 am or 6 pm in the city after `date`, when the automatic look changes (ms since the epoch). */
export function nextDayNightSwitch(date: Date | number = new Date()): number {
  // work on the city's wall clock as if it were UTC, then shift back
  const city = new Date(+date + CITY_UTC_OFFSET_MS);
  const h = city.getUTCHours();
  city.setUTCMinutes(0, 0, 0);
  if (h < DAY_STARTS) city.setUTCHours(DAY_STARTS);
  else if (h < NIGHT_STARTS) city.setUTCHours(NIGHT_STARTS);
  else city.setUTCHours(24 + DAY_STARTS);
  return city.getTime() - CITY_UTC_OFFSET_MS;
}

export const ROUTE_COLORS = {
  day: { line: '#0b6e79', casing: '#16181d', done: '#9aa6ab', glow: '#3fb6c2' },
  night: { line: '#3fd0dc', casing: '#05070a', done: '#3b4552', glow: '#3fd0dc' },
  trail: { line: '#ffc21a', casing: '#16181d', done: '#ffc21a', glow: '#ffc21a' },
};
