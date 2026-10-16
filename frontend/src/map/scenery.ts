import { MercatorCoordinate, type CustomLayerInterface, type GeoJSONFeature, type Map as MlMap } from 'maplibre-gl';
import * as THREE from 'three';
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';
import { dedupe, hash2, metresBetween, placeAlong, scatterIn, type LngLat, type Placed } from './sceneryPlacement';
import { skyLight } from './sky';
import { skyDirection, syncCamera } from './threeCamera';

/**
 * Realistic mode's street furniture (ADR 0016, 0017): broadleaf trees and coconut palms along the
 * streets and in parks, swaying a little in the breeze, and street lights along the main roads that
 * glow (with a pool of light on the road) at night. Life-size, drawn with three.js instanced meshes
 * in MapLibre's WebGL context, so buildings hide them correctly. Placed from the map's own vector
 * tiles around the view, and re-placed as the view moves on: in small slices of work between
 * frames, so driving through the city never stutters.
 */

/** Scenery appears from this zoom (below it a tree would be a pixel). */
export const SCENERY_MIN_ZOOM = 14.8;
/** How far around the view centre things are placed. */
const RADIUS_M = 1100;
const MAX_TREES = 2400;
const MAX_LAMPS = 1000;
/** half the carriageway: lamps and street trees stand just beyond it */
const HALF_WIDTH: Record<string, number> = { motorway: 11, trunk: 10, primary: 8.5, secondary: 7, tertiary: 6, minor: 4.5 };
const LAMP_SPACING: Record<string, number> = { motorway: 38, trunk: 36, primary: 34, secondary: 38, tertiary: 44 };
const TREE_SPACING: Record<string, number> = { primary: 30, secondary: 30, tertiary: 32, minor: 38 };
const GREEN_CLASSES = new Set(['wood', 'grass', 'park', 'garden', 'forest', 'scrub', 'cemetery', 'pitch', 'recreation_ground']);
/** share of coconut palms among street trees and park trees */
const PALMS_ON_STREETS = 0.28;
const PALMS_IN_PARKS = 0.2;

interface Tree {
  lng: number;
  lat: number;
  size: number;
  tint: number;
  palm: boolean;
}

