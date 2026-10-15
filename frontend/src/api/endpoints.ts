import { api } from './http';
import type {
  AuthResponse,
  DemandCell,
  DriverCard,
  DriverProfile,
  Estimate,
  GeoPoint,
  Grid,
  LiveDriver,
  LiveLocation,
  Offer,
  Page,
  PathPoint,
  Payment,
  Place,
  Ride,
  RideType,
  Role,
  Route,
  Stats,
} from './types';

const json = (body: unknown) => ({ method: 'POST', body });
const point = (p: GeoPoint) => ({ lat: p.lat, lng: p.lng, address: p.address ?? undefined });

export const authApi = {
  login: (email: string, password: string) =>
    api<AuthResponse>('/api/auth/login', { ...json({ email, password }), anonymous: true }),
  register: (req: { name: string; email: string; phone?: string; password: string; role: Role }) =>
    api<AuthResponse>('/api/auth/register', { ...json(req), anonymous: true }),
  logout: (refreshToken: string) =>
    api<void>('/api/auth/logout', { ...json({ refreshToken }), anonymous: true }),
};

export const placesApi = {
  search: (q: string, near?: GeoPoint, signal?: AbortSignal) => {
    const params = new URLSearchParams({ q });
    if (near) {
      params.set('lat', near.lat.toFixed(5));
      params.set('lng', near.lng.toFixed(5));
    }
    return api<Place[]>(`/api/places/search?${params}`, { signal });
  },
  reverse: (lat: number, lng: number) =>
    api<Place>(`/api/places/reverse?lat=${lat.toFixed(6)}&lng=${lng.toFixed(6)}`),
  route: (from: GeoPoint, to: GeoPoint) =>
    api<Route>(
      `/api/places/route?fromLat=${from.lat.toFixed(5)}&fromLng=${from.lng.toFixed(5)}` +
        `&toLat=${to.lat.toFixed(5)}&toLng=${to.lng.toFixed(5)}`,
    ),
};

export const ridesApi = {
  estimate: (pickup: GeoPoint, drop: GeoPoint) =>
    api<Estimate>('/api/rides/estimate', json({ pickup: point(pickup), drop: point(drop) })),
  book: (pickup: GeoPoint, drop: GeoPoint, rideType: RideType, idempotencyKey: string) =>
    api<Ride>('/api/rides', {
      ...json({ pickup: point(pickup), drop: point(drop), rideType }),
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  get: (id: string) => api<Ride>(`/api/rides/${id}`),
  list: (page = 0, size = 20, mine = true) => api<Page<Ride>>(`/api/rides?mine=${mine}&page=${page}&size=${size}`),
  /** Admins: every ride, or only the ones in flight. */
  all: (page = 0, size = 20, active = false) => api<Page<Ride>>(`/api/rides?mine=false&active=${active}&page=${page}&size=${size}`),
  cancel: (id: string, reason?: string) => api<Ride>(`/api/rides/${id}/cancel`, json({ reason })),
  arrived: (id: string) => api<Ride>(`/api/rides/${id}/arrived`, { method: 'POST' }),
  start: (id: string, otp: string) => api<Ride>(`/api/rides/${id}/start`, json({ otp })),
  complete: (id: string) => api<Ride>(`/api/rides/${id}/complete`, { method: 'POST' }),
};

export const driversApi = {
  me: () => api<DriverProfile>('/api/drivers/me'),
  saveProfile: (req: { vehicleType: RideType; vehicleNumber: string; vehicleModel: string }) =>
    api<DriverProfile>('/api/drivers/me', { method: 'PUT', body: req }),
  setStatus: (status: 'AVAILABLE' | 'OFFLINE', at?: GeoPoint) =>
    api<DriverProfile>('/api/drivers/me/status', {
      method: 'PATCH',
      body: { status, lat: at?.lat, lng: at?.lng },
    }),
  reportLocation: (loc: { lat: number; lng: number; heading?: number; speed?: number }) =>
    api<void>('/api/drivers/me/location', json({ lat: loc.lat, lng: loc.lng, heading: loc.heading, speed: loc.speed })),
  currentOffer: () => api<Offer | undefined>('/api/drivers/me/offer'),
  accept: (rideId: string) => api<unknown>(`/api/drivers/me/offers/${rideId}/accept`, { method: 'POST' }),
  reject: (rideId: string) => api<void>(`/api/drivers/me/offers/${rideId}/reject`, { method: 'POST' }),
  forRide: (rideId: string) => api<DriverCard>(`/api/drivers/for-ride/${rideId}`),
};

export const trackingApi = {
  location: (rideId: string) => api<LiveLocation>(`/api/tracking/rides/${rideId}/location`),
  /** The driver's recorded path during the ride, oldest first. */
  path: (rideId: string) => api<PathPoint[]>(`/api/tracking/rides/${rideId}/path`),
};

export const paymentsApi = {
  forRide: (rideId: string) => api<Payment>(`/api/payments/rides/${rideId}`),
  verify: (paymentId: string, r: { razorpayOrderId: string; razorpayPaymentId: string; razorpaySignature: string }) =>
    api<Payment>(`/api/payments/${paymentId}/razorpay/verify`, json(r)),
  retry: (paymentId: string) => api<Payment>(`/api/payments/${paymentId}/retry`, { method: 'POST' }),
};

export const adminApi = {
  stats: (hours = 24) => api<Stats>(`/api/admin/stats?hours=${hours}`),
  demand: (hours = 24) => api<DemandCell[]>(`/api/admin/demand?hours=${hours}`),
  grid: (lat: number, lng: number) => api<Grid>(`/api/admin/grid?lat=${lat.toFixed(6)}&lng=${lng.toFixed(6)}`),
  liveDrivers: () => api<LiveDriver[]>('/api/admin/drivers/live'),
  payments: (page = 0, size = 20) => api<Page<Payment>>(`/api/admin/payments?page=${page}&size=${size}`),
  refund: (paymentId: string, reason: string, amount?: number) =>
    api<Payment>(`/api/admin/payments/${paymentId}/refunds`, json({ reason, amount })),
};
