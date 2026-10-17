import { Box, Camera, LocateFixed, Minus, Moon, Plus, Route as RouteIcon, Sun, X } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { cameraBus } from '../map/followCamera';
import { CAMERA_VIEW_LABELS, useMapPrefs } from '../map/mapPrefs';

/** The way into realistic mode: a pill on the map, under the top bar. */
export function RealisticButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group absolute top-[4.6rem] right-3 z-[1100] flex items-center gap-2 rounded-full bg-ink py-2 pr-4 pl-2 font-display-wide text-sm font-bold text-chalk shadow-[var(--shadow-float)] transition-transform hover:-translate-y-0.5 md:top-20 md:right-4"
    >
      <span className="grid size-7 place-items-center rounded-full bg-auto text-ink">
        <Box className="size-4 transition-transform duration-500 group-hover:rotate-[20deg]" aria-hidden />
      </span>
      Realistic 3D
    </button>
  );
}

interface HudProps {
  /** what is happening, e.g. "Driver arriving" */
  eyebrow: string;
  /** the one number, e.g. the ETA */
  value: string;
  unit?: string;
  sub?: ReactNode;
  /** null hides the speedometer */
  speedKmh: number | null;
  following: boolean;
  onFollow: () => void;
  onOverview: () => void;
  night: boolean;
  onNight: () => void;
  onExit: () => void;
  /** the bottom card: driver, PIN, the next action */
  children?: ReactNode;
}

/** Realistic mode's overlay: status capsule, speedometer, camera controls and a compact bottom card. */
export function RealisticHud({ eyebrow, value, unit, sub, speedKmh, following, onFollow, onOverview, night, onNight, onExit, children }: HudProps) {
  return (
    <>
      <div
        className="pointer-events-none absolute top-[4.6rem] left-3 z-[1100] max-w-[calc(100%-5.5rem)] md:top-20 md:left-1/2 md:-translate-x-1/2"
        role="status"
        aria-label={`${eyebrow}: ${value}${unit ? ' ' + unit : ''}`}
      >
        <div className="flex items-center gap-4 rounded-2xl border border-white/10 bg-ink/90 py-2.5 pr-3 pl-4 text-chalk shadow-[var(--shadow-float)]">
          <div className="min-w-0">
            <p className="font-display-wide text-[0.66rem] font-bold tracking-[0.18em] text-chalk/60 uppercase">{eyebrow}</p>
            {sub && <p className="truncate text-sm text-chalk/90">{sub}</p>}
          </div>
          <p className="flex items-baseline gap-1 rounded-lg bg-black/40 px-3 py-1 font-mono font-bold text-auto tabular [text-shadow:0_0_12px_currentColor]">
            <span className="text-3xl leading-none">{value}</span>
            {unit && <span className="text-sm opacity-80">{unit}</span>}
          </p>
        </div>
      </div>

      <div className="absolute top-[4.6rem] right-3 z-[1100] flex flex-col gap-2 md:top-20 md:right-4">
        <HudButton label="Exit realistic mode" onClick={onExit} tone="dark">
          <X className="size-5" />
        </HudButton>
        <HudButton label={following ? 'Following the car' : 'Follow the car'} pressed={following} onClick={onFollow}>
          <LocateFixed className="size-5" />
        </HudButton>
        <HudButton label="Show the whole route" onClick={onOverview}>
          <RouteIcon className="size-5" />
        </HudButton>
        <CameraButton following={following} />
        <HudButton label={night ? 'Switch to day' : 'Switch to night'} pressed={night} onClick={onNight}>
          {night ? <Moon className="size-5" /> : <Sun className="size-5" />}
        </HudButton>
        <ZoomButtons />
      </div>
      <GestureHint following={following} />

      <div className="pointer-events-none absolute inset-x-3 bottom-[max(0.75rem,env(safe-area-inset-bottom))] z-[1100] flex items-end gap-3 md:right-auto md:left-4 md:w-[440px]">
        {speedKmh != null && <SpeedDial kmh={speedKmh} />}
        {children && (
          <div className="pointer-events-auto min-w-0 flex-1 rounded-2xl border border-white/10 bg-paper p-3 shadow-[var(--shadow-float)]">
            {children}
          </div>
        )}
      </div>
    </>
  );
}

