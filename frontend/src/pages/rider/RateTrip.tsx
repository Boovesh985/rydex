import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Star } from 'lucide-react';
import { useState } from 'react';
import { ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { Ride } from '../../api/types';
import { Button, ErrorNote, Eyebrow } from '../../components/ui';

const WORDS = ['', 'Bad', 'Poor', 'Okay', 'Good', 'Great'];

/** After the trip: the rider's stars (and a word, if they like), once. Drivers never see who gave what. */
export function RateTrip({ ride }: { ride: Ride }) {
  const queryClient = useQueryClient();
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const rate = useMutation({
    mutationFn: () => ridesApi.rate(ride.id, stars, comment.trim()),
    onSuccess: (rated) => queryClient.setQueryData(['ride', ride.id], rated),
  });

  if (ride.riderRating != null) {
    return (
      <p className="flex items-center gap-2 rounded-xl bg-chalk p-3 text-sm">
        <span className="flex" aria-label={`You rated this trip ${ride.riderRating} out of 5`}>
          {[1, 2, 3, 4, 5].map((n) => (
            <Star key={n} className={`size-4 ${n <= ride.riderRating! ? 'fill-auto text-auto-deep' : 'text-mist'}`} aria-hidden />
          ))}
        </span>
        Thanks for rating your trip.
      </p>
    );
  }

  return (
    <section aria-label="Rate your trip" className="space-y-2 rounded-xl bg-chalk p-3">
      <Eyebrow>How was your trip?</Eyebrow>
      <div role="radiogroup" aria-label="Stars" className="flex items-center gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={stars === n}
            aria-label={`${n} star${n > 1 ? 's' : ''}: ${WORDS[n]}`}
            onClick={() => setStars(n)}
            className="grid size-10 place-items-center rounded-full hover:bg-paper"
          >
            <Star className={`size-7 ${n <= stars ? 'fill-auto text-auto-deep' : 'text-slate'}`} aria-hidden />
          </button>
        ))}
        {stars > 0 && <span className="ml-1 text-sm font-bold">{WORDS[stars]}</span>}
      </div>
      {stars > 0 && (
        <>
          <label className="block">
            <span className="sr-only">A word for the team (optional)</span>
            <textarea
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={280}
              rows={2}
              placeholder="Anything to add? (optional)"
              className="w-full resize-none rounded-xl bg-paper px-3 py-2 text-sm ring-1 ring-mist focus:ring-2 focus:ring-ink focus:outline-none"
            />
          </label>
          <ErrorNote>{rate.error && errorMessage(rate.error)}</ErrorNote>
          <Button block variant="dark" busy={rate.isPending} onClick={() => rate.mutate()}>
            Rate {stars} star{stars > 1 ? 's' : ''}
          </Button>
        </>
      )}
    </section>
  );
}
