import { MapPin } from 'lucide-react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { placesApi } from '../api/endpoints';
import { errorMessage } from '../api/http';
import type { GeoPoint, Place } from '../api/types';

interface Props {
  label: string;
  marker: ReactNode;
  value: GeoPoint | null;
  placeholder: string;
  near?: GeoPoint | null;
  onPick: (p: GeoPoint) => void;
  onFocus?: () => void;
}

/**
 * Address search with suggestions (ARIA combobox). Searches 350 ms after typing stops, from
 * 3 characters: the backend proxies OpenStreetMap Nominatim, which allows one request a second.
 */
export function PlaceSearch({ label, marker, value, placeholder, near, onPick, onFocus }: Props) {
  const [text, setText] = useState(value?.address ?? '');
  const [results, setResults] = useState<Place[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [note, setNote] = useState('');
  const editing = useRef(false);
  const listId = useId();

  // follow the value when it changes elsewhere (map tap, swap, geolocation)
  useEffect(() => {
    if (!editing.current) setText(value?.address ?? '');
  }, [value]);

  useEffect(() => {
    const q = text.trim();
    if (!editing.current || q.length < 3) {
      setResults([]);
      return;
    }
    const ctrl = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const found = await placesApi.search(q, near ?? undefined, ctrl.signal);
        setResults(found);
        setNote(found.length ? '' : 'No places found. Try another name, or tap the map.');
        setOpen(true);
        setActive(-1);
      } catch (e) {
        if (!ctrl.signal.aborted) {
          setNote(errorMessage(e));
          setOpen(true);
        }
      }
    }, 350);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [text, near]);

  const pick = (p: Place) => {
    editing.current = false;
    setText(p.name);
    setOpen(false);
    setResults([]);
    onPick({ lat: p.lat, lng: p.lng, address: p.address.startsWith(p.name) ? p.address : `${p.name}, ${p.address}` });
  };

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!open || results.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % results.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i <= 0 ? results.length - 1 : i - 1));
    } else if (e.key === 'Enter' && active >= 0) {
      e.preventDefault();
      pick(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className="relative">
      <label className="flex h-12 items-center gap-3 rounded-xl bg-chalk px-3 focus-within:ring-2 focus-within:ring-ink">
        <span className="shrink-0" aria-hidden>
          {marker}
        </span>
        <span className="sr-only">{label}</span>
        <input
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
          className="h-full min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-slate/80"
          placeholder={placeholder}
          value={text}
          onFocus={(e) => {
            e.target.select();
            onFocus?.();
          }}
          onChange={(e) => {
            editing.current = true;
            setText(e.target.value);
          }}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKey}
        />
      </label>
      {open && (
        <ul id={listId} role="listbox" className="mt-1 max-h-64 overflow-y-auto rounded-xl bg-paper py-1 shadow-[var(--shadow-float)] ring-1 ring-mist">
          {results.map((p, i) => (
            <li
              key={`${p.lat},${p.lng},${i}`}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              className={`flex cursor-pointer gap-3 px-3 py-2.5 ${i === active ? 'bg-chalk' : 'hover:bg-chalk'}`}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(p);
              }}
            >
              <MapPin className="mt-0.5 size-4 shrink-0 text-slate" aria-hidden />
              <span className="min-w-0">
                <span className="block truncate font-bold">{p.name}</span>
                <span className="block truncate text-sm text-slate">{p.address}</span>
              </span>
            </li>
          ))}
          {note && <li className="px-3 py-2.5 text-sm text-slate">{note}</li>}
        </ul>
      )}
    </div>
  );
}
