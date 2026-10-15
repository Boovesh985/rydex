import { MercatorCoordinate, type CustomLayerInterface, type Map as MlMap } from 'maplibre-gl';
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import type { RideType } from '../api/types';
import { metersPerPixel } from '../lib/geo';

/**
 * Realistic mode's cars: low-poly 3D models drawn by three.js into MapLibre's own WebGL context
 * (a custom layer), so buildings hide them correctly. Models are built from primitives, so there
 * are no model files to download or license. Lengths are in metres; the model points along -z.
 */

export interface CarPose {
  lat: number;
  lng: number;
  /** compass degrees, 0 = north */
  heading: number;
  /** m/s, spins the wheels */
  speed: number;
  type: RideType | null;
  /** the car this screen is about: a ring under it and a slightly bigger model */
  focus?: boolean;
  /** greyed out (an admin's view of a car on a trip) */
  dim?: boolean;
}

interface Spec {
  length: number;
  width: number;
  height: number;
  body: string;
  roof: string;
  accent: string;
  taxiSign: boolean;
}

const SPECS: Record<RideType, Spec> = {
  // a Chennai call taxi: auto-yellow hatchback, black roof, roof sign
  ECONOMY: { length: 3.9, width: 1.7, height: 1.5, body: '#ffc21a', roof: '#16181d', accent: '#16181d', taxiSign: true },
  // a pearl-white sedan with a Marina-teal stripe
  COMFORT: { length: 4.5, width: 1.78, height: 1.45, body: '#f2f3ef', roof: '#f2f3ef', accent: '#0b6e79', taxiSign: false },
  // a graphite MUV
  XL: { length: 4.75, width: 1.88, height: 1.8, body: '#2c313a', roof: '#2c313a', accent: '#c9ced6', taxiSign: false },
};
const UNKNOWN: Spec = { ...SPECS.ECONOMY, body: '#9aa1aa', roof: '#5b616b', taxiSign: false };

const glass = new THREE.MeshStandardMaterial({ color: '#1b2633', roughness: 0.08, metalness: 0.6, transparent: true, opacity: 0.88 });
const tyre = new THREE.MeshStandardMaterial({ color: '#111216', roughness: 0.9 });
const hub = new THREE.MeshStandardMaterial({ color: '#b9bec6', roughness: 0.35, metalness: 0.8 });

function shadowTexture(): THREE.Texture {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d')!;
  const grad = g.createRadialGradient(32, 32, 4, 32, 32, 32);
  grad.addColorStop(0, 'rgba(0,0,0,0.55)');
  grad.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, 64, 64);
  return new THREE.CanvasTexture(c);
}

interface CarModel {
  group: THREE.Group;
  wheels: THREE.Object3D[];
  beams: THREE.Object3D;
  headlights: THREE.MeshStandardMaterial;
  ring: THREE.Mesh;
  spec: Spec;
  dim: boolean;
}

