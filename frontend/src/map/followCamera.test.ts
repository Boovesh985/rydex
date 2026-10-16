import type { Map as MlMap } from 'maplibre-gl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { angleDelta, cameraBus, damp, dampAngle, FollowCamera, speedPullBack, VIEWS } from './followCamera';
import { useMapPrefs } from './mapPrefs';

describe('camera smoothing', () => {
  it('moves the same whatever the frame rate', () => {
    let at60 = 0;
    for (let i = 0; i < 60; i++) at60 = damp(at60, 10, 0.3, 1 / 60);
    let at30 = 0;
    for (let i = 0; i < 30; i++) at30 = damp(at30, 10, 0.3, 1 / 30);
    let at144 = 0;
    for (let i = 0; i < 144; i++) at144 = damp(at144, 10, 0.3, 1 / 144);
    expect(at30).toBeCloseTo(at60, 6);
    expect(at144).toBeCloseTo(at60, 6);
    expect(at60).toBeCloseTo(10 * (1 - Math.exp(-1 / 0.3)), 6);
  });

  it('turns the short way round', () => {
    expect(angleDelta(350, 10)).toBe(20);
    expect(angleDelta(10, 350)).toBe(-20);
    expect(angleDelta(0, 180)).toBe(-180);
    let h = 350;
    for (let i = 0; i < 120; i++) {
      h = dampAngle(h, 10, 0.2, 1 / 60);
      // never swings through the south on the way from 350 to 10
      const d = ((h % 360) + 360) % 360;
      expect(d > 340 || d < 20).toBe(true);
    }
    expect(((h % 360) + 360) % 360).toBeCloseTo(10, 1);
  });

  it('pulls back a little at speed, not at all when parked', () => {
    expect(speedPullBack(0)).toBe(0);
    expect(speedPullBack(14)).toBeCloseTo(0.55, 5);
    expect(speedPullBack(30)).toBeCloseTo(0.55, 5);
  });
});

// ------------------------------------------------------------------ the camera on a stub map

function stubMap() {
  const container = document.createElement('div');
  const canvasContainer = document.createElement('div');
  container.appendChild(canvasContainer);
  Object.defineProperty(container, 'clientHeight', { value: 800 });
  const handler = () => {
    let on = true;
    return { isEnabled: () => on, enable: vi.fn(() => (on = true)), disable: vi.fn(() => (on = false)) };
  };
  const camera = { center: { lng: 80.2, lat: 13.0 }, zoom: 15, pitch: 0, bearing: 0 };
  const map = {
    dragPan: handler(),
    dragRotate: handler(),
    scrollZoom: handler(),
    doubleClickZoom: handler(),
    touchZoomRotate: handler(),
    touchPitch: handler(),
    keyboard: handler(),
    boxZoom: handler(),
    getContainer: () => container,
    getCanvasContainer: () => canvasContainer,
    getZoom: () => camera.zoom,
    getPitch: () => camera.pitch,
    getBearing: () => camera.bearing,
    getCenter: () => camera.center,
    getPadding: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    jumpTo: vi.fn((o: { center: [number, number]; zoom: number; pitch: number; bearing: number }) => {
      camera.center = { lng: o.center[0], lat: o.center[1] };
      camera.zoom = o.zoom;
      camera.pitch = o.pitch;
      camera.bearing = o.bearing;
    }),
  };
  return { map, camera, canvasContainer, container };
}

describe('the follow camera', () => {
  let frames: FrameRequestCallback[] = [];
  let now = 0;
  const run = (seconds: number) => {
    for (let t = 0; t < seconds * 60; t++) {
      now += 1000 / 60;
      const due = frames;
      frames = [];
      due.forEach((f) => f(now));
    }
  };

  beforeEach(() => {
    frames = [];
    now = 0;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    vi.stubGlobal('requestAnimationFrame', (f: FrameRequestCallback) => {
      frames.push(f);
      return frames.length;
    });
    vi.stubGlobal('cancelAnimationFrame', () => {
      frames = [];
    });
    useMapPrefs.setState({ camera: 'chase' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('takes over the gestures while it follows and hands them back after', () => {
    const { map, container } = stubMap();
    const cam = new FollowCamera(map as unknown as MlMap, () => ({ lat: 13.05, lng: 80.25, heading: 90, speed: 0 }), { reducedMotion: true });
    expect(map.scrollZoom.disable).toHaveBeenCalled();
    expect(map.dragPan.disable).toHaveBeenCalled();
    expect(container.classList.contains('rx-following')).toBe(true);
    cam.dispose();
    expect(map.scrollZoom.enable).toHaveBeenCalled();
    expect(map.dragRotate.enable).toHaveBeenCalled();
    expect(container.classList.contains('rx-following')).toBe(false);
  });

  it('settles behind the car in the chosen view', () => {
    const { map, camera } = stubMap();
    const cam = new FollowCamera(map as unknown as MlMap, () => ({ lat: 13.05, lng: 80.25, heading: 90, speed: 0 }));
    run(4);
    expect(camera.center.lat).toBeCloseTo(13.05, 6);
    expect(camera.center.lng).toBeCloseTo(80.25, 6);
    expect(camera.zoom).toBeCloseTo(VIEWS.chase.zoom, 1);
    expect(camera.pitch).toBeCloseTo(VIEWS.chase.pitch, 0);
    expect(camera.bearing).toBeCloseTo(90, 0);
    cam.dispose();
  });

  it('zooms with the wheel and the zoom buttons, and looks around with a drag that springs back', () => {
    const { map, camera, canvasContainer } = stubMap();
    const cam = new FollowCamera(map as unknown as MlMap, () => ({ lat: 13.05, lng: 80.25, heading: 0, speed: 0 }));
    run(3);
    canvasContainer.dispatchEvent(new WheelEvent('wheel', { deltaY: -330, cancelable: true }));
    run(1);
    expect(camera.zoom).toBeCloseTo(VIEWS.chase.zoom + 1, 1);
    cameraBus.send({ type: 'zoom', by: -0.5 });
    run(1);
    expect(camera.zoom).toBeCloseTo(VIEWS.chase.zoom + 0.5, 1);

    canvasContainer.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 1, button: 0, clientX: 100, clientY: 100, bubbles: true }));
    canvasContainer.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 0, clientY: 100, bubbles: true }));
    run(0.5);
    // dragged 100 px to the left: the camera swung 30 degrees round the car
    expect(angleDelta(0, camera.bearing)).toBeCloseTo(30, 0);
    canvasContainer.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1, bubbles: true }));
    run(8);
    expect(Math.abs(angleDelta(0, camera.bearing))).toBeLessThan(1.5);
    cam.dispose();
  });

  it('switches views when the viewer picks another', () => {
    const { map, camera } = stubMap();
    const cam = new FollowCamera(map as unknown as MlMap, () => ({ lat: 13.05, lng: 80.25, heading: 0, speed: 0 }));
    run(3);
    useMapPrefs.getState().nextCamera();
    run(3);
    expect(useMapPrefs.getState().camera).toBe('high');
    expect(camera.zoom).toBeCloseTo(VIEWS.high.zoom, 1);
    expect(camera.pitch).toBeCloseTo(VIEWS.high.pitch, 0);
    cam.dispose();
  });
});
