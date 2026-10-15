import { MercatorCoordinate, type CustomLayerInterface, type GeoJSONFeature, type Map as MlMap } from 'maplibre-gl';
import * as THREE from 'three';
import { dedupe, hash2, metresBetween, placeAlong, scatterIn, type LngLat, type Placed } from './sceneryPlacement';

/**
 * Realistic mode's street furniture (ADR 0016): low-poly trees along the streets and in parks, and
 * street lights along the main roads that glow (with a pool of light on the road) at night. Drawn
 * with three.js instanced meshes in MapLibre's WebGL context, so buildings hide them correctly.
 * Placed from the map's own vector tiles around the view, rebuilt when the view moves on.
 */

/** Scenery appears from this zoom (below it a tree would be a pixel). */
export const SCENERY_MIN_ZOOM = 14.8;
/** How far around the view centre things are placed. */
const RADIUS_M = 1100;
const MAX_TREES = 2600;
const MAX_LAMPS = 1100;
/** half the carriageway: lamps and street trees stand just beyond it */
const HALF_WIDTH: Record<string, number> = { motorway: 11, trunk: 10, primary: 8.5, secondary: 7, tertiary: 6, minor: 4.5 };
const LAMP_SPACING: Record<string, number> = { motorway: 38, trunk: 36, primary: 34, secondary: 38, tertiary: 44 };
const TREE_SPACING: Record<string, number> = { primary: 30, secondary: 30, tertiary: 32, minor: 38 };
const GREEN_CLASSES = new Set(['wood', 'grass', 'park', 'garden', 'forest', 'scrub', 'cemetery', 'pitch', 'recreation_ground']);

interface Placement {
  center: { lng: number; lat: number };
  zoom: number;
  lamps: Placed[];
  trees: { lng: number; lat: number; size: number; tint: number }[];
}

function lines(f: GeoJSONFeature): LngLat[][] {
  const g = f.geometry;
  if (g.type === 'LineString') return [g.coordinates as LngLat[]];
  if (g.type === 'MultiLineString') return g.coordinates as LngLat[][];
  return [];
}

function polygons(f: GeoJSONFeature): LngLat[][][] {
  const g = f.geometry;
  if (g.type === 'Polygon') return [g.coordinates as LngLat[][]];
  if (g.type === 'MultiPolygon') return g.coordinates as LngLat[][][];
  return [];
}

/** Lamps and trees for the area around the map's centre, from the loaded vector tiles. */
export function placeScenery(map: MlMap): Placement {
  const c = map.getCenter();
  const center = { lng: c.lng, lat: c.lat };
  const near = (p: { lng: number; lat: number }) => metresBetween(p, center) <= RADIUS_M;
  const lamps: Placed[] = [];
  const streetTrees: Placed[] = [];
  let roads: GeoJSONFeature[] = [];
  let parks: GeoJSONFeature[] = [];
  let cover: GeoJSONFeature[] = [];
  try {
    roads = map.querySourceFeatures('openmaptiles', { sourceLayer: 'transportation' });
    parks = map.querySourceFeatures('openmaptiles', { sourceLayer: 'park' });
    cover = map.querySourceFeatures('openmaptiles', { sourceLayer: 'landcover' });
  } catch {
    // the style has no such source: no scenery
  }
  for (const f of roads) {
    const cls = String(f.properties?.class ?? '');
    if (f.properties?.brunnel === 'tunnel' || !(cls in HALF_WIDTH)) continue;
    for (const line of lines(f)) {
      if (!line.some(([lng, lat]) => near({ lng, lat }))) continue;
      if (LAMP_SPACING[cls]) lamps.push(...placeAlong(line, LAMP_SPACING[cls], HALF_WIDTH[cls] + 1.2, true));
      if (TREE_SPACING[cls] && f.properties?.brunnel !== 'bridge') {
        streetTrees.push(...placeAlong(line, TREE_SPACING[cls], HALF_WIDTH[cls] + 3.2, true));
      }
    }
  }
  const keptLamps = dedupe(lamps.filter(near), 6).slice(0, MAX_LAMPS);
  // a street tree does not grow out of a lamp post
  const lampCells = new Set(keptLamps.map((l) => `${Math.round(l.lat * 8000)}:${Math.round(l.lng * 8000)}`));
  const trees: Placement['trees'] = [];
  for (const t of dedupe(streetTrees.filter(near), 5)) {
    if (lampCells.has(`${Math.round(t.lat * 8000)}:${Math.round(t.lng * 8000)}`)) continue;
    // Chennai's streets are lined, not walled, with trees: leave gaps
    if (hash2(Math.round(t.lat * 1e5), Math.round(t.lng * 1e5)) < 0.5) continue;
    trees.push({ lng: t.lng, lat: t.lat, size: 0.85 + 0.4 * hash2(Math.round(t.lng * 1e5), 3), tint: hash2(Math.round(t.lat * 1e5), 9) });
  }
  const green = [...parks.map((f) => ({ f, park: true })), ...cover.map((f) => ({ f, park: false }))];
  for (const { f, park } of green) {
    const cls = String(f.properties?.class ?? f.properties?.subclass ?? 'park');
    if (!park && !GREEN_CLASSES.has(cls)) continue;
    const dense = cls === 'wood' || cls === 'forest';
    for (const rings of polygons(f)) {
      if (!rings[0]?.some(([lng, lat]) => near({ lng, lat }))) continue;
      for (const [lng, lat] of scatterIn(rings, dense ? 11 : 16, dense ? 0.85 : 0.6, 300)) {
        if (near({ lng, lat })) trees.push({ lng, lat, size: 0.9 + 0.6 * hash2(Math.round(lng * 1e5), Math.round(lat * 1e5)), tint: hash2(Math.round(lat * 1e5), 4) });
      }
    }
  }
  return { center, zoom: map.getZoom(), lamps: keptLamps, trees: dedupe(trees, 4).slice(0, MAX_TREES) };
}

