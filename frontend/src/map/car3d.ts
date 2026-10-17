import { MercatorCoordinate, type CustomLayerInterface, type Map as MlMap } from 'maplibre-gl';
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import type { RideType } from '../api/types';
import { metersPerPixel } from '../lib/geo';
import { VEHICLE_PAINT } from '../lib/vehicles';
import { angleDelta, damp, dampAngle } from './followCamera';
import { skyLight } from './sky';
import { skyDirection, syncCamera } from './threeCamera';

/**
 * Realistic mode's vehicles (ADR 0016, 0017, 0018): 3D models drawn by three.js into MapLibre's own
 * WebGL context (a custom layer), so buildings hide them correctly. Built from code, so there are no
 * model files to download or license. Lengths are in metres; the model points along -z.
 * - cabs: a side profile extruded across the width, wheel arches cut out, glass cabin, bumpers,
 *   lights, plates, mirrors, five-spoke rims;
 * - a bike taxi: tank, seat, engine, forks and exhaust, ridden by a driver in a helmet, with a
 *   pillion rider (in the helmet the driver hands them) while on a trip;
 * - an auto rickshaw: three wheels, the driver's cowl and windscreen, a canopy on open sides, and
 *   the passengers on the back bench while on a trip.
 *
 * They also drive like the real thing: wheels roll with the distance actually covered, the front
 * wheels steer into turns, a car or auto leans out of a corner and dips under braking while a bike
 * leans into it, brake lights come on when slowing or stopped, and the indicator blinks through a
 * turn.
 */

export interface CarPose {
  lat: number;
  lng: number;
  /** compass degrees, 0 = north */
  heading: number;
  /** m/s as reported (the model works out its own speed from how it moves) */
  speed: number;
  type: RideType | null;
  /** the car this screen is about: a pulsing ring under it and headlight beams at night */
  focus?: boolean;
  /** a rider on board: the pillion on a bike, the passengers in an auto's back seat */
  carrying?: boolean;
  /** an admin's view of the fleet: an amber ring heading to a pickup, a teal one carrying a rider */
  duty?: 'free' | 'pickup' | 'trip';
}

type Shape = 'hatch' | 'sedan' | 'muv';
type Kind = 'car' | 'bike' | 'auto';

interface Spec {
  kind: Kind;
  length: number;
  width: number;
  height: number;
  wheelbase: number;
  wheelR: number;
  /** bonnet length, front bumper to windscreen */
  hood: number;
  shape: Shape;
  body: string;
  roof: string;
  accent: string;
  taxiSign: boolean;
}

const colours = (type: RideType) => VEHICLE_PAINT[type];
const SPECS: Record<RideType, Spec> = {
  // a commuter motorbike (hood and shape are unused)
  BIKE: { kind: 'bike', length: 1.98, width: 0.76, height: 1.1, wheelbase: 1.27, wheelR: 0.3, hood: 0, shape: 'hatch', ...colours('BIKE'), accent: '#16181c', taxiSign: false },
  // a three-wheeler: green body, yellow canopy
  AUTO: { kind: 'auto', length: 2.64, width: 1.32, height: 1.72, wheelbase: 2.0, wheelR: 0.21, hood: 0, shape: 'hatch', ...colours('AUTO'), accent: '#16181c', taxiSign: false },
  // a Chennai call taxi: auto-yellow hatchback, black roof and pillars, roof sign
  ECONOMY: { kind: 'car', length: 3.85, width: 1.7, height: 1.52, wheelbase: 2.45, wheelR: 0.3, hood: 0.86, shape: 'hatch', ...colours('ECONOMY'), accent: '#17191d', taxiSign: true },
  // a pearl-white sedan with a Marina-teal stripe
  COMFORT: { kind: 'car', length: 4.5, width: 1.76, height: 1.47, wheelbase: 2.62, wheelR: 0.31, hood: 1.12, shape: 'sedan', ...colours('COMFORT'), accent: '#0b6e79', taxiSign: false },
  // a graphite MUV with roof rails
  XL: { kind: 'car', length: 4.6, width: 1.82, height: 1.76, wheelbase: 2.75, wheelR: 0.34, hood: 1.0, shape: 'muv', ...colours('XL'), accent: '#c9ced6', taxiSign: false },
  // a long midnight-blue saloon with a chrome line
  PREMIUM: { kind: 'car', length: 4.92, width: 1.84, height: 1.46, wheelbase: 2.86, wheelR: 0.33, hood: 1.28, shape: 'sedan', ...colours('PREMIUM'), accent: '#c9ced6', taxiSign: false },
};
const UNKNOWN: Spec = { ...SPECS.ECONOMY, body: '#9aa1aa', roof: '#5b616b', taxiSign: false };

const BEVEL = 0.07;
/** how far the bevel pushes the body's outline out */
const OUT = BEVEL * 0.8;
const CLEARANCE = 0.17;

// ------------------------------------------------------------------ geometry helpers

/** A side profile (x forward, y up) extruded across `width` and turned so forward is -z. */
function sideExtrude(shape: THREE.Shape, width: number, bevel: number): THREE.BufferGeometry {
  const depth = Math.max(0.01, width - 2 * bevel);
  const g = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel * 0.8, bevelSegments: 3, curveSegments: 14 });
  g.translate(0, 0, -depth / 2);
  g.rotateY(Math.PI / 2);
  return g.index ? g.toNonIndexed() : g;
}

/** A box at (x, y, z) in model space (forward is -z). */
function box(w: number, h: number, d: number, x: number, y: number, z: number): THREE.BufferGeometry {
  return new THREE.BoxGeometry(w, h, d).translate(x, y, z).toNonIndexed();
}

/** A bar of square section `t` from one side-profile point (u forward, v up) to another, at side x. */
function bar(t: number, x: number, u1: number, v1: number, u2: number, v2: number): THREE.BufferGeometry {
  const dy = v2 - v1;
  const dz = -(u2 - u1);
  const length = Math.hypot(dy, dz);
  return new THREE.BoxGeometry(t, t, length)
    .applyMatrix4(new THREE.Matrix4().makeRotationX(Math.atan2(-dy, dz)))
    .translate(x, (v1 + v2) / 2, -(u1 + u2) / 2)
    .toNonIndexed();
}

type V3 = [number, number, number];

/** A point from side-profile coordinates: x across, v up, u forward (model forward is -z). */
const P = (x: number, v: number, u: number): V3 => [x, v, -u];

const UP = new THREE.Vector3(0, 1, 0);

/** Lays a geometry built along +y (centred on the origin) from point a to point b. */
function orient(g: THREE.BufferGeometry, a: V3, b: V3): THREE.BufferGeometry {
  const dir = new THREE.Vector3(b[0] - a[0], b[1] - a[1], b[2] - a[2]).normalize();
  g.applyQuaternion(new THREE.Quaternion().setFromUnitVectors(UP, dir));
  g.translate((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2);
  return g.index ? g.toNonIndexed() : g;
}

const span = (a: V3, b: V3) => Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);

/** A round rod of radius r from a to b. */
const rod = (r: number, a: V3, b: V3, sides = 8) => orient(new THREE.CylinderGeometry(r, r, span(a, b), sides), a, b);

/** A flat panel `width` across and `thickness` deep, from a to b (a windscreen, a torso). */
const slab = (width: number, thickness: number, a: V3, b: V3) => orient(new THREE.BoxGeometry(width, span(a, b), thickness), a, b);

/** A squashed sphere: a tank, a helmet, a hand. */
function blob(r: number, at: V3, scale: V3 = [1, 1, 1], detail = 14): THREE.BufferGeometry {
  return new THREE.SphereGeometry(r, detail, Math.max(6, Math.round(detail * 0.7)))
    .scale(...scale)
    .translate(...at)
    .toNonIndexed();
}

/** Part of a ring of mudguard over a wheel at (u, R): theta 0 is behind the wheel, PI ahead of it. */
function fender(radius: number, width: number, u: number, R: number, from: number, to: number): THREE.BufferGeometry {
  return new THREE.CylinderGeometry(radius, radius, width, 18, 1, true, from, to - from)
    .rotateZ(Math.PI / 2)
    .translate(0, R, -u)
    .toNonIndexed();
}

