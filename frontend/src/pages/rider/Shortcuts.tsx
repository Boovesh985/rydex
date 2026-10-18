import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Briefcase, History, House } from 'lucide-react';
import { meApi, ridesApi } from '../../api/endpoints';
import { errorMessage } from '../../api/http';
import type { GeoPoint, PlaceKind, SavedPlace } from '../../api/types';
import { ErrorNote } from '../../components/ui';
import { shortAddress } from '../../lib/format';
import { haversine } from '../../lib/geo';

const KIND_LABEL: Record<PlaceKind, string> = { HOME: 'Home', WORK: 'Work' };
const KIND_ICON = { HOME: House, WORK: Briefcase };
/** within this, two points are the same place (a pin dropped on the same building) */
const SAME_PLACE_M = 120;
const RECENTS = 3;

const near = (a: GeoPoint, b: GeoPoint) => haversine([a.lat, a.lng], [b.lat, b.lng]) < SAME_PLACE_M;

/**
 * Where the rider usually goes, one tap away: their Home and Work (saved on the server, so they
 * follow them to any device) and the last few places they went. With a drop chosen: save it as
 * Home or Work, or forget it.
 */
export function Shortcuts({ pickup, drop, onDrop }: { pickup: GeoPoint | null; drop: GeoPoint | null; onDrop: (p: GeoPoint) => void }) {
  const queryClient = useQueryClient();
  const saved = useQuery({ queryKey: ['me', 'places'], queryFn: meApi.places, staleTime: 5 * 60_000 });
  const history = useQuery({ queryKey: ['rides', 'recent-drops'], queryFn: () => ridesApi.list(0, 20), staleTime: 60_000 });
  const places = saved.data ?? [];

  const save = useMutation({
    mutationFn: ({ kind, place }: { kind: PlaceKind; place: GeoPoint }) => meApi.savePlace(kind, place),
    onSuccess: (p) => queryClient.setQueryData<SavedPlace[]>(['me', 'places'], (old = []) => [...old.filter((o) => o.kind !== p.kind), p].sort(byKind)),
  });
  const forget = useMutation({
    mutationFn: (kind: PlaceKind) => meApi.forgetPlace(kind),
    onSuccess: (_, kind) => queryClient.setQueryData<SavedPlace[]>(['me', 'places'], (old = []) => old.filter((o) => o.kind !== kind)),
  });

  if (drop) {
    const is = places.find((p) => near(p.place, drop));
    return (
      <div className="space-y-1">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          {is ? (
            <>
              <span className="font-bold">Saved as {KIND_LABEL[is.kind]}</span>
              <button type="button" className="text-slate underline underline-offset-4" disabled={forget.isPending} onClick={() => forget.mutate(is.kind)}>
                Forget it
              </button>
            </>
          ) : (
            (['HOME', 'WORK'] as PlaceKind[]).map((kind) => {
              const current = places.find((p) => p.kind === kind);
              return (
                <button
                  key={kind}
                  type="button"
                  className="font-bold underline underline-offset-4 disabled:opacity-50"
                  disabled={save.isPending}
                  title={current ? `Replaces ${shortAddress(current.place.address)}` : undefined}
                  onClick={() => save.mutate({ kind, place: drop })}
                >
                  Save as {KIND_LABEL[kind]}
                </button>
              );
            })
          )}
        </div>
        <ErrorNote>{(save.error || forget.error) && errorMessage(save.error ?? forget.error)}</ErrorNote>
      </div>
    );
  }

  // the last places the rider went, newest first: not the saved ones, not where they are now
  const recents: GeoPoint[] = [];
  for (const r of history.data?.items ?? []) {
    const p = r.drop;
    if (places.some((s) => near(s.place, p)) || (pickup && near(pickup, p)) || recents.some((q) => near(q, p))) continue;
    recents.push(p);
    if (recents.length === RECENTS) break;
  }
  if (places.length === 0 && recents.length === 0) return null;

  return (
    <div role="group" aria-label="Shortcuts" className="flex flex-wrap gap-2">
      {places.map(({ kind, place }) => {
        const Icon = KIND_ICON[kind];
        return (
          <button key={kind} type="button" onClick={() => onDrop(place)} className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-ink px-3 py-1.5 text-sm font-bold text-chalk hover:bg-ink-2">
            <Icon className="size-4 shrink-0" aria-hidden />
            {KIND_LABEL[kind]}
            <span className="truncate font-normal text-chalk/70">{shortAddress(place.address)}</span>
          </button>
        );
      })}
      {recents.map((p) => (
        <button
          key={`${p.lat},${p.lng}`}
          type="button"
          onClick={() => onDrop(p)}
          aria-label={`Recent: ${p.address ?? 'Pinned location'}`}
          className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-chalk px-3 py-1.5 text-sm ring-1 ring-mist hover:ring-ink/40"
        >
          <History className="size-4 shrink-0 text-slate" aria-hidden />
          <span className="truncate">{shortAddress(p.address)}</span>
        </button>
      ))}
    </div>
  );
}

const byKind = (a: SavedPlace, b: SavedPlace) => a.kind.localeCompare(b.kind);
