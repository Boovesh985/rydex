import { describe, expect, it } from 'vitest';
import { horizonY, lightPosition, projectSky, skyLight, sunPosition } from './sky';

const at = (iso: string) => new Date(iso);

describe('sky', () => {
  it('puts Chennai’s sun high at noon, in the east in the morning, down at night', () => {
    const noon = sunPosition(at('2026-10-15T12:15:00+05:30'));
    expect(noon.elevation).toBeGreaterThan(65);
    const morning = sunPosition(at('2026-10-15T07:30:00+05:30'));
    expect(morning.azimuth).toBeGreaterThan(80);
    expect(morning.azimuth).toBeLessThan(120);
    expect(morning.elevation).toBeGreaterThan(10);
    const evening = sunPosition(at('2026-10-15T17:00:00+05:30'));
    expect(evening.azimuth).toBeGreaterThan(240);
    expect(evening.azimuth).toBeLessThan(280);
    expect(sunPosition(at('2026-10-15T23:00:00+05:30')).elevation).toBeLessThan(-30);
  });

  it('lights the night with a moon and a forced day with an afternoon sun', () => {
    const night = skyLight(at('2026-10-15T23:00:00+05:30'), true);
    expect(night.kind).toBe('moon');
    expect(night.elevation).toBeGreaterThanOrEqual(18);
    const forcedDay = skyLight(at('2026-10-15T23:00:00+05:30'), false);
    expect(forcedDay).toEqual({ kind: 'sun', azimuth: 235, elevation: 35 });
    expect(skyLight(at('2026-10-15T12:00:00+05:30'), true).kind).toBe('moon');
    expect(lightPosition({ kind: 'sun', azimuth: 120, elevation: 60 })).toEqual([1.4, 120, 30]);
  });

  it('shows the sky only when the camera is tilted far enough, and places things in it', () => {
    const cam = { bearing: 0, pitch: 76, width: 1000, height: 800, centerX: 500, centerY: 560, focal: 1200 };
    const horizon = horizonY(cam);
    expect(horizon).toBeGreaterThan(200);
    expect(horizon).toBeLessThan(300);
    expect(horizonY({ ...cam, pitch: 0 })).toBeLessThan(0);
    const ahead = projectSky(0, 5, cam)!;
    expect(ahead.x).toBeCloseTo(500, 5);
    expect(ahead.y).toBeLessThan(horizon);
    expect(projectSky(180, 5, cam)).toBeNull(); // behind the camera
    expect(projectSky(30, 5, cam)!.x).toBeGreaterThan(500); // to the right when facing north
  });
});
