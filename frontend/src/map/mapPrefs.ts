import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { isNightNow } from './theme';

export type TimeOfDay = 'auto' | 'day' | 'night';

interface MapPrefs {
  /** the simulated traffic layer (ADR 0016) */
  traffic: boolean;
  /** day and night look of every map; auto follows the clock */
  time: TimeOfDay;
  toggleTraffic: () => void;
  setTime: (time: TimeOfDay) => void;
}

/** How this viewer likes the maps, remembered in this browser. */
export const useMapPrefs = create<MapPrefs>()(
  persist(
    (set) => ({
      traffic: true,
      time: 'auto',
      toggleTraffic: () => set((s) => ({ traffic: !s.traffic })),
      setTime: (time) => set({ time }),
    }),
    { name: 'rydex.map', storage: createJSONStorage(() => localStorage) },
  ),
);

/** auto -> day -> night -> auto */
export const nextTime = (t: TimeOfDay): TimeOfDay => (t === 'auto' ? 'day' : t === 'day' ? 'night' : 'auto');

/** Whether the maps are in their night look now (re-checked every minute in auto). */
export function useNight(): boolean {
  const time = useMapPrefs((s) => s.time);
  const [clockNight, setClockNight] = useState(isNightNow);
  useEffect(() => {
    if (time !== 'auto') return;
    const timer = setInterval(() => setClockNight(isNightNow()), 60_000);
    return () => clearInterval(timer);
  }, [time]);
  return time === 'auto' ? clockNight : time === 'night';
}
