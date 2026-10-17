const inrFormat = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

/** ₹339 (whole rupees; fares are quoted in whole rupees). */
export const inr = (amount: number | null | undefined) => (amount == null ? '—' : inrFormat.format(amount));

/** "2 min", "1 h 05 min"; under a minute rounds up to "1 min". */
export function minutes(seconds: number): string {
  const m = Math.max(1, Math.round(seconds / 60));
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`;
}

/** "850 m", "12.4 km". */
export function distance(meters: number): string {
  if (meters < 1000) return `${Math.round(meters / 10) * 10} m`;
  return `${(meters / 1000).toFixed(meters < 10_000 ? 1 : 0)} km`;
}

const timeFormat = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit' });
const dateFormat = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });

export const clock = (iso: string | null | undefined) => (iso ? timeFormat.format(new Date(iso)) : '');
export const when = (iso: string | null | undefined) => (iso ? dateFormat.format(new Date(iso)) : '');

/** First line of an address: "Chennai Central, Park Town, ..." -> "Chennai Central". */
export const shortAddress = (address: string | null | undefined, fallback = 'Pinned location') =>
  address?.split(',')[0]?.trim() || fallback;

export const rideTypeLabel = { BIKE: 'Bike', AUTO: 'Auto', ECONOMY: 'Economy', COMFORT: 'Comfort', XL: 'XL', PREMIUM: 'Premium' } as const;
export const rideTypeBlurb = {
  BIKE: 'Bike taxi · helmet provided, beats the traffic',
  AUTO: 'Auto rickshaw · 3 seats',
  ECONOMY: 'Hatchbacks · 4 seats',
  COMFORT: 'Sedans · 4 seats, more legroom',
  XL: 'MUVs · 6 seats',
  PREMIUM: 'Premium sedans · 4 seats, top-rated drivers',
} as const;
export const rideTypeSeats = { BIKE: 1, AUTO: 3, ECONOMY: 4, COMFORT: 4, XL: 6, PREMIUM: 4 } as const;
/** What the vehicle is called in a sentence: "your bike", "your auto", "your car". */
export const vehicleNoun = (type: keyof typeof rideTypeLabel | null | undefined) => (type === 'BIKE' ? 'bike' : type === 'AUTO' ? 'auto' : 'car');
/** A make and model to suggest when registering a vehicle of this type. */
export const vehicleModelHint = {
  BIKE: 'Hero Splendor Plus',
  AUTO: 'Bajaj RE',
  ECONOMY: 'Maruti Suzuki Swift',
  COMFORT: 'Honda City',
  XL: 'Toyota Innova Crysta',
  PREMIUM: 'Toyota Camry',
} as const;
