import { LoaderCircle } from 'lucide-react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'dark' | 'quiet' | 'danger';

const variants: Record<Variant, string> = {
  primary: 'bg-auto text-ink hover:bg-auto-deep shadow-[inset_0_-3px_0_rgb(22_24_29/0.18)]',
  dark: 'bg-ink text-chalk hover:bg-ink-2',
  quiet: 'bg-paper text-ink ring-1 ring-mist hover:ring-ink/40',
  danger: 'bg-paper text-signal ring-1 ring-signal/40 hover:bg-signal-soft',
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  busy?: boolean;
  block?: boolean;
}

export function Button({ variant = 'primary', busy, block, className = '', children, disabled, ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex min-h-12 items-center justify-center gap-2 rounded-xl px-5 font-display-wide text-[0.95rem] font-bold transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${block ? 'w-full' : ''} ${className}`}
    >
      {busy && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
}

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  hint?: string;
}

export function Field({ label, hint, id, className = '', ...rest }: FieldProps) {
  const inputId = id ?? `f-${label.toLowerCase().replace(/\W+/g, '-')}`;
  return (
    <label htmlFor={inputId} className="block">
      <span className="mb-1 block text-sm font-bold text-ink">{label}</span>
      <input
        id={inputId}
        {...rest}
        className={`h-12 w-full rounded-xl border border-mist bg-paper px-4 text-base text-ink placeholder:text-slate/70 focus:border-ink focus:outline-none ${className}`}
      />
      {hint && <span className="mt-1 block text-xs text-slate">{hint}</span>}
    </label>
  );
}

/**
 * The panel over the map: a bottom sheet on phones, a floating column on wider screens.
 */
export function Sheet({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <section
      className={`pointer-events-auto absolute inset-x-0 bottom-0 z-[1000] max-h-[78dvh] overflow-y-auto rounded-t-[var(--radius-sheet)] bg-paper px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] shadow-[var(--shadow-sheet)] md:inset-x-auto md:top-20 md:bottom-auto md:left-4 md:w-[400px] md:max-h-[calc(100dvh-6rem)] md:rounded-[var(--radius-sheet)] md:pb-4 md:shadow-[var(--shadow-float)] ${className}`}
    >
      <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-mist md:hidden" aria-hidden />
      {children}
    </section>
  );
}

/**
 * Signature element: an auto-rickshaw fare meter. Ink housing, yellow segment-style digits.
 * Used for the one number that matters right now: the fare, the ETA.
 */
export function Meter({ label, value, unit, sub, tone = 'auto' }: { label: string; value: string; unit?: string; sub?: ReactNode; tone?: 'auto' | 'go' }) {
  return (
    <div className="rounded-2xl bg-ink p-1.5 text-chalk shadow-[var(--shadow-float)]" role="status" aria-label={`${label}: ${value}${unit ? ' ' + unit : ''}`}>
      <div className="flex items-stretch gap-3 rounded-xl border border-white/10 bg-[radial-gradient(120%_140%_at_0%_0%,#262a33,#101216)] px-4 py-3">
        <div className="flex min-w-0 flex-1 flex-col justify-center">
          <span className="font-display-wide text-[0.68rem] font-bold tracking-[0.18em] text-chalk/60 uppercase">{label}</span>
          {sub && <span className="mt-1 truncate text-sm text-chalk/85">{sub}</span>}
        </div>
        <div
          className={`flex items-baseline gap-1 self-center rounded-lg bg-black/45 px-3 py-1 font-mono font-bold tabular ${tone === 'go' ? 'text-[#7ee2a4]' : 'text-auto'} [text-shadow:0_0_12px_currentColor]`}
        >
          <span className="text-[2rem] leading-none">{value}</span>
          {unit && <span className="text-sm opacity-80">{unit}</span>}
        </div>
      </div>
    </div>
  );
}

/** An Indian HSRP number plate: IND strip, white plate, ink border. */
export function Plate({ number }: { number: string }) {
  return (
    <span className="inline-flex items-stretch overflow-hidden rounded-md border-2 border-ink bg-paper font-mono text-[0.95rem] leading-none font-bold tracking-wider text-ink">
      <span className="flex flex-col items-center justify-center bg-[#1d4fa8] px-1 text-[0.5rem] leading-tight text-white" aria-hidden>
        <span>●</span>
        <span>IND</span>
      </span>
      <span className="px-2 py-1.5 whitespace-nowrap">{number}</span>
    </span>
  );
}

/** The 4-digit ride PIN as separate tiles, read one by one by screen readers. */
export function PinTiles({ pin }: { pin: string }) {
  return (
    <div className="flex gap-1.5" aria-label={`Ride PIN ${pin.split('').join(' ')}`}>
      {pin.split('').map((d, i) => (
        <span key={i} className="grid h-12 w-10 place-items-center rounded-lg bg-ink font-mono text-2xl font-bold text-auto" aria-hidden>
          {d}
        </span>
      ))}
    </div>
  );
}

export function Spinner({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 text-slate" role="status">
      <LoaderCircle className="size-5 animate-spin" aria-hidden />
      <span>{label}</span>
    </div>
  );
}

export function ErrorNote({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <p role="alert" className="rounded-xl bg-signal-soft px-3 py-2 text-sm font-bold text-signal">
      {children}
    </p>
  );
}

export function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="font-display-wide text-[0.7rem] font-bold tracking-[0.16em] text-slate uppercase">{children}</p>;
}
