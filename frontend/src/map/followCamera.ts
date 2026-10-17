import type { Map as MlMap, PaddingOptions } from 'maplibre-gl';
import { useMapPrefs, type CameraView } from './mapPrefs';

/*
 * Realistic mode's chase camera. It owns the camera while it follows a car: MapLibre's own gesture
 * handlers are switched off and this class reads the mouse, touch and keys itself, because
 * map.jumpTo (needed every frame to stay on the car) stops every running gesture and animation.
 * One loop per animation frame, every motion smoothed against the frame time, so it moves the
 * same at 30, 60 or 144 fps and never freezes between GPS fixes.
 */

interface ViewSpec {
  zoom: number;
  pitch: number;
  /** share of the screen height above the car: the car sits low, the road ahead fills the view */
  top: number;
}

/**
 * The cars, trees and roads are life-size (ADR 0017), so the chase views sit at street-level zooms:
 * at 21.2 the camera is about 35 m behind and 12 m above the car.
 */
export const VIEWS: Record<CameraView, ViewSpec> = {
  chase: { zoom: 21.2, pitch: 70, top: 0.44 },
  high: { zoom: 19.7, pitch: 58, top: 0.36 },
  street: { zoom: 22.1, pitch: 78, top: 0.5 },
  top: { zoom: 19.3, pitch: 0, top: 0.24 },
};

export const FOLLOW_MIN_ZOOM = 16.5;
export const FOLLOW_MAX_ZOOM = 22.8;
const MAX_PITCH = 80;

/** Frame-rate independent exponential smoothing towards `target` (`tau`: seconds to cover ~63%). */
export function damp(current: number, target: number, tau: number, dt: number): number {
  if (tau <= 0 || dt <= 0) return tau <= 0 ? target : current;
  return target + (current - target) * Math.exp(-dt / tau);
}

/** The short way round from one compass angle to another, in -180..180. */
export function angleDelta(from: number, to: number): number {
  return ((((to - from) % 360) + 540) % 360) - 180;
}

/** {@link damp} for compass angles: never spins the long way round. */
export function dampAngle(current: number, target: number, tau: number, dt: number): number {
  if (tau <= 0) return target;
  return current + angleDelta(current, target) * (1 - Math.exp(-dt / tau));
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2);

/** How much the chase views pull back at speed (zoom levels at 50 km/h and above). */
export function speedPullBack(speedMs: number): number {
  return 0.55 * clamp((speedMs - 3) / 11, 0, 1);
}

export interface FollowedCar {
  lat: number;
  lng: number;
  heading: number;
  /** m/s */
  speed: number;
}

// ------------------------------------------------------------------ commands from the HUD

/** zoom in or out; `view`: back to the chosen view (following) or the next tilt (free camera) */
export type CameraCommand = { type: 'zoom'; by: number } | { type: 'view' };
const listeners = new Set<(c: CameraCommand) => void>();

