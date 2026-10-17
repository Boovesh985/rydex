import type { RideType } from '../api/types';

/**
 * How each ride type looks (ADR 0018), shared by the 2D markers, the admin legend, the ride
 * pickers and the 3D models: `body` is the paint, `roof` the roof or the auto's canopy.
 */
export const VEHICLE_PAINT: Record<RideType, { body: string; roof: string }> = {
  // a red commuter motorbike
  BIKE: { body: '#c8102e', roof: '#17191d' },
  // a CNG auto rickshaw: green body, yellow canopy
  AUTO: { body: '#1f8a4c', roof: '#ffc21a' },
  // a Chennai call taxi: yellow with a black roof
  ECONOMY: { body: '#ffc21a', roof: '#17191d' },
  COMFORT: { body: '#f2f3ef', roof: '#f2f3ef' },
  XL: { body: '#2c313a', roof: '#2c313a' },
  // midnight blue
  PREMIUM: { body: '#1b2a4a', roof: '#1b2a4a' },
};

export type VehicleKind = 'bike' | 'auto' | 'car';

export const vehicleKind = (type: RideType | null | undefined): VehicleKind => (type === 'BIKE' ? 'bike' : type === 'AUTO' ? 'auto' : 'car');

/** A top-down vehicle for the 2D map (forward is up), drawn in a 40×40 box. */
export function topDownSvg(type: RideType | null | undefined, size: number): string {
  const { body, roof } = type ? VEHICLE_PAINT[type] : VEHICLE_PAINT.ECONOMY;
  const open = `<svg width="${size}" height="${size}" viewBox="0 0 40 40" aria-hidden="true">`;
  switch (vehicleKind(type)) {
    case 'bike':
      // wheels, the red tank and seat, the handlebar, the driver's yellow jacket and helmet
      return `${open}
    <ellipse cx="20" cy="21" rx="6" ry="16" fill="rgba(0,0,0,.22)"/>
    <rect x="18.3" y="2.5" width="3.4" height="9" rx="1.7" fill="#16181d"/>
    <rect x="18.3" y="28.5" width="3.4" height="9" rx="1.7" fill="#16181d"/>
    <rect x="15.5" y="8" width="9" height="23" rx="4.5" fill="${body}" stroke="#16181d" stroke-width="1.6"/>
    <path d="M11 11.5 H29" stroke="#16181d" stroke-width="2.2" stroke-linecap="round"/>
    <ellipse cx="20" cy="20.5" rx="7.2" ry="4.2" fill="#f2b705" stroke="#16181d" stroke-width="1.4"/>
    <circle cx="20" cy="18.6" r="3.7" fill="#1b1d22"/><circle cx="19" cy="17.6" r="1.1" fill="#8a9099"/>
  </svg>`;
    case 'auto':
      // the narrow cowl in front, the canopy over the cabin, the two rear wheels
      return `${open}
    <ellipse cx="20" cy="21" rx="12" ry="17" fill="rgba(0,0,0,.22)"/>
    <rect x="6.5" y="26" width="3" height="7" rx="1.2" fill="#16181d"/><rect x="30.5" y="26" width="3" height="7" rx="1.2" fill="#16181d"/>
    <path d="M15 4.5 Q20 2 25 4.5 L26 11 H14Z" fill="${body}" stroke="#16181d" stroke-width="1.6" stroke-linejoin="round"/>
    <rect x="8.5" y="9.5" width="23" height="28" rx="5.5" fill="${roof}" stroke="#16181d" stroke-width="2"/>
    <path d="M10.5 12 Q20 9.5 29.5 12 L29 14.5 Q20 12.5 11 14.5Z" fill="#16181d" opacity=".75"/>
    <path d="M12 21 H28 M12 27 H28" stroke="#16181d" stroke-width="1" opacity=".35"/>
    <circle cx="20" cy="4.6" r="1.5" fill="#fff6c8"/>
  </svg>`;
    default:
      return `${open}
    <ellipse cx="20" cy="21" rx="10" ry="17" fill="rgba(0,0,0,.22)"/>
    <rect x="11" y="3" width="18" height="34" rx="7" fill="${body}" stroke="#16181d" stroke-width="2"/>
    <path d="M13.5 12.5 Q20 9.5 26.5 12.5 L25.5 17 Q20 15.5 14.5 17Z" fill="#16181d"/>
    <path d="M14.5 27 Q20 28.5 25.5 27 L26 31 Q20 33 14 31Z" fill="#16181d" opacity=".8"/>
    <rect x="13" y="3.6" width="4" height="2" rx="1" fill="#fff6c8"/><rect x="23" y="3.6" width="4" height="2" rx="1" fill="#fff6c8"/>
  </svg>`;
  }
}