interface Sitter {
  /** centre of the pelvis */
  hip: V3;
  /** how far the back leans forward, radians */
  lean: number;
  /** left then right */
  hands: [V3, V3];
  knees: [V3, V3];
  feet: [V3, V3];
  shirt: string;
  trousers: string;
  skin: string;
  /** a helmet's colour, or bare-headed */
  helmet?: string;
}

/** A seated person, low-poly, for a vehicle's driver and passengers. */
function person(p: Sitter): THREE.BufferGeometry[] {
  const [hx, hy, hz] = p.hip;
  const back = 0.5;
  const neck: V3 = [hx, hy + back * Math.cos(p.lean), hz - back * Math.sin(p.lean)];
  const torso = new THREE.CapsuleGeometry(0.15, back - 0.2, 4, 10)
    .scale(1.22, 1, 0.78)
    .rotateX(-p.lean)
    .translate(hx, (hy + neck[1]) / 2 + 0.03, (hz + neck[2]) / 2)
    .toNonIndexed();
  const head: V3 = [neck[0], neck[1] + 0.15, neck[2] - 0.02];
  const parts = [
    tint(blob(0.15, p.hip, [1.15, 0.62, 0.95], 10), p.trousers),
    tint(torso, p.shirt),
    tint(rod(0.045, [neck[0], neck[1] - 0.04, neck[2]], head, 6), p.skin),
    tint(blob(0.1, head, [0.9, 1.05, 1]), p.skin),
  ];
  if (p.helmet) {
    parts.push(
      tint(blob(0.145, [head[0], head[1] + 0.025, head[2] + 0.01], [0.95, 0.98, 1.08]), p.helmet),
      tint(blob(0.1, [head[0], head[1] + 0.01, head[2] - 0.105], [1.15, 0.55, 0.42], 10), '#14171c'),
    );
  } else {
    parts.push(tint(blob(0.106, [head[0], head[1] + 0.03, head[2] + 0.012], [0.95, 0.78, 1]), '#1a1411'));
  }
  for (const i of [0, 1] as const) {
    const side = i === 0 ? -1 : 1;
    const shoulder: V3 = [hx + side * 0.19, neck[1] - 0.06, neck[2] + 0.02];
    const hand = p.hands[i];
    const elbow: V3 = [(shoulder[0] + hand[0]) / 2 + side * 0.07, (shoulder[1] + hand[1]) / 2 - 0.09, (shoulder[2] + hand[2]) / 2 + 0.05];
    parts.push(
      tint(blob(0.07, shoulder, [1, 1, 1], 8), p.shirt),
      tint(rod(0.05, shoulder, elbow), p.shirt),
      tint(rod(0.042, elbow, hand), p.shirt),
      tint(blob(0.046, hand, [1, 1, 1], 8), p.skin),
    );
    const joint: V3 = [hx + side * 0.1, hy, hz];
    const knee = p.knees[i];
    const foot = p.feet[i];
    parts.push(
      tint(rod(0.07, joint, knee), p.trousers),
      tint(blob(0.065, knee, [1, 1, 1], 8), p.trousers),
      tint(rod(0.056, knee, foot), p.trousers),
      tint(box(0.1, 0.075, 0.22, foot[0], foot[1] - 0.02, foot[2] - 0.05), '#1b1b1d'),
    );
  }
  return parts;
}

/** Mirrored copies of a part on both sides (x and -x). */
function bothSides(make: (side: 1 | -1) => THREE.BufferGeometry): THREE.BufferGeometry[] {
  return [make(1), make(-1)];
}

/** Paints a whole geometry one colour (a vertex colour, so differently coloured parts can share a mesh). */
function tint(g: THREE.BufferGeometry, color: string): THREE.BufferGeometry {
  const c = new THREE.Color(color);
  const n = g.getAttribute('position').count;
  const colors = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) colors.set([c.r, c.g, c.b], i * 3);
  g.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  return g;
}

function merge(parts: THREE.BufferGeometry[]): THREE.BufferGeometry {
  const cleaned = parts.map((p) => {
    const g = p.index ? p.toNonIndexed() : p;
    for (const name of Object.keys(g.attributes)) if (!['position', 'normal', 'uv', 'color'].includes(name)) g.deleteAttribute(name);
    if (!g.getAttribute('uv')) g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(g.getAttribute('position').count * 2), 2));
    return g;
  });
  const hasColor = cleaned.some((g) => g.getAttribute('color'));
  if (hasColor) for (const g of cleaned) if (!g.getAttribute('color')) tint(g, '#ffffff');
  const merged = mergeGeometries(cleaned, false);
  if (!merged) throw new Error('car parts do not merge');
  merged.computeBoundingSphere();
  return merged;
}

function canvasTexture(width: number, height: number, draw: (g: CanvasRenderingContext2D) => void): THREE.Texture {
  const c = document.createElement('canvas');
  c.width = width;
  c.height = height;
  draw(c.getContext('2d')!);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}

/** A soft contact shadow, the car's footprint blurred out. */
const shadowTexture = () =>
  canvasTexture(128, 256, (g) => {
    g.filter = 'blur(16px)';
    g.fillStyle = 'rgba(0,0,0,0.62)';
    g.beginPath();
    g.roundRect(30, 30, 68, 196, 26);
    g.fill();
  });

/** Indian commercial number plate: black on yellow. */
const plateTexture = () =>
  canvasTexture(256, 64, (g) => {
    g.fillStyle = '#f5cf1b';
    g.fillRect(0, 0, 256, 64);
    g.strokeStyle = '#111';
    g.lineWidth = 5;
    g.strokeRect(4, 4, 248, 56);
    g.fillStyle = '#111';
    g.font = 'bold 38px "Arial Narrow", Arial, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText('TN 07 RX 2041', 128, 34);
  });

const taxiTexture = () =>
  canvasTexture(128, 48, (g) => {
    g.fillStyle = '#ffe680';
    g.fillRect(0, 0, 128, 48);
    g.fillStyle = '#111';
    g.font = 'bold 34px Arial, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText('TAXI', 64, 26);
  });

/** A sky-and-street environment for reflections: blue overhead, a bright horizon band, grey road below. */
function environmentScene(night: boolean): THREE.Scene {
  const scene = new THREE.Scene();
  const geo = new THREE.SphereGeometry(10, 48, 24);
  const top = new THREE.Color(night ? '#060914' : '#5d9fe0');
  const horizon = new THREE.Color(night ? '#3a3550' : '#eef3f6');
  const ground = new THREE.Color(night ? '#0a0b0e' : '#55595e');
  const pos = geo.getAttribute('position');
  const colors = new Float32Array(pos.count * 3);
  const c = new THREE.Color();
  for (let i = 0; i < pos.count; i++) {
    const y = pos.getY(i) / 10;
    if (y >= 0) c.copy(horizon).lerp(top, Math.min(1, y * 2.2));
    else c.copy(horizon).lerp(ground, Math.min(1, -y * 6));
    colors.set([c.r, c.g, c.b], i * 3);
  }
  geo.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  scene.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide })));
  // a few bright windows of light: the sun by day, street lights by night
  const glow = new THREE.MeshBasicMaterial({ color: night ? '#ffcf87' : '#ffffff' });
  for (const [x, y, z, r] of night
    ? [
        [6, 1.5, -6, 0.5],
        [-7, 1.2, 4, 0.4],
        [2, 2, 8, 0.45],
      ]
    : [[-4, 7, -5, 1.6]]) {
    scene.add(new THREE.Mesh(new THREE.SphereGeometry(r, 12, 8), glow).translateX(x).translateY(y).translateZ(z));
  }
  return scene;
}

// ------------------------------------------------------------------ the model

