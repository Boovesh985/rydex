import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { isNightNow } from './theme';

export type TimeOfDay = 'auto' | 'day' | 'night';

/** Where the follow camera sits behind the car (realistic mode). */
export type CameraView = 'chase' | 'high' | 'street' | 'top';
export const CAMERA_VIEWS: CameraView[] = ['chase', 'high', 'street', 'top'];
export const CAMERA_VIEW_LABELS: Record<CameraView, string> = { chase: 'Chase', high: 'High chase', street: 'Street level', top: 'Top-down' };

interface MapPrefs {
  /** the simulated traffic layer (ADR 0016) */
  traffic: boolean;
  /** day and night look of every map; auto follows the clock */
  time: TimeOfDay;
  /** the follow camera's view */
  camera: CameraView;
  toggleTraffic: () => void;
  setTime: (time: TimeOfDay) => void;
  nextCamera: () => void;
}

/** How this viewer likes the maps, remembered in this browser. */
export const useMapPrefs = create<MapPrefs>()(
  persist(
    (set) => ({
      traffic: true,
      time: 'auto',
      camera: 'chase',
      toggleTraffic: () => set((s) => ({ traffic: !s.traffic })),
      setTime: (time) => set({ time }),
      nextCamera: () => set((s) => ({ camera: CAMERA_VIEWS[(CAMERA_VIEWS.indexOf(s.camera) + 1) % CAMERA_VIEWS.length] })),
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