function HudButton({ label, pressed, tone, onClick, children }: { label: string; pressed?: boolean; tone?: 'dark'; onClick: () => void; children: ReactNode }) {
  const look = pressed ? 'bg-sea text-paper' : tone === 'dark' ? 'bg-ink text-chalk' : 'bg-paper text-ink hover:bg-mist';
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      onClick={onClick}
      className={`grid size-11 place-items-center rounded-full shadow-[var(--shadow-float)] transition-colors ${look}`}
    >
      {children}
    </button>
  );
}

const DIAL_MAX = 80;

/** A small speedometer: an arc that fills up to 80 km/h, with the digits inside. */
export function SpeedDial({ kmh }: { kmh: number }) {
  const value = Math.max(0, Math.round(kmh));
  const r = 30;
  const arc = Math.PI * 1.5 * r; // 270 degrees
  const filled = arc * Math.min(1, value / DIAL_MAX);
  return (
    <div className="pointer-events-auto grid size-[84px] shrink-0 place-items-center rounded-full border border-white/10 bg-ink/90 text-chalk shadow-[var(--shadow-float)]" role="img" aria-label={`Speed ${value} km/h`}>
      <svg viewBox="0 0 80 80" className="absolute size-[84px]" aria-hidden>
        <g transform="rotate(135 40 40)">
          <circle cx="40" cy="40" r={r} fill="none" stroke="rgb(255 255 255 / 0.12)" strokeWidth="6" strokeDasharray={`${arc} 999`} strokeLinecap="round" />
          <circle
            cx="40"
            cy="40"
            r={r}
            fill="none"
            stroke={value > 60 ? '#d7362b' : '#ffc21a'}
            strokeWidth="6"
            strokeDasharray={`${filled} 999`}
            strokeLinecap="round"
            className="transition-[stroke-dasharray] duration-700"
          />
        </g>
      </svg>
      <span className="relative text-center font-mono leading-none font-bold tabular">
        <span className="block text-2xl">{value}</span>
        <span className="text-[0.6rem] tracking-widest text-chalk/60">KM/H</span>
      </span>
    </div>
  );
}

/** Trip progress from pickup to drop, with the car on it. */
export function TripProgress({ progress, from, to }: { progress: number; from: string; to: string }) {
  const p = Math.min(1, Math.max(0, progress));
  return (
    <div className="space-y-1.5" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(p * 100)} aria-label="Trip progress">
      <div className="relative h-2 rounded-full bg-mist">
        <div className="absolute inset-y-0 left-0 rounded-full bg-sea transition-[width] duration-1000" style={{ width: `${p * 100}%` }} />
        <span
          className="absolute top-1/2 grid size-5 -translate-x-1/2 -translate-y-1/2 place-items-center rounded-full border-2 border-ink bg-auto transition-[left] duration-1000"
          style={{ left: `${p * 100}%` }}
          aria-hidden
        />
      </div>
      <div className="flex justify-between gap-3 text-xs text-slate">
        <span className="truncate">{from}</span>
        <span className="shrink-0 font-mono font-bold text-ink tabular">{Math.round(p * 100)}%</span>
        <span className="truncate text-right">{to}</span>
      </div>
    </div>
  );
}

/**
 * Realistic mode without a ride of its own (the operations map, the booking map): exit, camera,
 * day/night, zoom. `following`: the camera chases a car the viewer picked (the admin's ride-along).
 */