interface Template {
  spec: Spec;
  paint: THREE.BufferGeometry;
  roof: THREE.BufferGeometry;
  glass: THREE.BufferGeometry;
  trim: THREE.BufferGeometry;
  head: THREE.BufferGeometry;
  tail: THREE.BufferGeometry;
  indicatorLeft: THREE.BufferGeometry;
  indicatorRight: THREE.BufferGeometry;
  plates: THREE.BufferGeometry;
  sign: THREE.BufferGeometry | null;
  signText: THREE.BufferGeometry | null;
  wheel: THREE.BufferGeometry;
  wheelsStatic: THREE.BufferGeometry;
  /** wheel centres (x, z) */
  wheelAt: [number, number][];
  /** which of them steer */
  steers: boolean[];
  /** the driver, always shown (a bike's rider, an auto's driver; a cab's tinted glass hides its own) */
  people: THREE.BufferGeometry | null;
  /** shown while carrying a rider */
  passengers: THREE.BufferGeometry | null;
  /** a bike leans into turns, wheels and all; a car or auto rolls its body on the wheels */
  leans: boolean;
  /** the frame and seat are part of a two- or three-wheeler's shape, not small details */
  trimIsDetail: boolean;
  /** headlamps (x, height) for the night beams */
  lamps: [number, number][];
}

/** For tests: a vehicle's built shape (no renderer needed). */
export function vehicleShape(type: RideType) {
  const t = buildTemplate(SPECS[type]);
  const bounds = new THREE.Box3();
  for (const g of [t.paint, t.roof, t.glass, t.trim, t.wheelsStatic]) bounds.union(new THREE.Box3().setFromBufferAttribute(g.getAttribute('position') as THREE.BufferAttribute));
  const people = t.people ? new THREE.Box3().setFromBufferAttribute(t.people.getAttribute('position') as THREE.BufferAttribute) : null;
  return { spec: t.spec, bounds, people, wheelAt: t.wheelAt, steers: t.steers, passengers: !!t.passengers, leans: t.leans };
}

function buildTemplate(s: Spec): Template {
  return s.kind === 'bike' ? buildBike(s) : s.kind === 'auto' ? buildAuto(s) : buildCarTemplate(s);
}

function buildCarTemplate(s: Spec): Template {
  const L = s.length;
  const W = s.width;
  const f = L / 2;
  const r = -L / 2;
  const c = CLEARANCE;
  const belt = s.height * (s.shape === 'muv' ? 0.56 : 0.6);
  const nose = belt - (s.shape === 'muv' ? 0.1 : 0.18);
  const R = s.wheelR;
  const arch = R + 0.07;
  const wb = s.wheelbase / 2;
  const ws = f - s.hood; // windscreen base

  // the lower body: sills with wheel arches, bumpers, bonnet, beltline, boot or hatch
  const lower = new THREE.Shape();
  lower.moveTo(r + 0.16, c);
  for (const uc of [-wb, wb]) {
    lower.lineTo(uc - arch, c);
    lower.lineTo(uc - arch, R);
    lower.absarc(uc, R, arch, Math.PI, 0, true);
    lower.lineTo(uc + arch, c);
  }
  lower.lineTo(f - 0.18, c);
  lower.quadraticCurveTo(f, c, f, c + 0.2);
  lower.lineTo(f, nose - 0.1);
  lower.quadraticCurveTo(f, nose, f - 0.16, nose + 0.02);
  lower.quadraticCurveTo(ws + s.hood * 0.45, belt - 0.005, ws, belt);
  if (s.shape === 'sedan') {
    lower.lineTo(r + 0.24, belt - 0.03);
    lower.quadraticCurveTo(r, belt - 0.03, r, belt - 0.18);
  } else {
    lower.lineTo(r + 0.1, belt);
    lower.quadraticCurveTo(r, belt, r, belt - 0.1);
  }
  lower.lineTo(r, c + 0.2);
  lower.quadraticCurveTo(r, c, r + 0.16, c);

  // the glasshouse: windscreen, roof line, rear screen
  const top = s.height;
  const wsRun = s.shape === 'muv' ? 0.64 : s.shape === 'sedan' ? 0.9 : 0.8;
  const roofFront = ws - wsRun;
  const rearBase = s.shape === 'sedan' ? r + 0.98 : s.shape === 'muv' ? r + 0.08 : r + 0.12;
  const roofRear = s.shape === 'sedan' ? rearBase + 0.64 : s.shape === 'muv' ? r + 0.2 : r + 0.4;
  const cabin = new THREE.Shape();
  cabin.moveTo(ws + 0.04, belt - 0.04);
  cabin.lineTo(roofFront + 0.12, top - 0.08);
  cabin.quadraticCurveTo(roofFront, top, roofFront - 0.2, top);
  cabin.lineTo(roofRear + 0.16, top);
  cabin.quadraticCurveTo(roofRear, top, roofRear - 0.04, top - 0.1);
  cabin.lineTo(rearBase, belt - 0.04);
  cabin.lineTo(ws + 0.04, belt - 0.04);
  const cabinW = W * 0.86;
  const glass = sideExtrude(cabin, cabinW, 0.06);

  // the roof panel over the glass, and the pillars that hold it
  const roofShape = new THREE.Shape();
  roofShape.moveTo(roofFront - 0.16, top - 0.03);
  roofShape.lineTo(roofRear + 0.12, top - 0.03);
  roofShape.lineTo(roofRear + 0.16, top + 0.035);
  roofShape.lineTo(roofFront - 0.2, top + 0.035);
  roofShape.lineTo(roofFront - 0.16, top - 0.03);
  const px = cabinW / 2 + 0.005;
  const bPillar = (ws + rearBase) / 2 - (s.shape === 'sedan' ? 0.05 : 0.15);
  const roofParts = [
    sideExtrude(roofShape, cabinW + 0.02, 0.02),
    ...bothSides((x) => bar(0.07, x * px, ws + 0.04, belt - 0.02, roofFront + 0.06, top - 0.04)), // A
    ...bothSides((x) => bar(0.08, x * px, bPillar, belt - 0.02, bPillar + (s.shape === 'sedan' ? 0.02 : 0.05), top - 0.02)), // B
    ...bothSides((x) => bar(0.1, x * px, rearBase + 0.02, belt - 0.02, roofRear - 0.02, top - 0.06)), // C or D
  ];
  if (s.shape === 'muv') {
    for (const x of [-1, 1]) roofParts.push(box(0.05, 0.06, (roofFront - roofRear) * 0.85, x * W * 0.36, top + 0.09, -(roofFront + roofRear) / 2));
  }

  // body-coloured: the shell, mirrors and their stalks, door handles
  const mirrorU = ws - 0.14;
  const paintParts = [
    sideExtrude(lower, W, BEVEL),
    ...bothSides((x) => box(0.2, 0.13, 0.11, x * (W / 2 + 0.11), belt + 0.1, -mirrorU)),
    ...bothSides((x) => box(0.12, 0.045, 0.05, x * (W / 2 + 0.03), belt + 0.06, -mirrorU)),
  ];
  if (s.roof === s.body) paintParts.push(...roofParts.splice(0, roofParts.length));

  // black plastic: grille, intakes, diffuser, door shut lines, arch liners, mirror glass, handles, accent stripe
  const front = -(f + OUT);
  const rear = L / 2 + OUT;
  const doors = s.shape === 'sedan' ? [ws + 0.06, bPillar, -wb + arch + 0.05] : [ws + 0.06, bPillar, -wb + arch + 0.04];
  const seamH = belt - c - 0.16;
  const trimParts = [
    box(W * 0.38, 0.13, 0.06, 0, nose - 0.2, front + 0.02),
    box(W * 0.62, 0.11, 0.06, 0, c + 0.16, front + 0.03),
    box(W * 0.78, 0.1, 0.06, 0, c + 0.14, rear - 0.03),
    ...doors.flatMap((u) => bothSides((x) => box(0.012, seamH, 0.014, x * (W / 2 + 0.003), c + 0.1 + seamH / 2, -u))),
    ...bothSides((x) => box(0.012, 0.035, 0.15, x * (W / 2 + 0.008), belt - 0.11, -(ws - 0.75))),
    ...bothSides((x) => box(0.012, 0.035, 0.15, x * (W / 2 + 0.008), belt - 0.11, -(bPillar - 0.62))),
    ...bothSides((x) => box(0.02, 0.1, 0.09, x * (W / 2 + 0.215), belt + 0.1, -mirrorU)),
    // liners inside the arches, so the wheel wells look deep
    ...[-wb, wb].map((u) =>
      new THREE.CylinderGeometry(arch - 0.012, arch - 0.012, W - 0.12, 20, 1, true, 0, Math.PI)
        .rotateZ(Math.PI / 2)
        .translate(0, R, -u)
        .toNonIndexed(),
    ),
  ].map((g) => tint(g, '#16181c'));
  // a stripe along each flank, in the accent colour
  const stripeL = f - 0.25 - (r + 0.25);
  const stripe = bothSides((x) => tint(box(0.014, 0.05, stripeL, x * (W / 2 + 0.004), belt - 0.17, -(f + r) / 2), s.accent));
  return finish(merge([...trimParts, ...stripe]));

  function finish(trimGeometry: THREE.BufferGeometry): Template {
    const headY = nose - 0.15;
    const tailY = s.shape === 'sedan' ? belt - 0.1 : belt - 0.15;
    const head = merge(bothSides((x) => box(0.36, 0.13, 0.08, x * (W / 2 - 0.29), headY, front + 0.035)));
    const tail = merge(bothSides((x) => box(0.28, 0.15, 0.08, x * (W / 2 - 0.25), tailY, rear - 0.035)));
    const indicator = (x: number) => merge([box(0.1, 0.08, 0.07, x * (W / 2 - 0.06), headY, front + 0.035), box(0.1, 0.08, 0.07, x * (W / 2 - 0.06), tailY, rear - 0.035)]);
    const plates = merge([
      new THREE.PlaneGeometry(0.52, 0.12).rotateY(Math.PI).translate(0, c + 0.33, front - 0.006),
      new THREE.PlaneGeometry(0.52, 0.12).translate(0, c + 0.44, rear + 0.006),
    ]);
    let sign: THREE.BufferGeometry | null = null;
    let signText: THREE.BufferGeometry | null = null;
    if (s.taxiSign) {
      const signZ = -(roofFront + roofRear) / 2;
      sign = merge([box(0.66, 0.2, 0.24, 0, top + 0.14, signZ)]);
      signText = merge([
        new THREE.PlaneGeometry(0.6, 0.17).rotateY(Math.PI).translate(0, top + 0.14, signZ - 0.122),
        new THREE.PlaneGeometry(0.6, 0.17).translate(0, top + 0.14, signZ + 0.122),
      ]);
    }
    const wheel = wheelGeometry(R, 0.21);
    const wheelAt: [number, number][] = [
      [-(W / 2 - 0.17), -wb],
      [W / 2 - 0.17, -wb],
      [-(W / 2 - 0.17), wb],
      [W / 2 - 0.17, wb],
    ].map(([x, u]) => [x, -u] as [number, number]);
    const wheelsStatic = merge(wheelAt.map(([x, z]) => wheel.clone().translate(x, R, z)));
    return {
      spec: s,
      paint: merge(paintParts),
      roof: merge(roofParts.length ? roofParts : [box(0.01, 0.01, 0.01, 0, top, 0)]),
      glass,
      trim: trimGeometry,
      head,
      tail,
      indicatorLeft: indicator(-1),
      indicatorRight: indicator(1),
      plates,
      sign,
      signText,
      wheel,
      wheelsStatic,
      wheelAt,
      // the first two are the rear wheels (+z)
      steers: [false, false, true, true],
      people: null,
      passengers: null,
      leans: false,
      trimIsDetail: true,
      lamps: [
        [-(W / 2 - 0.29), headY],
        [W / 2 - 0.29, headY],
      ],
    };
  }
}

