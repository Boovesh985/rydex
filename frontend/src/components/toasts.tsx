import { X } from 'lucide-react';
import { useEffect } from 'react';
import { useToasts, type Tone, type Toast } from './toastStore';

const bar: Record<Tone, string> = { info: 'bg-sea', good: 'bg-go', bad: 'bg-signal' };

function ToastItem({ t }: { t: Toast }) {
  const dismiss = useToasts((s) => s.dismiss);
  useEffect(() => {
    const timer = setTimeout(() => dismiss(t.id), 6000);
    return () => clearTimeout(timer);
  }, [t.id, dismiss]);
  return (
    <div className="pointer-events-auto flex overflow-hidden rounded-xl bg-ink text-chalk shadow-[var(--shadow-float)]">
      <span className={`w-1.5 shrink-0 ${bar[t.tone]}`} aria-hidden />
      <div className="min-w-0 flex-1 px-3 py-2.5">
        <p className="font-display-wide text-sm font-bold">{t.title}</p>
        {t.message && <p className="mt-0.5 text-sm text-chalk/80">{t.message}</p>}
      </div>
      <button className="px-3 text-chalk/60 hover:text-chalk" onClick={() => dismiss(t.id)} aria-label="Dismiss">
        <X className="size-4" />
      </button>
    </div>
  );
}

/** Ride and payment updates, top of the screen, newest last. */
export function Toaster() {
  const toasts = useToasts((s) => s.toasts);
  return (
    <div className="pointer-events-none fixed inset-x-0 top-16 z-[2000] mx-auto flex w-full max-w-md flex-col gap-2 px-4" aria-live="polite">
      {toasts.map((t) => (
        <ToastItem key={t.id} t={t} />
      ))}
    </div>
  );
}
