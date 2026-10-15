import type { Map as MlMap } from 'maplibre-gl';

/**
 * The base map: OpenFreeMap's "Positron" vector style (OpenStreetMap data, free, no key), recoloured
 * at runtime to the Rydex palette. "Realistic" adds extruded 3D buildings, a sky with fog and a
 * sun (or moon) light; night swaps every colour for a dark city.
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
  fog: string;
  light: { color: string; intensity: number; position: [number, number, number] };
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
  sky: '#86c3e3',
  horizon: '#f3ecdc',
  fog: '#eef0ea',
  light: { color: '#fff8e8', intensity: 0.55, position: [1.4, 205, 35] },
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
  tower: ['#252a34', '#323a48'],
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
  fog: '#151a26',
  light: { color: '#9fb7ff', intensity: 0.22, position: [1.4, 30, 70] },
};

export const BUILDINGS_3D = 'rx-buildings-3d';

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

function ensureBuildings(map: MlMap, p: Palette) {
  if (!map.getLayer(BUILDINGS_3D)) {
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
        },
      },
      firstSymbolLayer(map),
    );
  }
  map.setPaintProperty(BUILDINGS_3D, 'fill-extrusion-color', [
    'interpolate',
    ['linear'],
    ['coalesce', ['get', 'render_height'], 6],
    0,
    p.tower[0],
    60,
    p.tower[1],
  ]);
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
  const p = look.night ? NIGHT : DAY;
  recolor(map, p);
  ensureBuildings(map, p);
  if (!previous || previous.realistic !== look.realistic) {
    showBuildings(map, look.realistic);
    if (map.getLayer('building')) map.setLayoutProperty('building', 'visibility', look.realistic ? 'none' : 'visible');
  }
  map.setLight({ anchor: 'map', color: p.light.color, intensity: p.light.intensity, position: p.light.position });
  map.setSky(
    look.realistic
      ? {
          'sky-color': p.sky,
          'horizon-color': p.horizon,
          'fog-color': p.fog,
          'sky-horizon-blend': 0.6,
          'horizon-fog-blend': 0.7,
          'fog-ground-blend': 0.55,
          'atmosphere-blend': 0,
        }
      : { 'sky-color': p.land, 'horizon-color': p.land, 'fog-color': p.land, 'fog-ground-blend': 1, 'atmosphere-blend': 0 },
  );
  map.getContainer().style.background = p.land;
}

/** Night by the local clock (rough Chennai sunset/sunrise), for the realistic mode's default. */
export function isNightNow(date = new Date()): boolean {
  const h = date.getHours() + date.getMinutes() / 60;
  return h >= 18.5 || h < 6;
}

export const ROUTE_COLORS = {
  day: { line: '#0b6e79', casing: '#16181d', done: '#9aa6ab', glow: '#3fb6c2' },
  night: { line: '#3fd0dc', casing: '#05070a', done: '#3b4552', glow: '#3fd0dc' },
  trail: { line: '#ffc21a', casing: '#16181d', done: '#ffc21a', glow: '#ffc21a' },
};