/** A commuter motorbike, its driver in a helmet and jacket, and a pillion rider for trips. */
function buildBike(s: Spec): Template {
  const R = s.wheelR;
  const ua = s.wheelbase / 2;
  const body = s.body;
  const black = '#1c1d21';
  const steel = '#c3c8ce';
  const paintParts = [
    blob(0.2, P(0, 0.84, 0.2), [0.68, 0.55, 1.25]), // tank
    blob(0.13, P(0, 0.96, 0.57), [0.95, 0.85, 0.8]), // headlamp nacelle
    box(0.24, 0.17, 0.36, 0, 0.64, 0.22), // side covers
    slab(0.18, 0.1, P(0, 0.8, -0.38), P(0, 0.9, -0.88)), // tail cowl
  ];
  const trimParts = [
    // engine, cylinder and fins
    tint(box(0.24, 0.26, 0.34, 0, 0.42, 0.02), '#4a4e55'),
    tint(box(0.2, 0.2, 0.18, 0, 0.6, -0.17), '#5b6068'),
    ...[0.53, 0.58, 0.63, 0.68].map((y) => tint(box(0.26, 0.012, 0.2, 0, y, -0.17), '#80868e')),
    tint(new THREE.CylinderGeometry(0.1, 0.1, 0.03, 18).rotateZ(Math.PI / 2).translate(0.13, 0.42, 0).toNonIndexed(), '#9aa0a8'),
    // frame, seat, grab rail
    tint(rod(0.03, P(0, 0.97, 0.5), P(0, 0.62, 0)), black),
    tint(rod(0.026, P(0, 0.92, 0.5), P(0, 0.36, 0.22)), black),
    tint(slab(0.27, 0.1, P(0, 0.9, 0.06), P(0, 0.93, -0.6)), '#141518'),
    ...bothSides((x) => tint(rod(0.014, P(x * 0.13, 0.93, -0.5), P(x * 0.12, 0.95, -0.82)), steel)),
    // swingarm, shocks (red springs), forks, mudguards
    ...bothSides((x) => tint(rod(0.026, P(x * 0.1, 0.4, -0.08), P(x * 0.1, R, -ua)), black)),
    ...bothSides((x) => tint(rod(0.034, P(x * 0.13, 0.36, -0.55), P(x * 0.13, 0.84, -0.45)), '#b3121f')),
    ...bothSides((x) => tint(rod(0.028, P(x * 0.075, R, ua), P(x * 0.075, 1.0, 0.5)), steel)),
    tint(fender(R + 0.045, 0.12, ua, R, Math.PI * 0.35, Math.PI * 0.92), body),
    tint(fender(R + 0.05, 0.13, -ua, R, Math.PI * 0.05, Math.PI * 0.58), body),
    // handlebar, grips, mirrors, speedo
    tint(rod(0.016, P(-0.34, 1.06, 0.44), P(0.34, 1.06, 0.44)), black),
    ...bothSides((x) => tint(rod(0.024, P(x * 0.26, 1.06, 0.44), P(x * 0.37, 1.06, 0.44)), '#111214')),
    ...bothSides((x) => tint(rod(0.01, P(x * 0.24, 1.06, 0.46), P(x * 0.29, 1.26, 0.5)), black)),
    ...bothSides((x) => tint(blob(0.05, P(x * 0.29, 1.28, 0.5), [1.2, 0.75, 0.35], 8), black)),
    tint(blob(0.07, P(0, 1.1, 0.5), [1, 0.6, 0.6], 10), '#111214'),
    // exhaust, chain guard, foot pegs
    tint(rod(0.042, P(0.15, 0.3, 0.05), P(0.17, 0.48, -0.84), 12), steel),
    tint(box(0.04, 0.06, 0.6, -0.12, 0.33, 0.33), black),
    ...bothSides((x) => tint(rod(0.016, P(x * 0.1, 0.38, -0.02), P(x * 0.23, 0.38, -0.02)), black)),
    ...bothSides((x) => tint(rod(0.016, P(x * 0.12, 0.46, -0.45), P(x * 0.24, 0.46, -0.45)), black)),
  ];
  const head = merge([new THREE.CylinderGeometry(0.085, 0.085, 0.06, 20).rotateX(Math.PI / 2).translate(0, 0.95, -0.67)]);
  const tail = merge([box(0.13, 0.05, 0.04, 0, 0.86, 0.9)]);
  const indicator = (x: number) => merge([box(0.06, 0.035, 0.06, x * 0.17, 0.93, -0.6), box(0.05, 0.035, 0.05, x * 0.13, 0.8, 0.86)]);
  const plates = merge([
    new THREE.PlaneGeometry(0.24, 0.13).translate(0, 0.68, 0.905),
    new THREE.PlaneGeometry(0.2, 0.1).rotateY(Math.PI).translate(0, 0.55, -0.96),
  ]);
  const rider = person({
    hip: P(0, 1.0, -0.15),
    lean: 0.32,
    hands: [P(-0.3, 1.07, 0.42), P(0.3, 1.07, 0.42)],
    knees: [P(-0.17, 0.86, 0.2), P(0.17, 0.86, 0.2)],
    feet: [P(-0.17, 0.42, -0.02), P(0.17, 0.42, -0.02)],
    shirt: '#f2b705',
    trousers: '#2a2d33',
    skin: '#8d5a3b',
    helmet: '#1b1d22',
  });
  const pillion = person({
    hip: P(0, 1.02, -0.52),
    lean: 0.12,
    hands: [P(-0.17, 1.02, -0.24), P(0.17, 1.02, -0.24)],
    knees: [P(-0.2, 0.9, -0.22), P(0.2, 0.9, -0.22)],
    feet: [P(-0.2, 0.5, -0.45), P(0.2, 0.5, -0.45)],
    shirt: '#3f6fb5',
    trousers: '#454a52',
    skin: '#a8714c',
    helmet: '#f1f2f4',
  });
  const wheel = bikeWheel(R);
  const wheelAt: [number, number][] = [
    [0, -ua],
    [0, ua],
  ];
  return {
    spec: s,
    paint: merge(paintParts),
    roof: merge([box(0.01, 0.01, 0.01, 0, 0.5, 0)]),
    glass: merge([box(0.01, 0.01, 0.01, 0, 0.5, 0)]),
    trim: merge(trimParts),
    head,
    tail,
    indicatorLeft: indicator(-1),
    indicatorRight: indicator(1),
    plates,
    sign: null,
    signText: null,
    wheel,
    wheelsStatic: merge(wheelAt.map(([x, z]) => wheel.clone().translate(x, R, z))),
    wheelAt,
    steers: [true, false],
    people: merge(rider),
    passengers: merge(pillion),
    leans: true,
    trimIsDetail: false,
    lamps: [[0, 0.95]],
  };
}

