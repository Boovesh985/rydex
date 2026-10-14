import { useEffect, useRef, useState } from 'react';
import { driversApi } from '../../api/endpoints';
import { config } from '../../config';
import { along, pathLength, type LatLng } from '../../lib/geo';
import { realtime } from '../../realtime/stomp';

export interface Position {
  lat: number;
  lng: number;
  heading: number | null;
  /** m/s */
  speed: number | null;
}

/** The phone's GPS (watchPosition) while `enabled`. */
export function useGpsPosition(enabled: boolean) {
  const [pos, setPos] = useState<Position | null>(null);
  const [error, setError] = useState('');
  const unsupported = enabled && !navigator.geolocation;
  useEffect(() => {
    if (!enabled || !navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (p) => {
        setError('');
        setPos({
          lat: p.coords.latitude,
          lng: p.coords.longitude,
          heading: Number.isFinite(p.coords.heading) ? p.coords.heading : null,
          speed: Number.isFinite(p.coords.speed) ? p.coords.speed : null,
        });
      },
      (e) => setError(e.code === e.PERMISSION_DENIED ? 'Location is blocked. Allow it in the browser, or simulate driving.' : 'Waiting for GPS…'),
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 15_000 },
    );
    return () => navigator.geolocation.clearWatch(id);
  }, [enabled]);
  return { pos, error: unsupported ? 'This browser cannot share its location. Turn on "Simulate driving" instead.' : error };
}

/**
 * A pretend car for demos (the "Simulate driving" switch). It stays where it is put (tap the map)
 * and, given a route, drives along it at `speedKmh`, ticking once a second.
 */
export function useSimulatedPosition(start: LatLng | null, route: LatLng[] | null, speedKmh: number) {
  const [pos, setPos] = useState<Position>(() => {
    const [lat, lng] = start ?? [config.center.lat, config.center.lng];
    return { lat, lng, heading: 0, speed: 0 };
  });
  const travelled = useRef(0);

  // a new route starts from its beginning
  useEffect(() => {
    travelled.current = 0;
  }, [route]);

  useEffect(() => {
    if (!route || route.length < 2) return;
    const total = pathLength(route);
    const timer = setInterval(() => {
      const mps = (speedKmh * 1000) / 3600;
      travelled.current = Math.min(total, travelled.current + mps);
      const step = along(route, travelled.current);
      setPos({ lat: step.point[0], lng: step.point[1], heading: Math.round(step.heading), speed: step.done ? 0 : mps });
    }, 1000);
    return () => clearInterval(timer);
  }, [route, speedKmh]);

  const moveTo = (p: LatLng) => {
    travelled.current = 0;
    setPos({ lat: p[0], lng: p[1], heading: null, speed: 0 });
  };
  return { pos, moveTo };
}

/**
 * Sends the position every 3 s while `enabled`: over the WebSocket (tracking-service broadcasts it
 * to the rider), or over REST to driver-service when the socket is down so matching still sees us.
 */
export function useLocationReporter(pos: Position | null, enabled: boolean) {
  const latest = useRef(pos);
  useEffect(() => {
    latest.current = pos;
  }, [pos]);

  useEffect(() => {
    if (!enabled) return;
    const send = () => {
      const p = latest.current;
      if (!p) return;
      const body = {
        lat: Number(p.lat.toFixed(6)),
        lng: Number(p.lng.toFixed(6)),
        heading: p.heading == null ? undefined : Math.round(((p.heading % 360) + 360) % 360),
        speed: p.speed == null ? undefined : Math.min(100, Math.max(0, Number(p.speed.toFixed(1)))),
        ts: Date.now(),
      };
      if (!realtime.publish('/app/driver.location', body)) {
        void driversApi.reportLocation(body).catch(() => undefined);
      }
    };
    send();
    const timer = setInterval(send, 3000);
    return () => clearInterval(timer);
  }, [enabled]);
}