export function RealisticBar({ night, onNight, onExit, following = false }: { night: boolean; onNight: () => void; onExit: () => void; following?: boolean }) {
  return (
    <>
      <div className="absolute top-[4.6rem] right-3 z-[1100] flex flex-col gap-2 md:top-20 md:right-4">
        <HudButton label="Exit realistic mode" onClick={onExit} tone="dark">
          <X className="size-5" />
        </HudButton>
        <CameraButton following={following} />
        <HudButton label={night ? 'Switch to day' : 'Switch to night'} pressed={night} onClick={onNight}>
          {night ? <Moon className="size-5" /> : <Sun className="size-5" />}
        </HudButton>
        <ZoomButtons />
      </div>
      <GestureHint following={following} />
    </>
  );
}

/**
 * The camera: while following, steps through the chase views (remembered for next time); with a
 * free camera, through street-level tilt, bird's eye and straight down.
 */
function CameraButton({ following }: { following: boolean }) {
  const view = useMapPrefs((s) => s.camera);
  const nextCamera = useMapPrefs((s) => s.nextCamera);
  const label = following ? `Camera: ${CAMERA_VIEW_LABELS[view]} (C to change)` : 'Change the tilt';
  return (
    <HudButton label={label} onClick={() => (following ? nextCamera() : cameraBus.send({ type: 'view' }))}>
      <Camera className="size-5" />
    </HudButton>
  );
}

function ZoomButtons() {
  return (
    <div className="flex flex-col overflow-hidden rounded-full bg-paper text-ink shadow-[var(--shadow-float)]">
      <button type="button" aria-label="Zoom in" title="Zoom in" onClick={() => cameraBus.send({ type: 'zoom', by: 0.75 })} className="grid h-11 w-11 place-items-center hover:bg-mist">
        <Plus className="size-5" />
      </button>
      <span className="mx-2 h-px bg-mist" aria-hidden />
      <button type="button" aria-label="Zoom out" title="Zoom out" onClick={() => cameraBus.send({ type: 'zoom', by: -0.75 })} className="grid h-11 w-11 place-items-center hover:bg-mist">
        <Minus className="size-5" />
      </button>
    </div>
  );
}

const coarsePointer = () => window.matchMedia?.('(pointer: coarse)').matches ?? false;

/** How to move the camera, shown for a few seconds the first time in each mode (per browser session). */
export function GestureHint({ following }: { following: boolean }) {
  const key = `rydex.hint.${following ? 'follow' : 'free'}`;
  const [show, setShow] = useState(() => {
    try {
      return sessionStorage.getItem(key) == null;
    } catch {
      return true;
    }
  });
  useEffect(() => {
    if (!show) return;
    try {
      sessionStorage.setItem(key, '1');
    } catch {
      // private mode: the hint just shows again next time
    }
    const timer = setTimeout(() => setShow(false), 7000);
    return () => clearTimeout(timer);
  }, [show, key]);
  if (!show) return null;
  const touch = coarsePointer();
  const tips = following
    ? touch
      ? ['Drag to look around', 'Pinch to zoom', 'Double-tap to reset']
      : ['Drag to look around', 'Scroll to zoom', 'Double-click to reset', 'C changes the camera']
    : touch
      ? ['Drag to move', 'Pinch to zoom', 'Two fingers to turn and tilt']
      : ['Drag to move', 'Right-drag or Ctrl+drag to turn and tilt', 'Scroll to zoom'];
  return (
    <div role="status" className="rx-hint pointer-events-none absolute top-[9.6rem] left-1/2 z-[1100] w-max max-w-[calc(100%-1.5rem)] -translate-x-1/2 md:top-[8.6rem]">
      <p className="flex flex-wrap justify-center gap-x-3 gap-y-1 rounded-full bg-ink/90 px-4 py-2 text-xs font-bold text-chalk shadow-[var(--shadow-float)]">
        {tips.map((t) => (
          <span key={t}>{t}</span>
        ))}
      </p>
    </div>
  );
}
