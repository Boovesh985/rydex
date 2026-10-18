import { useState } from 'react';
import { useMapPrefs, useNight } from './mapPrefs';

/**
 * Realistic mode's switches for one screen: on/off and whether the camera follows the car. Day or
 * night is the viewer's map preference (day 6 am to 6 pm by the clock, or picked by hand until the
 * clock's next change), shared by every map and both views. `available` false (e.g. the ride ended)
 * turns realistic mode off.
 */
export function useRealistic(available: boolean) {
  const [on, setOn] = useState(false);
  const [following, setFollowing] = useState(true);
  const night = useNight();
  const setNight = useMapPrefs((s) => s.setNight);
  const active = on && available;
  return {
    active,
    following: active && following,
    night,
    enter: () => {
      setFollowing(true);
      setOn(true);
    },
    exit: () => setOn(false),
    toggleFollow: () => setFollowing((f) => !f),
    stopFollowing: () => setFollowing(false),
    toggleNight: () => setNight(!night),
  };
}