/** An auto rickshaw: one wheel in front, two behind, the driver up front and a bench behind. */
function buildAuto(s: Spec): Template {
  const R = s.wheelR;
  const W = s.width;
  const L = s.length;
  const f = L / 2;
  const ua = s.wheelbase / 2;
  const track = 0.56;
  const black = '#1c1d21';
  const chrome = '#c3c8ce';
  const rexine = '#5a1f22';

  // the driver's cowl: over the front wheel, the headlamp on top, open towards the driver
  const cowl = new THREE.Shape();
  cowl.moveTo(0.58, 0.36);
  cowl.lineTo(0.66, 0.54);
  cowl.lineTo(f - 0.12, 0.54);
  cowl.quadraticCurveTo(f + 0.02, 0.56, f, 0.72);
  cowl.lineTo(f - 0.03, 0.96);
  cowl.quadraticCurveTo(f - 0.05, 1.1, f - 0.22, 1.12);
  cowl.lineTo(0.74, 1.06);
  cowl.lineTo(0.58, 0.36);

  // each side of the passenger tub: a wheel arch, the doorway, the rear quarter
  const side = new THREE.Shape();
  const arch = 0.27;
  side.moveTo(-f, 0.3);
  side.lineTo(-ua - arch, 0.3);
  side.lineTo(-ua - arch, R);
  side.absarc(-ua, R, arch, Math.PI, 0, true);
  side.lineTo(-ua + arch, 0.3);
  side.lineTo(-0.25, 0.3);
  side.lineTo(-0.25, 0.52);
  side.lineTo(-0.82, 0.52);
  side.lineTo(-0.88, 0.95);
  side.lineTo(-f + 0.1, 0.98);
  side.quadraticCurveTo(-f, 0.98, -f, 0.88);
  side.lineTo(-f, 0.3);
  const sideX = W / 2 - 0.025;

  const paintParts = [
    sideExtrude(cowl, 0.9, 0.04),
    ...bothSides((x) => sideExtrude(side, 0.05, 0).translate(x * sideX, 0, 0)),
    box(W - 0.04, 0.68, 0.05, 0, 0.64, f - 0.03), // the back
    box(0.86, 0.2, 0.42, 0, 0.42, -0.4), // the driver's footwell
    box(0.6, 0.3, 0.38, 0, 0.53, -0.08), // under the driver's seat
    box(W - 0.4, 0.28, 0.5, 0, 0.45, 0.98), // under the bench, between the rear wheels
  ];

  // the canopy: a curved soft top that drops down over the back
  const top = new THREE.Shape();
  top.moveTo(ua + 0.02, 1.55);
  top.quadraticCurveTo(0.2, 1.78, -f + 0.08, 1.66);
  top.quadraticCurveTo(-f - 0.04, 1.64, -f - 0.04, 1.5);
  top.lineTo(-f - 0.04, 1.0);
  top.lineTo(-f + 0.01, 1.0);
  top.lineTo(-f + 0.01, 1.5);
  top.quadraticCurveTo(-f + 0.01, 1.6, -f + 0.1, 1.61);
  top.quadraticCurveTo(0.2, 1.72, ua + 0.02, 1.5);
  top.lineTo(ua + 0.02, 1.55);
  const roofParts = [sideExtrude(top, W + 0.02, 0.015), ...bothSides((x) => slab(0.03, 0.1, P(x * (W / 2 + 0.01), 1.58, ua), P(x * (W / 2 + 0.01), 1.62, -f + 0.1)))];

  const trimParts = [
    tint(box(W - 0.06, 0.05, 2.1, 0, 0.3, 0.25), black), // floor
    // seats
    tint(box(0.62, 0.09, 0.4, 0, 0.72, -0.08), rexine),
    tint(box(W - 0.16, 0.1, 0.5, 0, 0.64, 0.98), rexine),
    tint(slab(W - 0.16, 0.1, P(0, 0.68, -1.2), P(0, 1.18, -1.24)), rexine),
    // poles holding up the canopy, grab rails, the windscreen's frame
    ...bothSides((x) => tint(rod(0.02, P(x * 0.62, 0.5, -0.24), P(x * 0.63, 1.68, -0.24)), black)),
    ...bothSides((x) => tint(rod(0.02, P(x * 0.43, 1.06, 0.8), P(x * 0.52, 1.56, ua)), black)),
    ...bothSides((x) => tint(rod(0.016, P(x * 0.665, 0.98, -0.3), P(x * 0.665, 0.98, -0.85)), chrome)),
    tint(rod(0.02, P(-0.5, 1.56, ua + 0.01), P(0.5, 1.56, ua + 0.01)), black),
    // handlebar, steering column, front fork and mudguard
    tint(rod(0.018, P(-0.36, 1.06, 0.62), P(0.36, 1.06, 0.62)), black),
    ...bothSides((x) => tint(rod(0.026, P(x * 0.28, 1.06, 0.62), P(x * 0.38, 1.06, 0.62)), '#111214')),
    tint(rod(0.03, P(0, 0.74, 0.9), P(0, 1.06, 0.66)), black),
    ...bothSides((x) => tint(rod(0.026, P(x * 0.08, R, ua), P(x * 0.06, 0.6, ua + 0.06)), black)),
    tint(fender(R + 0.06, 0.17, ua, R, Math.PI * 0.2, Math.PI * 0.95), s.body),
    // bumpers, mirrors
    tint(rod(0.026, P(-0.56, 0.33, -f - 0.04), P(0.56, 0.33, -f - 0.04)), chrome),
    tint(rod(0.02, P(-0.3, 0.56, f + 0.02), P(0.3, 0.56, f + 0.02)), chrome),
    ...bothSides((x) => tint(rod(0.012, P(x * 0.44, 1.02, 0.98), P(x * 0.62, 1.24, 1.02)), black)),
    ...bothSides((x) => tint(blob(0.06, P(x * 0.63, 1.26, 1.02), [1.2, 0.75, 0.35], 8), black)),
  ];
  const glass = merge([slab(0.96, 0.015, P(0, 1.1, f - 0.2), P(0, 1.53, ua + 0.03))]);
  const head = merge([new THREE.CylinderGeometry(0.09, 0.09, 0.05, 20).rotateX(Math.PI / 2).translate(0, 0.98, -(f + 0.0))]);
  const tail = merge(bothSides((x) => box(0.12, 0.08, 0.04, x * 0.48, 0.72, f + 0.01)));
  const indicator = (x: number) => merge([box(0.06, 0.05, 0.04, x * 0.3, 0.9, -(f + 0.0)), box(0.06, 0.05, 0.04, x * 0.48, 0.6, f + 0.01)]);
  const plates = merge([
    new THREE.PlaneGeometry(0.28, 0.1).rotateY(Math.PI).translate(0, 0.66, -(f + 0.045)),
    new THREE.PlaneGeometry(0.3, 0.12).translate(0, 0.48, f + 0.01),
  ]);
  const driver = person({
    hip: P(0, 0.83, 0.08),
    lean: 0.12,
    hands: [P(-0.3, 1.06, 0.62), P(0.3, 1.06, 0.62)],
    knees: [P(-0.15, 0.83, 0.48), P(0.15, 0.83, 0.48)],
    feet: [P(-0.14, 0.38, 0.62), P(0.14, 0.38, 0.62)],
    shirt: '#b39a6b',
    trousers: '#3b3f46',
    skin: '#7a4b2e',
  });
  const passenger = (x: number, shirt: string, skin: string) =>
    person({
      hip: P(x, 0.75, -0.95),
      lean: -0.06,
      hands: [P(x - 0.1, 0.8, -0.56), P(x + 0.1, 0.8, -0.56)],
      knees: [P(x - 0.1, 0.77, -0.52), P(x + 0.1, 0.77, -0.52)],
      feet: [P(x - 0.11, 0.36, -0.46), P(x + 0.11, 0.36, -0.46)],
      shirt,
      trousers: '#2f3640',
      skin,
    });
  const wheel = wheelGeometry(R, 0.13);
  const wheelAt: [number, number][] = [
    [0, -ua],
    [-track, ua],
    [track, ua],
  ];
  return {
    spec: s,
    paint: merge(paintParts),
    roof: merge(roofParts),
    glass,
    trim: merge(trimParts),
    head,
    tail,
    indicatorLeft: indicator(-1),
    indicatorRight: indicator(1),
    plates,
    sign: null,
    signText: null,
    wheel,
    wheelsStatic: merge(wheelAt.map(([x, z]) => wheel.clone().translate(x, R, z))),
    wheelAt,
    steers: [true, false, false],
    people: merge(driver),
    passengers: merge([...passenger(-0.3, '#c2416b', '#9c6644'), ...passenger(0.3, '#3f6fb5', '#6f4a33')]),
    leans: false,
    trimIsDetail: false,
    lamps: [[0, 0.98]],
  };
}