interface Placement {
  center: { lng: number; lat: number };
  lamps: Placed[];
  trees: Tree[];
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

/**
 * Lamps and trees for the area around the map's centre, from the loaded vector tiles. A generator:
 * it yields every so often, so the work can be spread over several frames.
 */
function* placeScenery(map: MlMap): Generator<void, Placement> {
  const c = map.getCenter();
  const center = { lng: c.lng, lat: c.lat };
  const near = (p: { lng: number; lat: number }) => metresBetween(p, center) <= RADIUS_M;
  const query = (sourceLayer: string): GeoJSONFeature[] => {
    try {
      return map.querySourceFeatures('openmaptiles', { sourceLayer });
    } catch {
      return []; // the style has no such source: no scenery
    }
  };
  const roads = query('transportation');
  yield;
  const lamps: Placed[] = [];
  const streetTrees: Placed[] = [];
  let n = 0;
  for (const f of roads) {
    if (++n % 120 === 0) yield;
    const cls = String(f.properties?.class ?? '');
    if (f.properties?.brunnel === 'tunnel' || !(cls in HALF_WIDTH)) continue;
    for (const line of lines(f)) {
      if (!line.some(([lng, lat]) => near({ lng, lat }))) continue;
      if (LAMP_SPACING[cls]) lamps.push(...placeAlong(line, LAMP_SPACING[cls], HALF_WIDTH[cls] + 1.2, true));
      if (TREE_SPACING[cls] && f.properties?.brunnel !== 'bridge') streetTrees.push(...placeAlong(line, TREE_SPACING[cls], HALF_WIDTH[cls] + 3.2, true));
    }
  }
  yield;
  // the nearest first, so a cap trims the far edge and never the street in view
  const byDistance = <T extends { lng: number; lat: number }>(items: T[]) =>
    items.map((p) => ({ p, d: metresBetween(p, center) })).sort((a, b) => a.d - b.d).map(({ p }) => p);
  const keptLamps = byDistance(dedupe(lamps.filter(near), 6)).slice(0, MAX_LAMPS);
  // a street tree does not grow out of a lamp post
  const lampCells = new Set(keptLamps.map((l) => `${Math.round(l.lat * 8000)}:${Math.round(l.lng * 8000)}`));
  const trees: Tree[] = [];
  for (const t of dedupe(streetTrees.filter(near), 5)) {
    if (lampCells.has(`${Math.round(t.lat * 8000)}:${Math.round(t.lng * 8000)}`)) continue;
    const a = Math.round(t.lat * 1e5);
    const b = Math.round(t.lng * 1e5);
    // Chennai's streets are lined, not walled, with trees: leave gaps
    if (hash2(a, b) < 0.5) continue;
    trees.push({ lng: t.lng, lat: t.lat, size: 0.85 + 0.4 * hash2(b, 3), tint: hash2(a, 9), palm: hash2(a, b + 17) < PALMS_ON_STREETS });
  }
  yield;
  const green = [...query('park').map((f) => ({ f, park: true })), ...query('landcover').map((f) => ({ f, park: false }))];
  yield;
  n = 0;
  for (const { f, park } of green) {
    if (++n % 40 === 0) yield;
    const cls = String(f.properties?.class ?? f.properties?.subclass ?? 'park');
    if (!park && !GREEN_CLASSES.has(cls)) continue;
    const dense = cls === 'wood' || cls === 'forest';
    for (const rings of polygons(f)) {
      if (!rings[0]?.some(([lng, lat]) => near({ lng, lat }))) continue;
      for (const [lng, lat] of scatterIn(rings, dense ? 11 : 16, dense ? 0.85 : 0.6, 300)) {
        if (!near({ lng, lat })) continue;
        const a = Math.round(lng * 1e5);
        const b = Math.round(lat * 1e5);
        trees.push({ lng, lat, size: 0.9 + 0.6 * hash2(a, b), tint: hash2(b, 4), palm: !dense && hash2(b, a + 5) < PALMS_IN_PARKS });
      }
    }
  }
  yield;
  return { center, lamps: keptLamps, trees: byDistance(dedupe(trees, 4)).slice(0, MAX_TREES) };
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

// ------------------------------------------------------------------ tree models (metres)

function colored(g: THREE.BufferGeometry, color: (x: number, y: number, z: number) => THREE.Color): THREE.BufferGeometry {
  const geo = g.index ? g.toNonIndexed() : g;
  if (geo.getAttribute('uv')) geo.deleteAttribute('uv');
  const pos = geo.getAttribute('position');
  const colors = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const c = color(pos.getX(i), pos.getY(i), pos.getZ(i));
    colors.set([c.r, c.g, c.b], i * 3);
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  return geo;
}

/** A lumpy sphere: an icosphere pushed in and out a little, so a crown is not a ball; smooth-shaded. */
function lobe(radius: number, x: number, y: number, z: number, seed: number): THREE.BufferGeometry {
  const ico = new THREE.IcosahedronGeometry(radius, 1);
  ico.deleteAttribute('normal');
  ico.deleteAttribute('uv');
  const g = mergeVertices(ico);
  const pos = g.getAttribute('position');
  const v = new THREE.Vector3();
  for (let i = 0; i < pos.count; i++) {
    v.fromBufferAttribute(pos, i);
    const k = 0.86 + 0.26 * hash2(Math.round(v.x * 40) + seed, Math.round(v.y * 40) * 7 + Math.round(v.z * 40));
    pos.setXYZ(i, v.x * k, v.y * k * 0.9, v.z * k);
  }
  g.computeVertexNormals();
  return g.translate(x, y, z);
}

/** A rain tree or neem: a crown of a few lumpy lobes, darker underneath. */
function broadleafCrown(): THREE.BufferGeometry {
  const lobes = [
    lobe(2.2, 0, 4.5, 0, 1),
    lobe(1.6, 1.4, 4.1, 0.5, 2),
    lobe(1.7, -1.2, 4.2, -0.6, 3),
    lobe(1.5, 0.3, 5.7, -0.2, 4),
  ];
  const shade = new THREE.Color();
  return mergeGeometries(lobes.map((g) => colored(g, (_x, y) => shade.setScalar(Math.min(1.12, Math.max(0.55, 0.55 + (y - 2.6) * 0.15))))))!;
}

function broadleafTrunk(): THREE.BufferGeometry {
  const bark = new THREE.Color('#6b4f36');
  const parts = [
    new THREE.CylinderGeometry(0.15, 0.27, 3.4, 8).translate(0, 1.7, 0),
    // two limbs reaching into the crown
    new THREE.CylinderGeometry(0.07, 0.12, 1.8, 6).rotateZ(-0.6).translate(0.55, 3.6, 0.1),
    new THREE.CylinderGeometry(0.07, 0.12, 1.7, 6).rotateZ(0.55).rotateY(0.6).translate(-0.45, 3.6, -0.3),
  ];
  return mergeGeometries(parts.map((g) => colored(g, () => bark)))!;
}

const PALM_TOP = new THREE.Vector3(0.85, 9.4, 0);

/** A coconut palm's trunk: slender, leaning, ringed. */
function palmTrunk(): THREE.BufferGeometry {
  const curve = new THREE.QuadraticBezierCurve3(new THREE.Vector3(0, 0, 0), new THREE.Vector3(0.1, 5, 0), PALM_TOP);
  const g = new THREE.TubeGeometry(curve, 14, 0.16, 7, false);
  const light = new THREE.Color('#8d7a5e');
  const dark = new THREE.Color('#6e5c45');
  return colored(g, (_x, y) => (Math.sin(y * 7.5) > 0.2 ? dark : light));
}

/** A palm's crown: drooping fronds, folded along the rib, and a bunch of coconuts. */
function palmCrown(): THREE.BufferGeometry {
  const parts: THREE.BufferGeometry[] = [];
  const base = new THREE.Color('#3f6b2a');
  const tip = new THREE.Color('#7aa04a');
  const up = new THREE.Vector3(0, 1, 0);
  const fronds = 11;
  for (let k = 0; k < fronds; k++) {
    const angle = (k / fronds) * Math.PI * 2 + hash2(k, 31) * 0.35;
    const length = 3.4 + hash2(k, 32) * 0.9;
    const lift = 0.7 + hash2(k, 33) * 0.6;
    const dir = new THREE.Vector3(Math.cos(angle), 0, Math.sin(angle));
    const side = new THREE.Vector3(-dir.z, 0, dir.x);
    const steps = 7;
    const positions: number[] = [];
    const colors: number[] = [];
    const ring: THREE.Vector3[][] = [];
    for (let i = 0; i <= steps; i++) {
      const s = i / steps;
      const center = PALM_TOP.clone().addScaledVector(dir, s * length).addScaledVector(up, lift * s - 2.3 * s * s);
      const w = 0.62 * Math.sin(Math.PI * Math.min(1, s * 1.08 + 0.04));
      const fold = up.clone().multiplyScalar(-0.3 * w);
      ring.push([center.clone().addScaledVector(side, w).add(fold), center, center.clone().addScaledVector(side, -w).add(fold)]);
    }
    const c = new THREE.Color();
    for (let i = 0; i < steps; i++) {
      for (const [a, b] of [
        [0, 1],
        [1, 2],
      ]) {
        const quad = [ring[i][a], ring[i + 1][a], ring[i + 1][b], ring[i][a], ring[i + 1][b], ring[i][b]];
        for (const p of quad) {
          positions.push(p.x, p.y, p.z);
          c.copy(base).lerp(tip, i / steps);
          colors.push(c.r, c.g, c.b);
        }
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    g.computeVertexNormals();
    parts.push(g);
  }
  const nut = new THREE.Color('#5d5a2a');
  for (let k = 0; k < 5; k++) {
    const a = (k / 5) * Math.PI * 2;
    parts.push(colored(new THREE.SphereGeometry(0.17, 8, 6).translate(PALM_TOP.x + Math.cos(a) * 0.24, PALM_TOP.y - 0.3, Math.sin(a) * 0.24), () => nut));
  }
  return mergeGeometries(parts.map((g) => (g.getAttribute('normal') ? g : (g.computeVertexNormals(), g))))!;
}

/** The breeze: crowns sway a few centimetres, more at the top, each tree out of step with the next. */
const swayTime = { value: 0 };
function swaying<T extends THREE.Material>(material: T, strength: number): T {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uSwayTime = swayTime;
    shader.vertexShader = `uniform float uSwayTime;\n${shader.vertexShader}`.replace(
      '#include <begin_vertex>',
      `#include <begin_vertex>
      float swayHeight = max(0.0, position.y - 2.5);
      #ifdef USE_INSTANCING
        float swayPhase = instanceMatrix[3].x * 0.31 + instanceMatrix[3].z * 0.17;
      #else
        float swayPhase = 0.0;
      #endif
      transformed.x += sin(uSwayTime * 1.3 + swayPhase) * ${strength.toFixed(4)} * swayHeight;
      transformed.z += cos(uSwayTime * 1.05 + swayPhase * 1.7) * ${(strength * 0.7).toFixed(4)} * swayHeight;`,
    );
  };
  material.customProgramCacheKey = () => `sway-${strength}`;
  return material;
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
  scene.add(hemi, sun);
  let lastSky = 0;

  // geometry, shared by every instance; sizes in metres
  const broadTrunkGeo = broadleafTrunk();
  const broadCrownGeo = broadleafCrown();
  const palmTrunkGeo = palmTrunk();
  const palmCrownGeo = palmCrown();
  const poleGeo = new THREE.CylinderGeometry(0.08, 0.12, 8, 8).translate(0, 4, 0);
  const armGeo = new THREE.CylinderGeometry(0.045, 0.05, 1.75, 6).rotateX(Math.PI / 2).translate(0, 7.9, -0.82);
  const headGeo = new THREE.BoxGeometry(0.36, 0.13, 0.66).translate(0, 7.84, -1.6);
  const bulbGeo = new THREE.IcosahedronGeometry(0.42, 1).translate(0, 7.62, -1.6);
  const poolGeo = new THREE.PlaneGeometry(13, 13).rotateX(-Math.PI / 2).translate(0, 0.12, -3.2);

  const bark = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95 });
  const leaves = swaying(new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.85 }), 0.012);
  const fronds = swaying(new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.75, side: THREE.DoubleSide }), 0.016);
  const steel = new THREE.MeshStandardMaterial({ color: '#59606b', roughness: 0.45, metalness: 0.6 });
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
    lastSky = 0;
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
    const scale = new THREE.Vector3();

    const broad = placement.trees.filter((t) => !t.palm);
    const palms = placement.trees.filter((t) => t.palm);
    const trunks = add(broadTrunkGeo, bark, broad.length);
    const crowns = add(broadCrownGeo, leaves, broad.length);
    broad.forEach((t, i) => {
      const s = t.size * 1.1;
      q.setFromAxisAngle(up, t.tint * Math.PI * 2);
      m4.compose(local(t.lng, t.lat), q, scale.set(s, s * (0.9 + t.tint * 0.3), s));
      trunks.setMatrixAt(i, m4);
      crowns.setMatrixAt(i, m4);
      color.setHSL(0.25 + t.tint * 0.08, 0.42 + t.tint * 0.15, 0.32 + t.tint * 0.09);
      crowns.setColorAt(i, color);
    });
    const palmTrunks = add(palmTrunkGeo, bark, palms.length);
    const palmCrowns = add(palmCrownGeo, fronds, palms.length);
    palms.forEach((t, i) => {
      const s = 0.85 + t.size * 0.25;
      q.setFromAxisAngle(up, t.tint * Math.PI * 2);
      m4.compose(local(t.lng, t.lat), q, scale.set(s, s, s));
      palmTrunks.setMatrixAt(i, m4);
      palmCrowns.setMatrixAt(i, m4);
      color.setHSL(0.24 + t.tint * 0.05, 0.55, 0.5 + t.tint * 0.12);
      palmCrowns.setColorAt(i, color);
    });

    const { lamps } = placement;
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

  // placing runs in slices of ~4 ms between frames
  let job: Generator<void, Placement> | null = null;
  let pump: ReturnType<typeof setTimeout> | undefined;
  let lastBuild = 0;
  let stale = false;
  const work = () => {
    if (!job) return;
    const until = performance.now() + 4;
    let step = job.next();
    while (!step.done && performance.now() < until) step = job.next();
    if (step.done) {
      job = null;
      lastBuild = performance.now();
      build(step.value);
    } else pump = setTimeout(work, 0);
  };
  const start = () => {
    if (!map || job) return;
    stale = false;
    job = placeScenery(map);
    pump = setTimeout(work, 0);
  };

  /** Re-place when the view has moved on, or when new tiles came in since the last placing. */
  const maybeRebuild = () => {
    if (!map || job) return;
    if (map.getZoom() < SCENERY_MIN_ZOOM) {
      if (built) {
        clear();
        built = null;
        map.triggerRepaint();
      }
      return;
    }
    const c = map.getCenter();
    const moved = !built || metresBetween(built.center, { lng: c.lng, lat: c.lat }) > RADIUS_M * 0.3;
    if (moved || (stale && performance.now() - lastBuild > 4000)) start();
  };
  const onData = (e: { sourceId?: string; tile?: unknown }) => {
    if (e.sourceId === 'openmaptiles' && e.tile) stale = true;
  };
  let ticker: ReturnType<typeof setInterval> | undefined;

  const layer: CustomLayerInterface = {
    id,
    type: 'custom',
    renderingMode: '3d',
    onAdd(m, gl) {
      map = m;
      renderer = new THREE.WebGLRenderer({ canvas: m.getCanvas(), context: gl, antialias: true });
      renderer.autoClear = false;
      m.on('moveend', maybeRebuild);
      m.on('idle', maybeRebuild);
      m.on('sourcedata', onData);
      // a followed car keeps the map busy (no idle): look again every second
      ticker = setInterval(maybeRebuild, 1000);
      maybeRebuild();
    },
    onRemove() {
      clearTimeout(pump);
      clearInterval(ticker);
      job = null;
      map?.off('moveend', maybeRebuild);
      map?.off('idle', maybeRebuild);
      map?.off('sourcedata', onData);
      clear();
      built = null;
      renderer = null;
      map = null;
    },
    render(_gl, args) {
      if (!map || !renderer || !built || map.getZoom() < SCENERY_MIN_ZOOM) return;
      const now = performance.now();
      swayTime.value = now / 1000;
      if (now - lastSky > 10_000) {
        const light = skyLight(new Date(), night);
        sun.position.copy(skyDirection(light.azimuth, light.elevation).multiplyScalar(100));
        lastSky = now;
      }
      syncCamera(camera, args, origin, unit);
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
