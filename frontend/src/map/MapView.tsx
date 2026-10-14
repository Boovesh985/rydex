import L from 'leaflet';
import { useEffect, useMemo, useRef, type ReactNode } from 'react';
import { MapContainer, Marker, Polyline, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import { config } from '../config';
import { lerp, lerpHeading, type LatLng } from '../lib/geo';
import { decodePolyline } from '../lib/polyline';

const INK = '#16181d';
const SEA = '#0b6e79';

interface BaseMapProps {
  center?: LatLng;
  zoom?: number;
  children?: ReactNode;
  className?: string;
}

/** Full-bleed OpenStreetMap; zoom buttons sit bottom-right, clear of the sheets. */
export function BaseMap({ center = [config.center.lat, config.center.lng], zoom = 14, children, className }: BaseMapProps) {
  return (
    <MapContainer
      center={center}
      zoom={zoom}
      zoomControl={false}
      // tiles appear at once: a fade needs animation frames, which background tabs pause
      fadeAnimation={false}
      className={className ?? 'h-full w-full'}
      attributionControl
    >
      <TileLayer
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        maxZoom={19}
      />
      <ZoomControl />
      {children}
    </MapContainer>
  );
}

function ZoomControl() {
  const map = useMap();
  useEffect(() => {
    const control = L.control.zoom({ position: 'bottomright' }).addTo(map);
    return () => {
      control.remove();
    };
  }, [map]);
  return null;
}

/**
 * Fits the view to the given points whenever they change (by value), keeping them clear of
 * the sheet: on the left on wide screens, along the bottom on phones.
 */
export function FitTo({ points, maxZoom = 16 }: { points: LatLng[]; maxZoom?: number }) {
  const map = useMap();
  const key = points.map((p) => p.map((n) => n.toFixed(4)).join(',')).join(';');
  useEffect(() => {
    if (points.length === 0) return;
    const wide = window.innerWidth >= 768;
    const topLeft = L.point(wide ? 450 : 40, 90);
    const bottomRight = L.point(wide ? 70 : 40, wide ? 50 : Math.round(window.innerHeight * 0.5));
    const bounds = L.latLngBounds(points.length === 1 ? [points[0], points[0]] : points);
    map.fitBounds(bounds, { paddingTopLeft: topLeft, paddingBottomRight: bottomRight, maxZoom: points.length === 1 ? 15 : maxZoom, animate: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-fit only when the points change by value
  }, [key, map]);
  return null;
}

/** Calls back with taps on the map. */
export function MapClicks({ onClick }: { onClick: (p: LatLng) => void }) {
  useMapEvents({ click: (e) => onClick([e.latlng.lat, e.latlng.lng]) });
  return null;
}

/** Calls back when the user stops panning or zooming. */
export function MapMoves({ onMove }: { onMove: (center: LatLng) => void }) {
  const map = useMapEvents({ moveend: () => onMove([map.getCenter().lat, map.getCenter().lng]) });
  return null;
}

const pickupIcon = L.divIcon({
  className: 'rx-icon',
  iconSize: [26, 26],
  iconAnchor: [13, 13],
  html: `<svg width="26" height="26" viewBox="0 0 26 26" aria-hidden="true"><circle cx="13" cy="13" r="10" fill="#fff" stroke="${INK}" stroke-width="4"/><circle cx="13" cy="13" r="3" fill="${INK}"/></svg>`,
});

const dropIcon = L.divIcon({
  className: 'rx-icon',
  iconSize: [24, 24],
  iconAnchor: [12, 12],
  html: `<svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><rect x="2" y="2" width="20" height="20" rx="3" fill="${INK}"/><rect x="8" y="8" width="8" height="8" fill="#fff"/></svg>`,
});

export function PickupPin({ at }: { at: LatLng }) {
  return <Marker position={at} icon={pickupIcon} title="Pickup" />;
}

export function DropPin({ at }: { at: LatLng }) {
  return <Marker position={at} icon={dropIcon} title="Drop" />;
}

/** A top-down car in auto yellow; `heading` rotates the inner element. */
function carIcon(color = '#ffc21a', size = 34) {
  return L.divIcon({
    className: 'rx-icon',
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
    html: `<div class="rx-car" style="width:${size}px;height:${size}px">
      <svg width="${size}" height="${size}" viewBox="0 0 34 34" aria-hidden="true">
        <rect x="9" y="3" width="16" height="28" rx="6" fill="${color}" stroke="${INK}" stroke-width="2"/>
        <rect x="11.5" y="9" width="11" height="6" rx="2" fill="${INK}"/>
        <rect x="11.5" y="21" width="11" height="4" rx="1.5" fill="${INK}" opacity=".75"/>
      </svg></div>`,
  });
}

const liveCar = carIcon();

/**
 * The live driver. Updates arrive every ~3 s; the marker glides from where it is towards each
 * new fix over `glideMs` and turns the short way round, so it never jumps.
 */
export function AnimatedCar({ lat, lng, heading, glideMs = 2800 }: { lat: number; lng: number; heading: number | null; glideMs?: number }) {
  const marker = useRef<L.Marker>(null);
  const shown = useRef<{ pos: LatLng; heading: number } | null>(null);

  useEffect(() => {
    const m = marker.current;
    if (!m) return;
    const target: LatLng = [lat, lng];
    const targetHeading = heading ?? shown.current?.heading ?? 0;
    const from = shown.current ?? { pos: target, heading: targetHeading };
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const t = reduced ? 1 : Math.min(1, (now - start) / glideMs);
      const pos = lerp(from.pos, target, t);
      const h = lerpHeading(from.heading, targetHeading, Math.min(1, t * 3));
      shown.current = { pos, heading: h };
      m.setLatLng(pos);
      const el = m.getElement()?.querySelector<HTMLElement>('.rx-car');
      if (el) el.style.transform = `rotate(${h}deg)`;
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    // background tabs pause animation frames: land on the fix anyway
    const snap = setTimeout(() => step(start + glideMs), glideMs + 250);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(snap);
    };
  }, [lat, lng, heading, glideMs]);

  // the initial position only: later moves go through the animation above
  const initial = useMemo<LatLng>(() => [lat, lng], []); // eslint-disable-line react-hooks/exhaustive-deps
  return <Marker ref={marker} position={initial} icon={liveCar} title="Your driver" zIndexOffset={1000} />;
}

/** A static car for the admin map; colour by ride type, rotated by heading. */
export function CarDot({ lat, lng, heading, color, title }: { lat: number; lng: number; heading: number | null; color: string; title: string }) {
  const icon = useMemo(() => {
    const base = carIcon(color, 26);
    const html = (base.options.html as string).replace('class="rx-car"', `class="rx-car" style="transform:rotate(${heading ?? 0}deg)"`);
    return L.divIcon({ ...base.options, html });
  }, [color, heading]);
  return <Marker position={[lat, lng]} icon={icon} title={title} />;
}

/** The planned route: ink casing under a teal line; dashed when it is only a straight-line guess. */
export function RouteLine({ polyline, fallback }: { polyline: string | null | undefined; fallback?: LatLng[] }) {
  const points = useMemo(() => (polyline ? decodePolyline(polyline) : (fallback ?? [])), [polyline, fallback]);
  if (points.length < 2) return null;
  const dashed = !polyline;
  return (
    <>
      <Polyline positions={points} pathOptions={{ color: INK, weight: 8, opacity: 0.9, dashArray: dashed ? '2 12' : undefined, lineCap: 'round' }} />
      <Polyline positions={points} pathOptions={{ color: SEA, weight: 4, opacity: 1, dashArray: dashed ? '2 12' : undefined, lineCap: 'round' }} />
    </>
  );
}