/** A motorbike wheel: a round tyre, a thin rim, five slim cast spokes and a brake disc. */
function bikeWheel(R: number): THREE.BufferGeometry {
  const parts = [
    tint(new THREE.TorusGeometry(R - 0.045, 0.045, 10, 36).rotateY(Math.PI / 2), '#121316'),
    tint(new THREE.TorusGeometry(R - 0.095, 0.014, 6, 36).rotateY(Math.PI / 2), '#b8bec6'),
    tint(new THREE.CylinderGeometry(0.05, 0.05, 0.12, 12).rotateZ(Math.PI / 2), '#9aa0a8'),
    tint(new THREE.CylinderGeometry(0.11, 0.11, 0.006, 22).rotateZ(Math.PI / 2).translate(0.045, 0, 0), '#aab0b7'),
  ];
  for (let k = 0; k < 5; k++) {
    parts.push(
      tint(
        new THREE.BoxGeometry(0.02, R - 0.1, 0.028)
          .translate(0, (R - 0.1) / 2, 0)
          .rotateX((k * 2 * Math.PI) / 5),
        '#2a2d33',
      ),
    );
  }
  return merge(parts);
}

/** A tyre with a five-spoke alloy rim, both faces; vertex-coloured so it is one mesh. */
function wheelGeometry(R: number, t: number): THREE.BufferGeometry {
  const parts = [
    tint(new THREE.CylinderGeometry(R, R, t, 30).rotateZ(Math.PI / 2), '#121316'),
    tint(new THREE.TorusGeometry(R - 0.036, 0.035, 6, 30).rotateY(Math.PI / 2).translate(t / 2 - 0.01, 0, 0), '#1c1d21'),
    tint(new THREE.TorusGeometry(R - 0.036, 0.035, 6, 30).rotateY(Math.PI / 2).translate(-t / 2 + 0.01, 0, 0), '#1c1d21'),
    tint(new THREE.CylinderGeometry(R * 0.66, R * 0.66, t + 0.004, 24).rotateZ(Math.PI / 2), '#26292e'),
    tint(new THREE.CylinderGeometry(R * 0.17, R * 0.17, t + 0.03, 12).rotateZ(Math.PI / 2), '#aab0b7'),
  ];
  for (const side of [1, -1]) {
    parts.push(tint(new THREE.TorusGeometry(R * 0.66, 0.025, 6, 30).rotateY(Math.PI / 2).translate(side * (t / 2 + 0.006), 0, 0), '#d6dbe0'));
    for (let k = 0; k < 5; k++) {
      parts.push(
        tint(
          new THREE.BoxGeometry(0.02, R * 0.56, 0.06)
            .translate(0, R * 0.32, 0)
            .rotateX((k * 2 * Math.PI) / 5)
            .translate(side * (t / 2 + 0.006), 0, 0),
          '#cfd4da',
        ),
      );
    }
  }
  return merge(parts);
}

interface Dynamics {
  heading: number;
  speed: number;
  accel: number;
  yawRate: number;
  steer: number;
  roll: number;
  pitch: number;
  spin: number;
  last: THREE.Vector3 | null;
  turning: number;
  turnUntil: number;
}

interface CarModel {
  root: THREE.Group;
  chassis: THREE.Group;
  template: Template;
  passengers: THREE.Object3D | null;
  wheels: { pivot: THREE.Group; spin: THREE.Mesh }[];
  wheelsStatic: THREE.Mesh;
  details: THREE.Object3D[];
  beams: THREE.Object3D;
  head: THREE.MeshStandardMaterial;
  tail: THREE.MeshStandardMaterial;
  left: THREE.MeshStandardMaterial;
  right: THREE.MeshStandardMaterial;
  ring: THREE.Mesh;
  spec: Spec;
  state: Dynamics;
}

interface Shared {
  shadow: THREE.Texture;
  plate: THREE.MeshStandardMaterial;
  taxi: THREE.MeshStandardMaterial;
  signBody: THREE.MeshStandardMaterial;
  glass: THREE.MeshPhysicalMaterial;
  trim: THREE.MeshStandardMaterial;
  rims: THREE.MeshStandardMaterial;
  people: THREE.MeshStandardMaterial;
  paint: Map<string, THREE.MeshPhysicalMaterial>;
  templates: Map<Spec, Template>;
}

