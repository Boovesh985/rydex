import type { RideType } from '../api/types';
import { VEHICLE_PAINT } from '../lib/vehicles';

const INK = '#16181d';

function Wheel({ x, r }: { x: number; r: number }) {
  return (
    <>
      <circle cx={x} cy={28 - r - 0.6} r={r} fill={INK} />
      <circle cx={x} cy={28 - r - 0.6} r={r * 0.42} fill="#c3c8ce" />
    </>
  );
}

// cab silhouettes and their windows, front to the left
const CAB_BODY: Record<string, string> = {
  ECONOMY: 'M3 20 V15 Q3 12 7 11.5 L14 10.8 L19 5.8 Q21 5 30 5 L37 5.6 L42 10.5 Q45 11.4 45 14.5 V20Z',
  COMFORT: 'M2 20 V15.5 Q2 12.5 6 12 L13 11 L18.5 6.4 Q20 5.6 29 5.6 L34 6.4 L38.5 11 L44 11.7 Q46 12.2 46 15 V20Z',
  XL: 'M2 20 V13.5 Q2 11 5.5 10.6 L11 10 L15.5 4.5 Q16.5 3.6 26 3.6 L42 4 Q45 4.4 45.5 8 L46 20Z',
  PREMIUM: 'M1.5 20 V15.8 Q1.5 12.8 5.5 12.2 L13 11.2 L19 6.6 Q20.5 5.9 29.5 5.9 L34.5 6.6 L39.5 11.2 L45 11.9 Q46.8 12.4 46.8 15.2 V20Z',
};
const CAB_GLASS: Record<string, string> = {
  ECONOMY: 'M15.5 10.8 L19.8 6.7 H29.5 V10.8Z M31.5 6.7 H36.4 L40.3 10.8 H31.5Z',
  COMFORT: 'M15 11 L19.2 7.3 H27.5 V11Z M29.5 7.3 H33.4 L36.8 11 H29.5Z',
  XL: 'M12.5 10 L16.4 5.2 H26 V10Z M28 5.2 H36.5 V10 H28Z M38.5 5.2 H43 L43.6 10 H38.5Z',
  PREMIUM: 'M15.5 11.2 L19.8 7.5 H28 V11.2Z M30 7.5 H34 L37.6 11.2 H30Z',
};

/** A side view of each ride type's vehicle, in its paint, for the ride pickers. */
export function VehicleIcon({ type, className }: { type: RideType; className?: string }) {
  const { body, roof } = VEHICLE_PAINT[type];
  let drawing;
  if (type === 'BIKE') {
    // drawn facing right, mirrored to face left like the others
    drawing = (
      <g transform="matrix(-1 0 0 1 48 0)">
        <Wheel x={11} r={5.6} />
        <Wheel x={37} r={5.6} />
        <path d="M11 21.8 L19 13.5 H29 L37 21.8" fill="none" stroke={INK} strokeWidth="1.8" strokeLinejoin="round" />
        <path d="M34.5 21.8 L31 9.5 H34" fill="none" stroke={INK} strokeWidth="1.8" strokeLinecap="round" />
        <path d="M18 13.8 Q20 9.5 26 10 L30 11.5 L29 14.5 H18Z" fill={body} stroke={INK} strokeWidth="1.2" strokeLinejoin="round" />
        <path d="M13 13 H21" stroke={INK} strokeWidth="2.6" strokeLinecap="round" />
        <path d="M20 12 L23 4.8 L28.5 6 L31 10" fill="none" stroke="#f2b705" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="23.6" cy="3.6" r="2.9" fill="#1b1d22" />
      </g>
    );
  } else if (type === 'AUTO') {
    drawing = (
      <>
        <path d="M5 21 Q4.5 13 9 12 L12 6 Q24 2.5 44 4.5 L44.5 21Z" fill={body} stroke={INK} strokeWidth="1.2" strokeLinejoin="round" />
        <path d="M10.5 6.5 Q24 1.5 45 3.5 L45.5 7 Q26 5.4 12 8.2Z" fill={roof} stroke={INK} strokeWidth="1.2" strokeLinejoin="round" />
        <path d="M17 8 H26 V15 H17Z M29 7.6 H40 V14.5 H29Z" fill="#cfe3ea" opacity=".85" />
        <circle cx="6.4" cy="14.5" r="1.4" fill="#fff6c8" stroke={INK} strokeWidth=".8" />
        <Wheel x={10} r={4} />
        <Wheel x={37} r={4.6} />
      </>
    );
  } else {
    const axle = type === 'XL' ? [12, 37] : [11.5, 36.5];
    drawing = (
      <>
        <path d={CAB_BODY[type]} fill={body} stroke={INK} strokeWidth="1.2" strokeLinejoin="round" />
        {type === 'ECONOMY' && <path d="M19 5.8 Q21 5 30 5 L37 5.6 L37.5 6.8 H18.4Z" fill={roof} />}
        <path d={CAB_GLASS[type]} fill="#26313b" />
        {type === 'ECONOMY' && <rect x="24" y="1.6" width="6" height="3" rx=".8" fill="#ffe680" stroke={INK} strokeWidth=".8" />}
        {type === 'PREMIUM' && <path d="M4 15 H45" stroke="#c9ced6" strokeWidth="1" />}
        <Wheel x={axle[0]} r={4.4} />
        <Wheel x={axle[1]} r={4.4} />
      </>
    );
  }
  return (
    <svg viewBox="0 0 48 28" className={className} aria-hidden="true">
      {drawing}
    </svg>
  );
}
