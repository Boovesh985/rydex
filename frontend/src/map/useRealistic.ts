import { useState } from 'react';
import { isNightNow } from './theme';

/**
 * Realistic mode's switches for one screen: on/off, whether the camera follows the car, and
 * day or night (night by default after sunset). `available` false (e.g. the ride ended) turns it off.
 */
export function useRealistic(available: boolean) {
  const [on, setOn] = useState(false);
  const [following, setFollowing] = useState(true);
  const [night, setNight] = useState(isNightNow);
  const active = on && available;
  return {
    active,
    following: active && following,
    night: active && night,
    enter: () => {
      setFollowing(true);
      setOn(true);
    },
    exit: () => setOn(false),
    toggleFollow: () => setFollowing((f) => !f),
    stopFollowing: () => setFollowing(false),
    toggleNight: () => setNight((n) => !n),
  };
}
