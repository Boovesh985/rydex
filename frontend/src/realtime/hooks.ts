import { useEffect, useRef, useSyncExternalStore } from 'react';
import { realtime } from './stomp';

/** Subscribes while mounted (and while `destination` is set); the latest handler is always used. */
export function useSubscription<T>(destination: string | null, handler: (body: T) => void) {
  const ref = useRef(handler);
  useEffect(() => {
    ref.current = handler;
  });
  useEffect(() => {
    if (!destination) return;
    return realtime.subscribe(destination, (body) => ref.current(body as T));
  }, [destination]);
}

/** Whether the live connection is up. */
export function useConnected(): boolean {
  return useSyncExternalStore(
    (cb) => realtime.onConnection(cb),
    () => realtime.connected,
  );
}

/** Runs `effect` on every (re)connect after the first render: re-read state pushes may have missed. */
export function useOnReconnect(effect: () => void) {
  const ref = useRef(effect);
  useEffect(() => {
    ref.current = effect;
  });
  useEffect(() => realtime.onConnection((up) => up && ref.current()), []);
}