function buildCar(type: RideType | null, shadow: THREE.Texture): CarModel {
  const s = type ? SPECS[type] : UNKNOWN;
  const group = new THREE.Group();
  const paint = new THREE.MeshStandardMaterial({ color: s.body, roughness: 0.35, metalness: 0.25 });
  const roofPaint = new THREE.MeshStandardMaterial({ color: s.roof, roughness: 0.4, metalness: 0.2 });
  const accent = new THREE.MeshStandardMaterial({ color: s.accent, roughness: 0.5 });
  const wheelR = s.height > 1.6 ? 0.38 : 0.32;

  // lower body
  const bodyH = s.height * 0.42;
  const body = new THREE.Mesh(new RoundedBoxGeometry(s.width, bodyH, s.length, 3, 0.18), paint);
  body.position.y = wheelR + bodyH / 2 - 0.05;
  group.add(body);

  // a stripe along the sides
  const stripe = new THREE.Mesh(new THREE.BoxGeometry(s.width + 0.02, 0.08, s.length * 0.82), accent);
  stripe.position.y = wheelR + bodyH * 0.55;
  group.add(stripe);

  // cabin: glass band with a roof on top, set back a little
  const cabinH = s.height - bodyH - wheelR + 0.05;
  const cabinL = s.length * (s.taxiSign ? 0.56 : 0.52);
  const cabin = new THREE.Mesh(new RoundedBoxGeometry(s.width * 0.88, cabinH, cabinL, 3, 0.16), glass);
  cabin.position.set(0, body.position.y + bodyH / 2 + cabinH / 2 - 0.06, s.length * 0.06);
  group.add(cabin);
  const roof = new THREE.Mesh(new RoundedBoxGeometry(s.width * 0.9, 0.09, cabinL * 0.92, 2, 0.04), roofPaint);
  roof.position.set(0, cabin.position.y + cabinH / 2, cabin.position.z);
  group.add(roof);

  if (s.taxiSign) {
    const sign = new THREE.Mesh(
      new RoundedBoxGeometry(0.62, 0.2, 0.3, 2, 0.05),
      new THREE.MeshStandardMaterial({ color: '#ffe680', emissive: '#ffcf33', emissiveIntensity: 0.6 }),
    );
    sign.position.set(0, roof.position.y + 0.14, roof.position.z);
    group.add(sign);
  }
  if (!s.taxiSign && s.height > 1.6) {
    for (const x of [-1, 1]) {
      const rail = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.06, cabinL * 0.8), hub);
      rail.position.set((x * s.width * 0.4), roof.position.y + 0.06, roof.position.z);
      group.add(rail);
    }
  }

  // lights: headlights at the front (-z), tail lights at the back
  const headlights = new THREE.MeshStandardMaterial({ color: '#fffbe6', emissive: '#fff4c2', emissiveIntensity: 0.4 });
  const tail = new THREE.MeshStandardMaterial({ color: '#7a0d0d', emissive: '#ff2a2a', emissiveIntensity: 0.7 });
  for (const x of [-1, 1]) {
    const head = new THREE.Mesh(new THREE.BoxGeometry(0.34, 0.12, 0.06), headlights);
    head.position.set(x * s.width * 0.33, body.position.y + bodyH * 0.12, -s.length / 2 - 0.01);
    group.add(head);
    const back = new THREE.Mesh(new THREE.BoxGeometry(0.32, 0.12, 0.06), tail);
    back.position.set(x * s.width * 0.35, body.position.y + bodyH * 0.18, s.length / 2 + 0.01);
    group.add(back);
  }

  // headlight beams, only at night: additive cones reaching ahead
  const beams = new THREE.Group();
  const beamMaterial = new THREE.MeshBasicMaterial({
    color: '#fff1b8',
    transparent: true,
    opacity: 0.16,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    side: THREE.DoubleSide,
  });
  for (const x of [-1, 1]) {
    const cone = new THREE.Mesh(new THREE.ConeGeometry(2.2, 11, 20, 1, true), beamMaterial);
    cone.rotation.x = -Math.PI / 2; // tip at the lamp, opening forwards (-z)
    cone.position.set(x * s.width * 0.3, 0.55, -s.length / 2 - 5.5);
    beams.add(cone);
  }
  beams.visible = false;
  group.add(beams);

  // wheels
  const wheels: THREE.Object3D[] = [];
  const tyreGeometry = new THREE.CylinderGeometry(wheelR, wheelR, 0.24, 18);
  const hubGeometry = new THREE.CylinderGeometry(wheelR * 0.55, wheelR * 0.55, 0.26, 12);
  for (const [x, z] of [
    [-1, -1],
    [1, -1],
    [-1, 1],
    [1, 1],
  ]) {
    const wheel = new THREE.Group();
    const t = new THREE.Mesh(tyreGeometry, tyre);
    const h = new THREE.Mesh(hubGeometry, hub);
    // a spoke, so turning is visible
    const spoke = new THREE.Mesh(new THREE.BoxGeometry(0.27, wheelR * 1.3, 0.06), hub);
    t.rotation.z = Math.PI / 2;
    h.rotation.z = Math.PI / 2;
    spoke.rotation.y = Math.PI / 2;
    wheel.add(t, h, spoke);
    wheel.position.set(x * (s.width / 2 - 0.1), wheelR, z * s.length * 0.32);
    group.add(wheel);
    wheels.push(wheel);
  }

  // soft contact shadow
  const shade = new THREE.Mesh(
    new THREE.PlaneGeometry(s.width * 1.7, s.length * 1.35),
    new THREE.MeshBasicMaterial({ map: shadow, transparent: true, depthWrite: false }),
  );
  shade.rotation.x = -Math.PI / 2;
  shade.position.y = 0.02;
  group.add(shade);

  // the focus ring
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(s.length * 0.75, s.length * 0.86, 48),
    new THREE.MeshBasicMaterial({ color: '#3fd0dc', transparent: true, opacity: 0.7, depthWrite: false, side: THREE.DoubleSide }),
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.04;
  ring.visible = false;
  group.add(ring);

  return { group, wheels, beams, headlights, ring, spec: s, dim: false };
}

export interface CarLayer {
  layer: CustomLayerInterface;
  set(id: string, pose: CarPose): void;
  remove(id: string): void;
  setNight(night: boolean): void;
}

