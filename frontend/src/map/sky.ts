/**
 * Where the sun (or moon) is in Chennai's sky, for realistic mode's lighting and sky (ADR 0016).
 * A simple solar model (declination and hour angle; no equation of time): good to a degree or
 * two, plenty for shading buildings and drawing a sun.
 */

export interface Celestial {
  kind: 'sun' | 'moon';
  /** compass degrees, 0 = north */
  azimuth: number;
  /** degrees above the horizon */
  elevation: number;
}

const rad = (d: number) => (d * Math.PI) / 180;
const deg = (r: number) => (r * 180) / Math.PI;

/** The sun's position at `date` seen from (lat, lng). */
export function sunPosition(date: Date, lat = 13.08, lng = 80.27): { azimuth: number; elevation: number } {
  const start = Date.UTC(date.getUTCFullYear(), 0, 0);
  const dayOfYear = (date.getTime() - start) / 86_400_000;
  const declination = rad(-23.44 * Math.cos(((2 * Math.PI) / 365) * (dayOfYear + 10)));
  const utcHours = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600;
  const hourAngle = rad(15 * (utcHours + lng / 15 - 12));
  const phi = rad(lat);
  const sinEl = Math.sin(phi) * Math.sin(declination) + Math.cos(phi) * Math.cos(declination) * Math.cos(hourAngle);
  const elevation = deg(Math.asin(sinEl));
  const azimuth = (deg(Math.atan2(Math.sin(hourAngle), Math.cos(hourAngle) * Math.sin(phi) - Math.tan(declination) * Math.cos(phi))) + 180 + 360) % 360;
  return { azimuth, elevation };
}

/**
 * What lights the scene: the sun by day, the moon (opposite it) by night. A forced "day" at night
 * gets a pleasant afternoon sun, a forced "night" by day a moon in the east.
 */
export function skyLight(date: Date, night: boolean): Celestial {
  const sun = sunPosition(date);
  if (!night) {
    return sun.elevation >= 6 ? { kind: 'sun', ...sun } : { kind: 'sun', azimuth: 235, elevation: 35 };
  }
  if (sun.elevation <= -6) {
    return { kind: 'moon', azimuth: (sun.azimuth + 180) % 360, elevation: Math.min(65, Math.max(18, -sun.elevation)) };
  }
  return { kind: 'moon', azimuth: 110, elevation: 32 };
}

/**
 * MapLibre's light position [radial, azimuthal, polar] for a light at this place in the sky
 * (anchor "map": azimuth from north, polar angle from straight up).
 */
export function lightPosition(c: Celestial): [number, number, number] {
  return [1.4, c.azimuth, Math.min(85, Math.max(5, 90 - c.elevation))];
}

/** Screen position of a direction in the sky for a pitched camera, or null when off screen. */
export function projectSky(
  azimuth: number,
  elevation: number,
  cam: { bearing: number; pitch: number; width: number; height: number; centerX: number; centerY: number; focal: number },
): { x: number; y: number } | null {
  const relative = ((((azimuth - cam.bearing) % 360) + 540) % 360) - 180;
  if (Math.abs(relative) > 75) return null;
  const above = 90 - cam.pitch + elevation; // degrees above the camera's centre ray
  if (above >= 89) return null;
  const x = cam.centerX + cam.focal * Math.tan(rad(relative));
  const y = cam.centerY - cam.focal * Math.tan(rad(above));
  if (x < -200 || x > cam.width + 200 || y < -200) return null;
  return { x, y };
}

/** The horizon's height on screen (pixels from the top); 0 or less when the sky is not in view. */
export function horizonY(cam: { pitch: number; centerY: number; focal: number }): number {
  if (cam.pitch <= 1) return -Infinity;
  return cam.centerY - cam.focal * Math.tan(rad(90 - cam.pitch));
}