function paintFor(shared: Shared, color: string): THREE.MeshPhysicalMaterial {
  let m = shared.paint.get(color);
  if (!m) {
    m = new THREE.MeshPhysicalMaterial({ color, metalness: 0.25, roughness: 0.4, clearcoat: 1, clearcoatRoughness: 0.07 });
    shared.paint.set(color, m);
  }
  return m;
}

function lightMaterial(color: string, emissive: string): THREE.MeshStandardMaterial {
  return new THREE.MeshStandardMaterial({ color, emissive, emissiveIntensity: 0.3, roughness: 0.25, metalness: 0.1 });
}

function buildCar(type: RideType | null, shared: Shared): CarModel {
  const spec = type ? SPECS[type] : UNKNOWN;
  let t = shared.templates.get(spec);
  if (!t) shared.templates.set(spec, (t = buildTemplate(spec)));
  const root = new THREE.Group();
  const chassis = new THREE.Group();
  root.add(chassis);
  const head = lightMaterial('#f4f1e6', '#fff4c8');
  const tail = lightMaterial('#5c0b0b', '#ff2020');
  const left = lightMaterial('#6b4300', '#ffa000');
  const right = lightMaterial('#6b4300', '#ffa000');
  const mesh = (g: THREE.BufferGeometry, m: THREE.Material) => new THREE.Mesh(g, m);
  const paint = mesh(t.paint, paintFor(shared, spec.body));
  const roof = mesh(t.roof, paintFor(shared, spec.roof));
  const trim = mesh(t.trim, shared.trim);
  const details = [mesh(t.plates, shared.plate), mesh(t.indicatorLeft, left), mesh(t.indicatorRight, right)];
  if (t.trimIsDetail) details.push(trim);
  chassis.add(paint, roof, trim, mesh(t.glass, shared.glass), mesh(t.head, head), mesh(t.tail, tail), ...details);
  if (t.sign && t.signText) chassis.add(mesh(t.sign, shared.signBody), mesh(t.signText, shared.taxi));
  if (t.people) chassis.add(mesh(t.people, shared.people));
  const passengers = t.passengers ? mesh(t.passengers, shared.people) : null;
  if (passengers) {
    passengers.visible = false;
    chassis.add(passengers);
  }

  // headlight beams, at night on the focus car: additive cones reaching ahead
  const beams = new THREE.Group();
  const beamMaterial = new THREE.MeshBasicMaterial({ color: '#fff1b8', transparent: true, opacity: 0.13, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide });
  for (const [x, y] of t.lamps) {
    const cone = new THREE.Mesh(new THREE.ConeGeometry(2.4, 13, 24, 1, true), beamMaterial);
    cone.rotation.x = -Math.PI / 2;
    cone.position.set(x, y, -spec.length / 2 - 6.5);
    beams.add(cone);
  }
  beams.visible = false;
  chassis.add(beams);

  // a bike's wheels lean with it; a car's body rolls on its wheels
  const wheelHolder = t.leans ? chassis : root;
  const wheels = t.wheelAt.map(([x, z]) => {
    const pivot = new THREE.Group();
    const spin = new THREE.Mesh(t!.wheel, shared.rims);
    pivot.position.set(x, spec.wheelR, z);
    pivot.add(spin);
    wheelHolder.add(pivot);
    return { pivot, spin };
  });
  const wheelsStatic = new THREE.Mesh(t.wheelsStatic, shared.rims);
  wheelsStatic.visible = false;
  wheelHolder.add(wheelsStatic);

  const shade = new THREE.Mesh(
    new THREE.PlaneGeometry(spec.width * 1.32, spec.length * 1.16).rotateX(-Math.PI / 2).translate(0, 0.02, 0),
    new THREE.MeshBasicMaterial({ map: shared.shadow, transparent: true, depthWrite: false }),
  );
  root.add(shade);

  const ring = new THREE.Mesh(
    new THREE.RingGeometry(spec.length * 0.75, spec.length * 0.84, 64).rotateX(-Math.PI / 2).translate(0, 0.04, 0),
    new THREE.MeshBasicMaterial({ color: '#3fd0dc', transparent: true, opacity: 0.7, depthWrite: false, side: THREE.DoubleSide }),
  );
  ring.visible = false;
  root.add(ring);

  return {
    root,
    chassis,
    template: t,
    passengers,
    wheels,
    wheelsStatic,
    details,
    beams,
    head,
    tail,
    left,
    right,
    ring,
    spec,
    state: { heading: 0, speed: 0, accel: 0, yawRate: 0, steer: 0, roll: 0, pitch: 0, spin: 0, last: null, turning: 0, turnUntil: 0 },
  };
}

export interface CarLayer {
  layer: CustomLayerInterface;
  set(id: string, pose: CarPose): void;
  remove(id: string): void;
  setNight(night: boolean): void;
}