/** On-screen length of a car, so it stays readable at every zoom (real scale at street level). */
const FOCUS_PX = 54;
const FLEET_PX = 34;

export function createCarLayer(id = 'rx-cars-3d'): CarLayer {
  const poses = new Map<string, CarPose>();
  const models = new Map<string, CarModel>();
  let map: MlMap | null = null;
  let renderer: THREE.WebGLRenderer | null = null;
  let night = false;
  const scene = new THREE.Scene();
  const camera = new THREE.Camera();
  const hemi = new THREE.HemisphereLight('#ffffff', '#5b616b', 1.6);
  const sun = new THREE.DirectionalLight('#fff4dc', 2.2);
  sun.position.set(-40, 80, 30);
  scene.add(hemi, sun);
  const shadow = shadowTexture();
  // scene origin, so model coordinates stay small (float precision)
  let origin = MercatorCoordinate.fromLngLat([80.2707, 13.0827]);
  let unit = origin.meterInMercatorCoordinateUnits();
  const last = performance.now();
  let lastFrame = last;

  const applyNight = () => {
    hemi.intensity = night ? 0.55 : 1.6;
    hemi.color.set(night ? '#8fa6ff' : '#ffffff');
    sun.intensity = night ? 0.35 : 2.2;
    for (const m of models.values()) {
      m.beams.visible = night;
      m.headlights.emissiveIntensity = night ? 2.4 : 0.4;
    }
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
    },
    onRemove() {
      for (const m of models.values()) scene.remove(m.group);
      models.clear();
      renderer = null;
      map = null;
    },
    render(_gl, args) {
      if (!map || !renderer) return;
      const now = performance.now();
      const dt = Math.min(0.1, (now - lastFrame) / 1000);
      lastFrame = now;
      const zoom = map.getZoom();
      const center = map.getCenter();
      // re-centre the scene when the view has wandered far from its origin
      const here = MercatorCoordinate.fromLngLat([center.lng, center.lat]);
      if (Math.abs(here.x - origin.x) / unit > 20_000 || Math.abs(here.y - origin.y) / unit > 20_000) {
        origin = here;
        unit = origin.meterInMercatorCoordinateUnits();
      }
      const mpp = metersPerPixel(zoom, center.lat);
      for (const [carId, pose] of poses) {
        let model = models.get(carId);
        if (!model || model.spec !== (pose.type ? SPECS[pose.type] : UNKNOWN)) {
          if (model) scene.remove(model.group);
          model = buildCar(pose.type, shadow);
          model.beams.visible = night;
          model.headlights.emissiveIntensity = night ? 2.4 : 0.4;
          models.set(carId, model);
          scene.add(model.group);
        }
        const mc = MercatorCoordinate.fromLngLat([pose.lng, pose.lat]);
        model.group.position.set((mc.x - origin.x) / unit, 0, (mc.y - origin.y) / unit);
        model.group.rotation.y = (-pose.heading * Math.PI) / 180;
        const wanted = ((pose.focus ? FOCUS_PX : FLEET_PX) * mpp) / model.spec.length;
        model.group.scale.setScalar(Math.min(40, Math.max(1, wanted)));
        for (const w of model.wheels) w.rotation.x -= (pose.speed * dt) / 0.33;
        model.ring.visible = !!pose.focus;
        if (pose.focus) {
          const pulse = (now % 1600) / 1600;
          model.ring.scale.setScalar(0.9 + pulse * 0.5);
          (model.ring.material as THREE.MeshBasicMaterial).opacity = 0.75 * (1 - pulse);
        }
        if (model.dim !== !!pose.dim) {
          model.dim = !!pose.dim;
          model.group.traverse((o) => {
            if (o instanceof THREE.Mesh && o.material instanceof THREE.MeshStandardMaterial && o.material !== glass) {
              o.material.transparent = model.dim;
              o.material.opacity = model.dim ? 0.45 : 1;
            }
          });
        }
      }
      const m = new THREE.Matrix4().fromArray(args.defaultProjectionData.mainMatrix as unknown as number[]);
      const l = new THREE.Matrix4()
        .makeTranslation(origin.x, origin.y, origin.z)
        .scale(new THREE.Vector3(unit, -unit, unit))
        .multiply(new THREE.Matrix4().makeRotationAxis(new THREE.Vector3(1, 0, 0), Math.PI / 2));
      camera.projectionMatrix = m.multiply(l);
      renderer.resetState();
      renderer.render(scene, camera);
      // the focus ring pulses and wheels turn: keep drawing while any car is shown
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
      if (model) scene.remove(model.group);
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
