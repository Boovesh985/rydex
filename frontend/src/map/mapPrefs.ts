import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { isNightNow, nextDayNightSwitch } from './theme';

export type TimeOfDay = 'auto' | 'day' | 'night';

/** Where the follow camera sits behind the car (realistic mode). */
export type CameraView = 'chase' | 'high' | 'street' | 'top';
export const CAMERA_VIEWS: CameraView[] = ['chase', 'high', 'street', 'top'];
export const CAMERA_VIEW_LABELS: Record<CameraView, string> = { chase: 'Chase', high: 'High chase', street: 'Street level', top: 'Top-down' };

interface MapPrefs {
  /** the simulated traffic layer (ADR 0016) */
  traffic: boolean;
  /** day and night look of every map: auto is day from 6 am to 6 pm, night from 6 pm to 6 am */
  time: TimeOfDay;
  /** a day or night picked by hand holds until this moment (the next 6 am or 6 pm), then auto again */
  timeUntil: number | null;
  /** the follow camera's view */
  camera: CameraView;
  toggleTraffic: () => void;
  /** day or night by hand, until the clock's next change; picking what the clock shows anyway is auto */
  setNight: (night: boolean) => void;
  nextCamera: () => void;
}

/** How this viewer likes the maps, remembered in this browser. */
export const useMapPrefs = create<MapPrefs>()(
  persist(
    (set) => ({
      traffic: true,
      time: 'auto',
      timeUntil: null,
      camera: 'chase',
      toggleTraffic: () => set((s) => ({ traffic: !s.traffic })),
      setNight: (night) => set(night === isNightNow() ? { time: 'auto', timeUntil: null } : { time: night ? 'night' : 'day', timeUntil: nextDayNightSwitch() }),
      nextCamera: () => set((s) => ({ camera: CAMERA_VIEWS[(CAMERA_VIEWS.indexOf(s.camera) + 1) % CAMERA_VIEWS.length] })),
    }),
    { name: 'rydex.map', storage: createJSONStorage(() => localStorage) },
  ),
);

/**
 * The look in force at `now`: a hand-picked day or night until its time is up (a choice saved before
 * choices expired has no end, and counts as up), otherwise auto.
 */
export function timeInForce(time: TimeOfDay, until: number | null | undefined, now = Date.now()): TimeOfDay {
  return time !== 'auto' && until != null && now < until ? time : 'auto';
}

/** The day/night setting in force, re-checked every minute (the clock moves on, a choice runs out). */
export function useTimeOfDay(): { time: TimeOfDay; night: boolean } {
  const time = useMapPrefs((s) => s.time);
  const until = useMapPrefs((s) => s.timeUntil);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);
  const inForce = timeInForce(time, until, now);
  return { time: inForce, night: inForce === 'auto' ? isNightNow(new Date(now)) : inForce === 'night' };
}

/** Whether the maps are in their night look now. */
export function useNight(): boolean {
  return useTimeOfDay().night;
}
