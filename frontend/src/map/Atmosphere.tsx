import type { Map as MlMap } from 'maplibre-gl';
import { useEffect, useMemo, useRef } from 'react';
import { hash2 } from './sceneryPlacement';
import { horizonY, projectSky, skyLight } from './sky';
import { skyColors } from './theme';

interface Cloud {
  azimuth: number;
  elevation: number;
  /** pixels wide at the horizon's distance */
  width: number;
  shape: number;
  /** degrees of azimuth per minute (the wind) */
  drift: number;
}

const CLOUD_SHAPES = [
  'M10 46 Q4 46 4 38 Q4 30 14 30 Q16 18 30 18 Q40 8 54 16 Q66 10 74 22 Q90 22 90 36 Q96 46 86 46 Z',
  'M8 44 Q2 36 12 32 Q14 22 26 24 Q34 12 48 18 Q60 14 64 26 Q80 24 82 36 Q92 40 84 46 Z',
  'M6 42 Q4 34 14 32 Q22 24 34 28 Q44 20 56 26 Q70 22 74 32 Q86 32 86 42 Z',
];

/**
 * Realistic mode's sky (ADR 0016, 0017): a gradient down to the horizon, the sun or the moon where
 * it really is over Chennai, stars at night, and 22 clouds drifting with the wind. It lies behind the
 * map, whose own sky is see-through, so trees, lamps and buildings standing above the horizon cover
 * the clouds. Updated in step
 * with the map's frames, and only when the camera moved or the clouds drifted.
 */
export function Atmosphere({ map, night }: { map: MlMap; night: boolean }) {
  const band = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const cloudEls = useRef<(HTMLDivElement | null)[]>([]);
  const starEls = useRef<(HTMLSpanElement | null)[]>([]);

  const clouds = useMemo<Cloud[]>(
    () =>
      Array.from({ length: 22 }, (_, i) => ({
        azimuth: hash2(i, 1) * 360,
        // low in the sky: the camera never looks far above the horizon
        elevation: 1.2 + hash2(i, 2) * 6.5,
        width: 150 + hash2(i, 3) * 230,
        shape: i % CLOUD_SHAPES.length,
        drift: 0.6 + hash2(i, 4) * 0.8,
      })),
    [],
  );
  const colors = skyColors(night);
  const stars = useMemo(
    () => Array.from({ length: 70 }, (_, i) => ({ azimuth: hash2(i, 21) * 360, elevation: 6 + hash2(i, 22) * 40, size: hash2(i, 23) < 0.15 ? 2.5 : 1.5 })),
    [],
  );

  useEffect(() => {
    let last = '';
    let lastMinute = -1;
    // drawn in step with the map's own frames (its render event), so the sky never trails the camera
    const draw = () => {
      const el = band.current;
      if (!el || document.hidden) return;
      const width = map.getContainer().clientWidth;
      const height = map.getContainer().clientHeight;
      const padding = map.getPadding();
      const pad = { top: padding.top ?? 0, bottom: padding.bottom ?? 0, left: padding.left ?? 0, right: padding.right ?? 0 };
      const transform = (map as unknown as { transform?: { cameraToCenterDistance?: number } }).transform;
      const focal = transform?.cameraToCenterDistance ?? height / 2 / Math.tan((36.87 * Math.PI) / 360);
      const cam = {
        bearing: map.getBearing(),
        pitch: map.getPitch(),
        width,
        height,
        centerX: pad.left + (width - pad.left - pad.right) / 2,
        centerY: pad.top + (height - pad.top - pad.bottom) / 2,
        focal,
      };
      // clouds drift by the minute: between camera moves, ten updates a minute are plenty
      const minutes = Date.now() / 60_000;
      const tick = Math.floor(minutes * 10);
      const key = `${cam.bearing.toFixed(2)}|${cam.pitch.toFixed(2)}|${width}|${height}|${cam.centerY.toFixed(1)}|${focal.toFixed(1)}`;
      if (key === last && tick === lastMinute) return;
      last = key;
      lastMinute = tick;
      const horizon = horizonY(cam);
      if (horizon < 8) {
        el.style.visibility = 'hidden';
        return;
      }
      el.style.visibility = 'visible';
      // a little past the horizon: the ground's fog fades into the same colour
      el.style.height = `${Math.round(horizon + 24)}px`;

      const light = skyLight(new Date(), night);
      const at = projectSky(light.azimuth, Math.min(light.elevation, 30), cam);
      if (body.current) {
        body.current.style.display = at ? 'block' : 'none';
        if (at) body.current.style.transform = `translate3d(${at.x}px, ${at.y}px, 0) translate(-50%, -50%)`;
      }
      clouds.forEach((c, i) => {
        const node = cloudEls.current[i];
        if (!node) return;
        const p = projectSky((c.azimuth + minutes * c.drift) % 360, c.elevation, cam);
        if (!p) {
          node.style.display = 'none';
          return;
        }
        // nearer the horizon a cloud is farther away: smaller and hazier
        const scale = Math.max(0.35, Math.min(1.3, (c.elevation + 2) / 6));
        node.style.display = 'block';
        node.style.opacity = String(Math.min(1, (horizon - p.y) / 25));
        node.style.transform = `translate3d(${p.x}px, ${p.y}px, 0) translate(-50%, -50%) scale(${scale})`;
      });
      stars.forEach((s, i) => {
        const node = starEls.current[i];
        if (!node) return;
        const p = night ? projectSky(s.azimuth, s.elevation, cam) : null;
        node.style.display = p ? 'block' : 'none';
        if (p) node.style.transform = `translate3d(${p.x}px, ${p.y}px, 0)`;
      });
    };
    map.on('render', draw);
    // the drift goes on while the map is still (no render events then)
    const timer = setInterval(draw, 6000);
    draw();
    return () => {
      map.off('render', draw);
      clearInterval(timer);
    };
  }, [map, night, clouds, stars]);

  return (
    <div
      ref={band}
      className="pointer-events-none absolute inset-x-0 top-0 overflow-hidden"
      style={{
        visibility: 'hidden',
        background: `linear-gradient(to bottom, ${colors.sky} 0%, ${colors.sky} 25%, ${colors.horizon} calc(100% - 24px), ${colors.horizon} 100%)`,
      }}
      aria-hidden
    >
      {stars.map((s, i) => (
        <span
          key={`s${i}`}
          ref={(n) => {
            starEls.current[i] = n;
          }}
          className="rx-star absolute top-0 left-0 rounded-full bg-white"
          style={{ width: s.size, height: s.size, animationDelay: `${(i % 7) * 0.6}s` }}
        />
      ))}
      <div ref={body} className={`absolute top-0 left-0 ${night ? 'rx-moon' : 'rx-sun'}`} />
      {clouds.map((c, i) => (
        <div
          key={`c${i}`}
          ref={(n) => {
            cloudEls.current[i] = n;
          }}
          className="absolute top-0 left-0"
          style={{ width: c.width, display: 'none' }}
        >
          <svg viewBox="0 0 96 50" className={`w-full ${night ? 'rx-cloud-night' : 'rx-cloud'}`}>
            <path d={CLOUD_SHAPES[c.shape]} />
          </svg>
        </div>
      ))}
    </div>
  );
}
