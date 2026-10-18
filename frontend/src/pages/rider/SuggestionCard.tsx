import { Clock3, Footprints, Route, Sparkles, Tag } from 'lucide-react';
import type { Suggestion, SuggestionHighlight } from '../../api/types';
import { Button } from '../../components/ui';
import { cityClock, inr, rideTypeLabel } from '../../lib/format';

const CHIPS: Record<SuggestionHighlight, { label: string; icon: typeof Clock3 }> = {
  SHORTER_WAIT: { label: 'Shorter wait time', icon: Clock3 },
  BETTER_PRICE: { label: 'Better price', icon: Tag },
  SMARTER_ROUTE: { label: 'Smarter route', icon: Route },
};

interface Props {
  suggestion: Suggestion;
  /** the rider tapped "Use suggestion" (the card then says what it changed) */
  used: boolean;
  onUse: () => void;
  onDismiss: () => void;
  now?: Date;
}

/** The AI Smart Suggestion: which ride, when and from where, and why (master prompt §11). */
export function SuggestionCard({ suggestion: s, used, onUse, onDismiss, now = new Date() }: Props) {
  const best = s.bestPickupTime;
  const walk = s.alternativePickup;
  const type = rideTypeLabel[s.recommendedRideType];
  return (
    <section aria-label="Smart suggestion" className="space-y-3 rounded-2xl bg-ink p-4 text-chalk shadow-[var(--shadow-float)]">
      <div className="flex items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 font-display-wide text-[0.7rem] font-bold tracking-[0.16em] text-auto uppercase">
          <Sparkles className="size-3.5" aria-hidden /> Smart suggestion
        </p>
        <span className="text-xs text-chalk/60" title={`Model ${s.modelVersion}`}>
          {Math.round(s.confidence * 100)}% sure
        </span>
      </div>

      {s.highlights.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label="Why it is better">
          {s.highlights.map((h) => {
            const { label, icon: Icon } = CHIPS[h];
            return (
              <li key={h} className="flex items-center gap-1 rounded-full bg-chalk/10 px-2.5 py-1 text-xs font-bold text-chalk">
                <Icon className="size-3.5 text-auto" aria-hidden /> {label}
              </li>
            );
          })}
        </ul>
      )}

      <p className="font-display-wide text-lg leading-snug font-black">
        {type} · {best.offsetMinutes > 0 ? `book at ${cityClock(now, best.offsetMinutes)}` : `${best.predictedWaitMin} min wait`} ·{' '}
        <span className="font-mono tabular">{inr(best.predictedFare)}</span>
      </p>
      {best.offsetMinutes > 0 && (
        <p className="text-sm text-chalk/80">
          In {best.offsetMinutes} min the fare is predicted to drop by <strong className="text-auto">{inr(best.savingsVsNow)}</strong>.
        </p>
      )}
      {walk && (
        <p className="flex items-start gap-1.5 text-sm text-chalk/80">
          <Footprints className="mt-0.5 size-4 shrink-0 text-auto" aria-hidden />
          Walk {walk.walkMeters} m to be picked up in about {walk.predictedWaitMin} min.
        </p>
      )}

      <ul className="space-y-1 text-sm text-chalk/80">
        {s.reasons.map((r) => (
          <li key={r} className="flex gap-2">
            <span className="mt-2 size-1 shrink-0 rounded-full bg-auto" aria-hidden />
            {r}
          </li>
        ))}
      </ul>

      {used ? (
        <p role="status" className="text-sm font-bold text-auto">
          Applied: {type}
          {walk ? ', and the pickup moved to the suggested spot' : ''}.
        </p>
      ) : (
        <div className="flex items-center gap-3">
          <Button className="min-h-10 px-4" onClick={onUse}>
            Use suggestion
          </Button>
          <button type="button" className="text-sm text-chalk/70 underline underline-offset-4 hover:text-chalk" onClick={onDismiss}>
            Not now
          </button>
        </div>
      )}
    </section>
  );
}