/** On-screen length the cars are never smaller than (zoomed out they grow so they stay readable). */
const FOCUS_PX = 54;
const FLEET_PX = 34;
/** Above this size-up the small parts (lights, plates, shut lines, spinning wheels) are not drawn. */
const DETAIL_SCALE = 2.4;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export function createCarLayer(id = 'rx-cars-3d'): CarLayer {
  const poses = new Map<string, CarPose>();
  const models = new Map<string, CarModel>();
  let map: MlMap | null = null;
  let renderer: THREE.WebGLRenderer | null = null;
  let night = false;
  const scene = new THREE.Scene();
  const camera = new THREE.Camera();
  const hemi = new THREE.HemisphereLight('#ffffff', '#5b616b', 1.4);
  const sun = new THREE.DirectionalLight('#fff4dc', 2.4);
  scene.add(hemi, sun);
  const taxi = taxiTexture();
  const shared: Shared = {
    shadow: shadowTexture(),
    plate: new THREE.MeshStandardMaterial({ map: plateTexture(), roughness: 0.5 }),
    // the sign glows through its own picture, so the letters stay dark
    taxi: new THREE.MeshStandardMaterial({ map: taxi, emissive: '#ffffff', emissiveMap: taxi, emissiveIntensity: 0.25, roughness: 0.5 }),
    signBody: new THREE.MeshStandardMaterial({ color: '#ffe680', emissive: '#ffcf33', emissiveIntensity: 0.5, roughness: 0.5 }),
    glass: new THREE.MeshPhysicalMaterial({ color: '#0c1219', metalness: 0.3, roughness: 0.04, envMapIntensity: 1.5 }),
    trim: new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.65, metalness: 0.1, side: THREE.DoubleSide }),
    rims: new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.42, metalness: 0.55 }),
    people: new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.78, metalness: 0 }),
    paint: new Map(),
    templates: new Map(),
  };
  let environments: { day: THREE.Texture; night: THREE.Texture } | null = null;
  let environmentTried = false;
  // scene origin, so model coordinates stay small (float precision)
  let origin = MercatorCoordinate.fromLngLat([80.2707, 13.0827]);
  let unit = origin.meterInMercatorCoordinateUnits();
  let lastFrame = performance.now();
  let lastSky = 0;

  const environmentMaterials = () => [shared.glass, shared.rims, shared.trim, ...shared.paint.values()];

  const applyNight = () => {
    // at night the street lights light the cars a little: dim, but the colours still read
    hemi.intensity = night ? 0.8 : 1.4;
    hemi.color.set(night ? '#a9b8ff' : '#ffffff');
    sun.intensity = night ? 0.45 : 2.4;
    shared.signBody.emissiveIntensity = night ? 1.6 : 0.5;
    shared.taxi.emissiveIntensity = night ? 0.9 : 0.25;
    const env = environments ? (night ? environments.night : environments.day) : null;
    for (const m of environmentMaterials()) {
      m.envMap = env;
      m.envMapIntensity = m === shared.glass ? (night ? 0.8 : 1.5) : night ? 0.35 : 1;
      m.needsUpdate = true;
    }
    lastSky = 0;
  };

  /** Reflections are made on the first frame, inside MapLibre's render (it resets its GL state after). */
  const makeEnvironments = (r: THREE.WebGLRenderer) => {
    environmentTried = true;
    try {
      const pmrem = new THREE.PMREMGenerator(r);
      environments = { day: pmrem.fromScene(environmentScene(false), 0.02).texture, night: pmrem.fromScene(environmentScene(true), 0.02).texture };
      pmrem.dispose();
      applyNight();
    } catch {
      environments = null; // plain lighting still works
    }
    r.resetState();
  };

  const updateDynamics = (model: CarModel, pose: CarPose, position: THREE.Vector3, dt: number, now: number) => {
    const st = model.state;
    if (st.last && dt > 0) {
      const moved = position.distanceTo(st.last);
      // a jump (a new car, the scene re-centred) is not driving
      const v = moved > 60 ? st.speed : Math.min(45, moved / dt);
      const before = st.speed;
      st.speed = damp(st.speed, v, 0.35, dt);
      st.accel = damp(st.accel, (st.speed - before) / dt, 0.4, dt);
    } else {
      st.heading = pose.heading;
    }
    st.last = (st.last ?? new THREE.Vector3()).copy(position);
    const prevHeading = st.heading;
    st.heading = dampAngle(st.heading, pose.heading, 0.06, dt);
    const yaw = dt > 0 ? angleDelta(prevHeading, st.heading) / dt : 0; // degrees per second, + = turning right
    st.yawRate = damp(st.yawRate, yaw, 0.2, dt);
    const omega = (st.yawRate * Math.PI) / 180;
    const v = st.speed;
    st.steer = damp(st.steer, clamp(Math.atan((model.spec.wheelbase * omega) / Math.max(v, 1.2)), -0.55, 0.55), 0.18, dt);
    // a bike leans into the turn as far as the corner needs (tan(lean) = v * omega / g); a car or an
    // auto (tall and light, so more) rolls out of it
    const kind = model.spec.kind;
    const roll =
      kind === 'bike' ? -clamp(Math.atan((v * omega) / 9.81), -0.6, 0.6) : kind === 'auto' ? clamp(v * omega * 0.018, -0.08, 0.08) : clamp(v * omega * 0.011, -0.055, 0.055);
    st.roll = damp(st.roll, roll, kind === 'bike' ? 0.22 : 0.28, dt);
    st.pitch = damp(st.pitch, clamp(st.accel * 0.011, -0.035, 0.03), 0.3, dt);
    st.spin -= (v * dt) / model.spec.wheelR;
    if (Math.abs(omega) > 0.14 && v > 0.8) {
      st.turning = Math.sign(omega);
      st.turnUntil = now + 900;
    } else if (now > st.turnUntil) st.turning = 0;
  };

  const layer: CustomLayerInterface = {
    id,
    type: 'custom',
    renderingMode: '3d',
    onAdd(m, gl) {
      map = m;
      renderer = new THREE.WebGLRenderer({ canvas: m.getCanvas(), context: gl, antialias: true });
      renderer.autoClear = false;
      const c = m.getCenter();
      origin = MercatorCoordinate.fromLngLat([c.lng, c.lat]);
      unit = origin.meterInMercatorCoordinateUnits();
      applyNight();
    },
    onRemove() {
      for (const m of models.values()) scene.remove(m.root);
      models.clear();
      renderer = null;
      map = null;
    },
    render(_gl, args) {
      if (!map || !renderer) return;
      if (!environmentTried) makeEnvironments(renderer);
      const now = performance.now();
      const dt = Math.min(0.1, (now - lastFrame) / 1000);
      lastFrame = now;
      if (now - lastSky > 10_000) {
        // the sun (or moon) where it is over Chennai, as the map's buildings are lit
        const light = skyLight(new Date(), night);
        sun.position.copy(skyDirection(light.azimuth, light.elevation).multiplyScalar(100));
        lastSky = now;
      }
      const zoom = map.getZoom();
      const center = map.getCenter();
      // re-centre the scene when the view has wandered from its origin (precision at street level)
      const here = MercatorCoordinate.fromLngLat([center.lng, center.lat]);
      if (Math.abs(here.x - origin.x) / unit > 2000 || Math.abs(here.y - origin.y) / unit > 2000) {
        const shift = new THREE.Vector3((origin.x - here.x) / unit, 0, (origin.y - here.y) / unit);
        for (const m of models.values()) m.state.last?.add(shift);
        origin = here;
        unit = origin.meterInMercatorCoordinateUnits();
      }
      const mpp = metersPerPixel(zoom, center.lat);
      const blinkOn = now % 700 < 350;
      for (const [carId, pose] of poses) {
        let model = models.get(carId);
        if (!model || model.spec !== (pose.type ? SPECS[pose.type] : UNKNOWN)) {
          if (model) scene.remove(model.root);
          model = buildCar(pose.type, shared);
          models.set(carId, model);
          scene.add(model.root);
        }
        const mc = MercatorCoordinate.fromLngLat([pose.lng, pose.lat]);
        const position = new THREE.Vector3((mc.x - origin.x) / unit, 0, (mc.y - origin.y) / unit);
        updateDynamics(model, pose, position, dt, now);
        const st = model.state;
        model.root.position.copy(position);
        model.root.rotation.y = (-st.heading * Math.PI) / 180;
        // sized against a car's length, so a bike zoomed out still reads smaller than a cab
        const wanted = ((pose.focus ? FOCUS_PX : FLEET_PX) * mpp) / Math.max(model.spec.length, 3.2);
        const scale = clamp(wanted, pose.focus ? 1.12 : 1, 40);
        model.root.scale.setScalar(scale);
        const detailed = scale < DETAIL_SCALE;
        for (const d of model.details) d.visible = detailed;
        model.wheelsStatic.visible = !detailed;
        model.chassis.rotation.set(st.pitch, 0, st.roll, 'YXZ');
        model.wheels.forEach((w, i) => {
          w.pivot.visible = detailed;
          w.spin.rotation.x = st.spin;
          w.pivot.rotation.y = model.template.steers[i] ? -st.steer : 0;
        });
        if (model.passengers) model.passengers.visible = !!pose.carrying;
        // lights: headlamps at night, brake lights when slowing or standing, the indicator in a turn
        const braking = st.accel < -0.45 || st.speed < 0.3;
        model.head.emissiveIntensity = night ? 2.6 : 0.35;
        model.tail.emissiveIntensity = braking ? (night ? 3.2 : 2.2) : night ? 1.2 : 0.45;
        model.left.emissiveIntensity = st.turning < 0 && blinkOn ? 2.8 : 0.05;
        model.right.emissiveIntensity = st.turning > 0 && blinkOn ? 2.8 : 0.05;
        // beams only on the car being followed: on a scaled-up fleet car far away they read as giant wedges
        model.beams.visible = night && !!pose.focus;
        const duty = pose.duty && pose.duty !== 'free' ? pose.duty : null;
        model.ring.visible = !!pose.focus || !!duty;
        const ringMaterial = model.ring.material as THREE.MeshBasicMaterial;
        if (pose.focus) {
          const pulse = (now % 1600) / 1600;
          model.ring.scale.setScalar(0.9 + pulse * 0.5);
          ringMaterial.color.set('#3fd0dc');
          ringMaterial.opacity = 0.75 * (1 - pulse);
        } else if (duty) {
          model.ring.scale.setScalar(1);
          ringMaterial.color.set(duty === 'pickup' ? '#f08c00' : '#0b6e79');
          ringMaterial.opacity = 0.85;
        }
      }
      syncCamera(camera, args, origin, unit);
      renderer.resetState();
      renderer.render(scene, camera);
      // wheels turn, the ring pulses, indicators blink: keep drawing while any car is shown
      if (poses.size > 0) map.triggerRepaint();
    },
  };

  return {
    layer,
    set(carId, pose) {
      poses.set(carId, pose);
      map?.triggerRepaint();
    },
    remove(carId) {
      poses.delete(carId);
      const model = models.get(carId);
      if (model) scene.remove(model.root);
      models.delete(carId);
      map?.triggerRepaint();
    },
    setNight(value) {
      night = value;
      applyNight();
      map?.triggerRepaint();
    },
  };
}