/** The HUD's zoom and reset buttons talk to whichever camera drives the map (follow or free). */
export const cameraBus = {
  send(c: CameraCommand) {
    listeners.forEach((l) => l(c));
  },
  listen(l: (c: CameraCommand) => void) {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
};

type Handler = { isEnabled(): boolean; enable(): void; disable(): void };

export class FollowCamera {
  private readonly map: MlMap;
  private readonly getCar: () => FollowedCar | null;
  private readonly reduced: boolean;
  private view: ViewSpec;
  private raf = 0;
  private last = 0;
  // smoothed camera state
  private heading: number | null = null;
  private speed = 0;
  private zoom: number;
  private zoomTarget: number;
  private readonly bias: number;
  private pitch: number;
  private pitchTarget: number;
  private yaw = 0;
  private yawTarget = 0;
  private padTop: number;
  // the swoop from wherever the camera was to behind the car
  private intro: { from: { lng: number; lat: number; zoom: number; pitch: number; bearing: number; padTop: number }; start: number; ms: number } | null;
  // input
  private readonly pointers = new Map<number, { x: number; y: number }>();
  private pinch: { distance: number; angle: number } | null = null;
  private lastInput = -Infinity;
  private readonly disabled: Handler[] = [];
  private readonly off: (() => void)[] = [];

  /**
   * @param options.zoomBias zoom levels closer than the views (tuned for a 4 m car), for a smaller
   *   vehicle: a bike or an auto
   */
  constructor(map: MlMap, getCar: () => FollowedCar | null, options: { reducedMotion?: boolean; zoomBias?: number } = {}) {
    this.map = map;
    this.getCar = getCar;
    this.reduced = !!options.reducedMotion;
    this.bias = options.zoomBias ?? 0;
    this.view = VIEWS[useMapPrefs.getState().camera];
    this.zoom = map.getZoom();
    this.zoomTarget = this.view.zoom + this.bias;
    this.pitch = map.getPitch();
    this.pitchTarget = this.view.pitch;
    const padding = map.getPadding();
    this.padTop = padding.top ?? 0;
    const c = map.getCenter();
    this.intro = this.reduced
      ? null
      : { from: { lng: c.lng, lat: c.lat, zoom: this.zoom, pitch: this.pitch, bearing: map.getBearing(), padTop: this.padTop }, start: performance.now(), ms: 1700 };
    this.takeOverInput();
    this.off.push(
      useMapPrefs.subscribe((s, prev) => {
        if (s.camera !== prev.camera) this.setView(s.camera);
      }),
      cameraBus.listen((c) => this.command(c)),
    );
    this.map.getContainer().classList.add('rx-following');
    this.raf = requestAnimationFrame(this.frame);
  }

  dispose() {
    cancelAnimationFrame(this.raf);
    this.off.forEach((f) => f());
    for (const h of this.disabled) h.enable();
    this.disabled.length = 0;
    this.map.getContainer().classList.remove('rx-following');
  }

  /** A new view: zoom, tilt and placement ease over; any orbit is let go. */
  setView(view: CameraView) {
    this.view = VIEWS[view];
    this.zoomTarget = clamp(this.view.zoom + this.bias, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM);
    this.pitchTarget = this.view.pitch;
    this.yawTarget = 0;
  }

  private command(c: CameraCommand) {
    this.lastInput = performance.now();
    if (c.type === 'zoom') this.zoomTarget = clamp(this.zoomTarget + c.by, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM);
    else this.setView(useMapPrefs.getState().camera);
  }

  private readonly frame = (now: number) => {
    this.raf = requestAnimationFrame(this.frame);
    const dt = this.last ? clamp((now - this.last) / 1000, 0, 0.1) : 0;
    this.last = now;
    const car = this.getCar();
    if (!car) return;
    const r = this.reduced;
    // the camera turns with the car a beat later, as a chase camera on a spring does
    this.heading = this.heading == null ? car.heading : dampAngle(this.heading, car.heading, r ? 0 : 0.55, dt);
    this.speed = damp(this.speed, car.speed, 1.4, dt);
    // a look around is let go a few seconds after the last touch
    if (this.pointers.size === 0 && now - this.lastInput > 2600 && this.view !== VIEWS.top) this.yawTarget = damp(this.yawTarget, 0, 0.9, dt);
    this.yaw = dampAngle(this.yaw, this.yawTarget, r ? 0 : 0.09, dt);
    const pull = this.view === VIEWS.top ? 0 : speedPullBack(this.speed);
    this.zoom = damp(this.zoom, clamp(this.zoomTarget - pull, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM), r ? 0 : 0.16, dt);
    this.pitch = damp(this.pitch, this.pitchTarget, r ? 0 : 0.16, dt);
    const height = this.map.getContainer().clientHeight;
    this.padTop = damp(this.padTop, this.view.top * height, r ? 0 : 0.25, dt);

    let lng = car.lng;
    let lat = car.lat;
    let zoom = this.zoom;
    let pitch = this.pitch;
    let bearing = this.heading + this.yaw;
    let padTop = this.padTop;
    if (this.intro) {
      const t = clamp((now - this.intro.start) / this.intro.ms, 0, 1);
      const k = easeInOut(t);
      const f = this.intro.from;
      lng = f.lng + (lng - f.lng) * k;
      lat = f.lat + (lat - f.lat) * k;
      zoom = f.zoom + (zoom - f.zoom) * k;
      pitch = f.pitch + (pitch - f.pitch) * k;
      bearing = f.bearing + angleDelta(f.bearing, bearing) * k;
      padTop = f.padTop + (padTop - f.padTop) * k;
      if (t >= 1) this.intro = null;
    }
    const padding: PaddingOptions = { top: Math.round(padTop), bottom: 0, left: 0, right: 0 };
    this.map.jumpTo({ center: [lng, lat], zoom, pitch: clamp(pitch, 0, MAX_PITCH), bearing, padding });
  };

  // ------------------------------------------------------------------ input

  private takeOverInput() {
    const m = this.map;
    for (const h of [m.dragPan, m.dragRotate, m.scrollZoom, m.doubleClickZoom, m.touchZoomRotate, m.touchPitch, m.keyboard, m.boxZoom] as Handler[]) {
      if (h.isEnabled()) {
        h.disable();
        this.disabled.push(h);
      }
    }
    const el = m.getCanvasContainer();
    const on = <K extends keyof HTMLElementEventMap>(type: K, fn: (e: HTMLElementEventMap[K]) => void, opts?: AddEventListenerOptions) => {
      el.addEventListener(type, fn as EventListener, opts);
      this.off.push(() => el.removeEventListener(type, fn as EventListener, opts));
    };
    on('pointerdown', (e) => {
      if (e.pointerType === 'mouse' && e.button !== 0 && e.button !== 2) return;
      // a marker's own button (a rider on the admin map) keeps its click
      if ((e.target as HTMLElement).closest('button')) return;
      this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      el.setPointerCapture?.(e.pointerId);
      this.pinch = this.pinchState();
      this.lastInput = performance.now();
    });
    on('pointermove', (e) => {
      const p = this.pointers.get(e.pointerId);
      if (!p) return;
      const dx = e.clientX - p.x;
      const dy = e.clientY - p.y;
      p.x = e.clientX;
      p.y = e.clientY;
      this.lastInput = performance.now();
      if (this.pointers.size === 1) {
        // drag: look around the car (sideways) and tilt (up and down)
        this.yawTarget -= dx * 0.3;
        this.pitchTarget = clamp(this.pitchTarget - dy * 0.22, 0, MAX_PITCH);
      } else if (this.pointers.size === 2) {
        const next = this.pinchState();
        if (this.pinch && next) {
          this.zoomTarget = clamp(this.zoomTarget + Math.log2(next.distance / this.pinch.distance), FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM);
          this.yawTarget -= angleDelta(this.pinch.angle, next.angle);
        }
        this.pinch = next;
      }
    });
    const release = (e: PointerEvent) => {
      this.pointers.delete(e.pointerId);
      this.pinch = this.pinchState();
    };
    on('pointerup', release);
    on('pointercancel', release);
    on(
      'wheel',
      (e) => {
        e.preventDefault();
        const unit = e.deltaMode === 1 ? 33 : e.deltaMode === 2 ? 600 : 1;
        // a trackpad pinch arrives as a wheel event with ctrlKey and small steps
        const rate = e.ctrlKey ? 1 / 90 : 1 / 330;
        this.zoomTarget = clamp(this.zoomTarget - clamp(e.deltaY * unit * rate, -1, 1), FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM);
        this.lastInput = performance.now();
      },
      { passive: false },
    );
    on('dblclick', (e) => {
      e.preventDefault();
      this.setView(useMapPrefs.getState().camera);
    });
    on('contextmenu', (e) => e.preventDefault());
    on('keydown', (e) => {
      const step: Record<string, () => void> = {
        ArrowLeft: () => (this.yawTarget += 15),
        ArrowRight: () => (this.yawTarget -= 15),
        ArrowUp: () => (this.pitchTarget = clamp(this.pitchTarget + 5, 0, MAX_PITCH)),
        ArrowDown: () => (this.pitchTarget = clamp(this.pitchTarget - 5, 0, MAX_PITCH)),
        '+': () => (this.zoomTarget = clamp(this.zoomTarget + 0.5, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM)),
        '=': () => (this.zoomTarget = clamp(this.zoomTarget + 0.5, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM)),
        '-': () => (this.zoomTarget = clamp(this.zoomTarget - 0.5, FOLLOW_MIN_ZOOM, FOLLOW_MAX_ZOOM)),
        c: () => useMapPrefs.getState().nextCamera(),
        r: () => this.setView(useMapPrefs.getState().camera),
      };
      const act = step[e.key] ?? step[e.key.toLowerCase()];
      if (!act || e.altKey || e.ctrlKey || e.metaKey) return;
      e.preventDefault();
      act();
      this.lastInput = performance.now();
    });
  }

  private pinchState(): { distance: number; angle: number } | null {
    if (this.pointers.size !== 2) return null;
    const [a, b] = [...this.pointers.values()];
    return { distance: Math.max(1, Math.hypot(b.x - a.x, b.y - a.y)), angle: (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI };
  }
}