function glowTexture(): THREE.Texture {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d')!;
  const grad = g.createRadialGradient(32, 32, 2, 32, 32, 32);
  grad.addColorStop(0, 'rgba(255,214,140,0.9)');
  grad.addColorStop(0.45, 'rgba(255,190,110,0.35)');
  grad.addColorStop(1, 'rgba(255,170,90,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 64, 64);
  return new THREE.CanvasTexture(c);
}

export interface SceneryLayer {
  layer: CustomLayerInterface;
  setNight(night: boolean): void;
}

export function createSceneryLayer(id = 'rx-scenery-3d'): SceneryLayer {
  let map: MlMap | null = null;
  let renderer: THREE.WebGLRenderer | null = null;
  let night = false;
  let built: Placement | null = null;
  let origin = MercatorCoordinate.fromLngLat([80.2707, 13.0827]);
  let unit = origin.meterInMercatorCoordinateUnits();
  const scene = new THREE.Scene();
  const camera = new THREE.Camera();
  const hemi = new THREE.HemisphereLight('#ffffff', '#55604e', 1.5);
  const sun = new THREE.DirectionalLight('#fff3d6', 1.9);
  sun.position.set(-30, 80, 40);
  scene.add(hemi, sun);

  // geometry, shared by every instance; sizes in metres
  const trunkGeo = new THREE.CylinderGeometry(0.16, 0.24, 2.6, 6).translate(0, 1.3, 0);
  const crownGeo = new THREE.IcosahedronGeometry(2.3, 0).translate(0, 4.2, 0);
  const crownTopGeo = new THREE.IcosahedronGeometry(1.5, 0).translate(0.5, 5.6, -0.3);
  const poleGeo = new THREE.CylinderGeometry(0.08, 0.12, 8, 6).translate(0, 4, 0);
  const armGeo = new THREE.BoxGeometry(0.08, 0.08, 1.7).translate(0, 7.9, -0.8);
  const headGeo = new THREE.BoxGeometry(0.34, 0.14, 0.62).translate(0, 7.82, -1.6);
  const bulbGeo = new THREE.IcosahedronGeometry(0.42, 1).translate(0, 7.62, -1.6);
  const poolGeo = new THREE.PlaneGeometry(13, 13).rotateX(-Math.PI / 2).translate(0, 0.12, -3.2);

  const bark = new THREE.MeshStandardMaterial({ color: '#6b4f36', roughness: 0.95 });
  const leaves = new THREE.MeshStandardMaterial({ color: '#ffffff', roughness: 0.85, flatShading: true });
  const steel = new THREE.MeshStandardMaterial({ color: '#59606b', roughness: 0.5, metalness: 0.6 });
  const lampHead = new THREE.MeshStandardMaterial({ color: '#d9dde3', emissive: '#ffd27a', emissiveIntensity: 0, roughness: 0.4 });
  const bulb = new THREE.MeshBasicMaterial({ color: '#ffe2a6', transparent: true, opacity: 0.85, blending: THREE.AdditiveBlending, depthWrite: false });
  const pool = new THREE.MeshBasicMaterial({ map: glowTexture(), transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, opacity: 0.85 });

  const meshes: THREE.InstancedMesh[] = [];
  let bulbs: THREE.InstancedMesh | null = null;
  let pools: THREE.InstancedMesh | null = null;

  const clear = () => {
    for (const m of meshes) {
      scene.remove(m);
      m.dispose();
    }
    meshes.length = 0;
    bulbs = pools = null;
  };

  const add = (geo: THREE.BufferGeometry, mat: THREE.Material, count: number) => {
    const m = new THREE.InstancedMesh(geo, mat, Math.max(1, count));
    m.count = count;
    m.frustumCulled = false;
    meshes.push(m);
    scene.add(m);
    return m;
  };

  const applyNight = () => {
    hemi.intensity = night ? 0.45 : 1.5;
    hemi.color.set(night ? '#8ea4ff' : '#ffffff');
    sun.intensity = night ? 0.25 : 1.9;
    lampHead.emissiveIntensity = night ? 2.6 : 0;
    if (bulbs) bulbs.visible = night;
    if (pools) pools.visible = night;
  };

  const build = (placement: Placement) => {
    clear();
    built = placement;
    origin = MercatorCoordinate.fromLngLat([placement.center.lng, placement.center.lat]);
    unit = origin.meterInMercatorCoordinateUnits();
    const local = (lng: number, lat: number) => {
      const mc = MercatorCoordinate.fromLngLat([lng, lat]);
      return new THREE.Vector3((mc.x - origin.x) / unit, 0, (mc.y - origin.y) / unit);
    };
    const m4 = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const up = new THREE.Vector3(0, 1, 0);
    const color = new THREE.Color();

    const { trees, lamps } = placement;
    const trunks = add(trunkGeo, bark, trees.length);
    const crowns = add(crownGeo, leaves, trees.length);
    const tops = add(crownTopGeo, leaves, trees.length);
    trees.forEach((t, i) => {
      // a little bigger than life, as games do, so they read at street zoom
      const s = t.size * 1.25;
      q.setFromAxisAngle(up, t.tint * Math.PI * 2);
      m4.compose(local(t.lng, t.lat), q, new THREE.Vector3(s, s * (0.9 + t.tint * 0.3), s));
      trunks.setMatrixAt(i, m4);
      crowns.setMatrixAt(i, m4);
      tops.setMatrixAt(i, m4);
      color.setHSL(0.27 + t.tint * 0.08, 0.42 + t.tint * 0.15, 0.3 + t.tint * 0.1);
      crowns.setColorAt(i, color);
      color.offsetHSL(0.01, 0.02, 0.06);
      tops.setColorAt(i, color);
    });

    const poles = add(poleGeo, steel, lamps.length);
    const arms = add(armGeo, steel, lamps.length);
    const heads = add(headGeo, lampHead, lamps.length);
    bulbs = add(bulbGeo, bulb, lamps.length);
    pools = add(poolGeo, pool, lamps.length);
    const one = new THREE.Vector3(1, 1, 1);
    lamps.forEach((l, i) => {
      // the arm (-z in the model) reaches over the road: turn the model to face `heading`
      q.setFromAxisAngle(up, (-l.heading * Math.PI) / 180);
      m4.compose(local(l.lng, l.lat), q, one);
      for (const m of [poles, arms, heads, bulbs!, pools!]) m.setMatrixAt(i, m4);
    });
    for (const m of meshes) {
      m.instanceMatrix.needsUpdate = true;
      if (m.instanceColor) m.instanceColor.needsUpdate = true;
    }
    applyNight();
    map?.triggerRepaint();
  };

  let pending: ReturnType<typeof setTimeout> | undefined;
  const maybeRebuild = () => {
    if (!map) return;
    const zoom = map.getZoom();
    if (zoom < SCENERY_MIN_ZOOM) {
      if (built) {
        clear();
        built = null;
        map.triggerRepaint();
      }
      return;
    }
    const c = map.getCenter();
    if (built && metresBetween(built.center, { lng: c.lng, lat: c.lat }) < RADIUS_M * 0.3 && Math.abs(built.zoom - zoom) < 1.5) return;
    clearTimeout(pending);
    pending = setTimeout(() => map && build(placeScenery(map)), 120);
  };

  const layer: CustomLayerInterface = {
    id,
    type: 'custom',
    renderingMode: '3d',
    onAdd(m, gl) {
      map = m;
      renderer = new THREE.WebGLRenderer({ canvas: m.getCanvas(), context: gl, antialias: true });
      renderer.autoClear = false;
      m.on('idle', maybeRebuild);
      maybeRebuild();
    },
    onRemove() {
      clearTimeout(pending);
      map?.off('idle', maybeRebuild);
      clear();
      built = null;
      renderer = null;
      map = null;
    },
    render(_gl, args) {
      if (!map || !renderer || !built || map.getZoom() < SCENERY_MIN_ZOOM) return;
      const m = new THREE.Matrix4().fromArray(args.defaultProjectionData.mainMatrix as unknown as number[]);
      const l = new THREE.Matrix4()
        .makeTranslation(origin.x, origin.y, origin.z)
        .scale(new THREE.Vector3(unit, -unit, unit))
        .multiply(new THREE.Matrix4().makeRotationAxis(new THREE.Vector3(1, 0, 0), Math.PI / 2));
      camera.projectionMatrix = m.multiply(l);
      renderer.resetState();
      renderer.render(scene, camera);
    },
  };

  return {
    layer,
    setNight(value) {
      night = value;
      applyNight();
      map?.triggerRepaint();
    },
  };
}
