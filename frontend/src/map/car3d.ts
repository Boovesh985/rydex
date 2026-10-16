import { MercatorCoordinate, type CustomLayerInterface, type Map as MlMap } from 'maplibre-gl';
import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import type { RideType } from '../api/types';
import { metersPerPixel } from '../lib/geo';
import { angleDelta, damp, dampAngle } from './followCamera';
import { skyLight } from './sky';
import { skyDirection, syncCamera } from './threeCamera';

/**
 * Realistic mode's cars (ADR 0016, 0017): 3D models drawn by three.js into MapLibre's own WebGL
 * context (a custom layer), so buildings hide them correctly. Built from code (a side profile
 * extruded across the width, wheel arches cut out, glass cabin, bumpers, lights, plates, mirrors,
 * five-spoke rims), so there are no model files to download or license. Lengths are in metres; the
 * model points along -z.
 *
 * They also drive like cars: wheels roll with the distance actually covered, the front wheels
 * steer into turns, the body leans out of a corner and dips under braking, brake lights come on
 * when slowing or stopped, and the indicator blinks through a turn.
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
  /** an admin's view of the fleet: an amber ring heading to a pickup, a teal one carrying a rider */
  duty?: 'free' | 'pickup' | 'trip';
}

type Shape = 'hatch' | 'sedan' | 'muv';

interface Spec {
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

const SPECS: Record<RideType, Spec> = {
  // a Chennai call taxi: auto-yellow hatchback, black roof and pillars, roof sign
  ECONOMY: { length: 3.85, width: 1.7, height: 1.52, wheelbase: 2.45, wheelR: 0.3, hood: 0.86, shape: 'hatch', body: '#ffc21a', roof: '#17191d', accent: '#17191d', taxiSign: true },
  // a pearl-white sedan with a Marina-teal stripe
  COMFORT: { length: 4.5, width: 1.76, height: 1.47, wheelbase: 2.62, wheelR: 0.31, hood: 1.12, shape: 'sedan', body: '#f2f3ef', roof: '#f2f3ef', accent: '#0b6e79', taxiSign: false },
  // a graphite MUV with roof rails
  XL: { length: 4.6, width: 1.82, height: 1.76, wheelbase: 2.75, wheelR: 0.34, hood: 1.0, shape: 'muv', body: '#2c313a', roof: '#2c313a', accent: '#c9ced6', taxiSign: false },
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
}

function buildTemplate(s: Spec): Template {
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
      new THREE.CylinderGeometry(arch - 0.012, arch - 0.012, W - 0.12, 20, 1, true, -Math.PI / 2, Math.PI)
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
    const wheel = wheelGeometry(R);
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
    };
  }
}

/** A tyre with a five-spoke alloy rim, both faces; vertex-coloured so it is one mesh. */
function wheelGeometry(R: number): THREE.BufferGeometry {
  const t = 0.21;
  const parts = [
    tint(new THREE.CylinderGeometry(R, R, t, 30).rotateZ(Math.PI / 2), '#121316'),
    tint(new THREE.TorusGeometry(R * 0.93, 0.035, 6, 30).rotateY(Math.PI / 2).translate(t / 2 - 0.01, 0, 0), '#1c1d21'),
    tint(new THREE.TorusGeometry(R * 0.93, 0.035, 6, 30).rotateY(Math.PI / 2).translate(-t / 2 + 0.01, 0, 0), '#1c1d21'),
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
  const details = [mesh(t.trim, shared.trim), mesh(t.plates, shared.plate), mesh(t.indicatorLeft, left), mesh(t.indicatorRight, right)];
  chassis.add(paint, roof, mesh(t.glass, shared.glass), mesh(t.head, head), mesh(t.tail, tail), ...details);
  if (t.sign && t.signText) chassis.add(mesh(t.sign, shared.signBody), mesh(t.signText, shared.taxi));

  // headlight beams, at night on the focus car: additive cones reaching ahead
  const beams = new THREE.Group();
  const beamMaterial = new THREE.MeshBasicMaterial({ color: '#fff1b8', transparent: true, opacity: 0.13, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide });
  for (const x of [-1, 1]) {
    const cone = new THREE.Mesh(new THREE.ConeGeometry(2.4, 13, 24, 1, true), beamMaterial);
    cone.rotation.x = -Math.PI / 2;
    cone.position.set(x * spec.width * 0.3, 0.6, -spec.length / 2 - 6.5);
    beams.add(cone);
  }
  beams.visible = false;
  chassis.add(beams);

  const wheels = t.wheelAt.map(([x, z]) => {
    const pivot = new THREE.Group();
    const spin = new THREE.Mesh(t!.wheel, shared.rims);
    pivot.position.set(x, spec.wheelR, z);
    pivot.add(spin);
    root.add(pivot);
    return { pivot, spin };
  });
  const wheelsStatic = new THREE.Mesh(t.wheelsStatic, shared.rims);
  wheelsStatic.visible = false;
  root.add(wheelsStatic);

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
    st.roll = damp(st.roll, clamp(v * omega * 0.011, -0.055, 0.055), 0.28, dt);
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
        const wanted = ((pose.focus ? FOCUS_PX : FLEET_PX) * mpp) / model.spec.length;
        const scale = clamp(wanted, pose.focus ? 1.12 : 1, 40);
        model.root.scale.setScalar(scale);
        const detailed = scale < DETAIL_SCALE;
        for (const d of model.details) d.visible = detailed;
        model.wheelsStatic.visible = !detailed;
        model.chassis.rotation.set(st.pitch, 0, st.roll, 'YXZ');
        model.wheels.forEach((w, i) => {
          w.pivot.visible = detailed;
          w.spin.rotation.x = st.spin;
          w.pivot.rotation.y = i < 2 ? -st.steer : 0;
        });
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
