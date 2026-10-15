/** Types of the Rydex REST and WebSocket APIs (see /contracts and each service's OpenAPI). */

export type Role = 'RIDER' | 'DRIVER' | 'ADMIN';
export type RideType = 'ECONOMY' | 'COMFORT' | 'XL';
export const RIDE_TYPES: RideType[] = ['ECONOMY', 'COMFORT', 'XL'];

export type RideStatus =
  | 'REQUESTED'
  | 'DRIVER_ASSIGNED'
  | 'DRIVER_ARRIVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_DRIVER_FOUND';

export const ACTIVE_STATUSES: RideStatus[] = ['REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS'];

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'REFUNDED';

export interface User {
  id: string;
  name: string;
  email: string;
  phone: string | null;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface GeoPoint {
  lat: number;
  lng: number;
  address?: string | null;
}

export interface Place {
  name: string;
  address: string;
  lat: number;
  lng: number;
}

export interface RideOption {
  rideType: RideType;
  fare: number;
  etaMinutes: number | null;
  driversNearby: number;
}

export interface Estimate {
  pickup: GeoPoint;
  drop: GeoPoint;
  distanceM: number;
  durationS: number;
  surgeMultiplier: number;
  route: { polyline: string | null; source: string };
  options: RideOption[];
}

export interface Route {
  distanceM: number;
  durationS: number;
  polyline: string | null;
  source: 'OSRM' | 'ESTIMATED';
}

export interface StatusChange {
  from: RideStatus | null;
  to: RideStatus;
  at: string;
}

export interface Ride {
  id: string;
  status: RideStatus;
  rideType: RideType;
  riderId: string;
  driverId: string | null;
  pickup: GeoPoint;
  drop: GeoPoint;
  estFare: number;
  finalFare: number | null;
  estDistanceM: number;
  estDurationS: number;
  surgeMultiplier: number;
  otp: string | null;
  requestedAt: string;
  assignedAt: string | null;
  arrivedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  cancelledBy: string | null;
  cancellationFee: number | null;
  paymentStatus: PaymentStatus | null;
  refundedAmount: number | null;
  timeline: StatusChange[] | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface DriverCard {
  id: string;
  name: string;
  vehicleType: RideType;
  vehicleNumber: string;
  vehicleModel: string;
  rating: number;
}

export type DriverStatus = 'OFFLINE' | 'AVAILABLE' | 'ON_TRIP';

export interface DriverProfile extends DriverCard {
  status: DriverStatus;
  currentRideId: string | null;
  searchable: boolean;
  lastLocation: { lat: number; lng: number; at: string } | null;
  updatedAt: string;
}

export interface Offer {
  rideId: string;
  rideType: RideType;
  pickup: GeoPoint;
  drop: GeoPoint;
  estFare: number;
  estDistanceM: number;
  estDurationS: number;
  pickupEtaS: number;
  distanceToPickupM: number;
  attempt: number;
  offeredAt: string;
  expiresAt: string;
  secondsLeft: number;
}

export type Phase = 'TO_PICKUP' | 'AT_PICKUP' | 'TO_DROP';

export interface LiveLocation {
  rideId: string;
  driverId: string;
  lat: number;
  lng: number;
  heading: number | null;
  speed: number | null;
  phase: Phase;
  etaS: number;
  distanceM: number;
  etaEstimated: boolean;
  at: string;
}

export interface CheckoutOptions {
  keyId: string;
  orderId: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
}

export interface Refund {
  id: string;
  amount: number;
  status: 'PENDING' | 'PROCESSED' | 'FAILED';
  reason: string;
  failureReason: string | null;
  createdAt: string;
  processedAt: string | null;
}

export interface Payment {
  id: string;
  rideId: string;
  riderId: string;
  driverId: string | null;
  kind: 'RIDE_FARE' | 'CANCELLATION_FEE';
  status: PaymentStatus;
  amount: number;
  refundedAmount: number;
  refundingAmount: number;
  refundable: number;
  currency: string;
  provider: 'MOCK' | 'RAZORPAY';
  providerRef: string | null;
  failureReason: string | null;
  createdAt: string;
  paidAt: string | null;
  refunds: Refund[];
  checkout: CheckoutOptions | null;
}

export interface GridCell {
  geohash: string;
  position: string;
  minLat: number;
  minLng: number;
  maxLat: number;
  maxLng: number;
  availableDrivers: number;
  recentRequests: number;
}

export interface Grid {
  center: string;
  precision: number;
  availableDrivers: number;
  recentRequests: number;
  cells: GridCell[];
}

export interface PathPoint {
  lat: number;
  lng: number;
  speed: number | null;
  heading: number | null;
  at: string;
}

export interface LiveDriver {
  driverId: string;
  lat: number;
  lng: number;
  heading: number | null;
  speed: number | null;
  geohash: string;
  status: DriverStatus | null;
  rideType: RideType | null;
  at: string;
}

export interface Stats {
  hours: number;
  since: string;
  inFlight: Record<string, number>;
  requested: number;
  byStatus: Record<string, number>;
  completed: number;
  cancelled: number;
  noDriverFound: number;
  matchRate: number | null;
  avgAssignS: number | null;
  avgPickupS: number | null;
  revenue: number;
  avgFare: number | null;
  riders: number;
  drivers: number;
}

export interface DemandCell {
  lat: number;
  lng: number;
  requests: number;
}

/** `/user/queue/notifications`: ride steps and payment steps share the queue; tell them apart by `type`. */
export interface Notification {
  eventId: string;
  type: string;
  rideId: string;
  status?: string;
  title: string;
  message: string;
  driverId?: string | null;
  fare?: number | null;
  paymentId?: string;
  kind?: string;
  amount?: number;
  refundAmount?: number | null;
  at: string;
}

/** `/user/queue/offers` */
export interface OfferMessage {
  type: 'OFFER' | 'OFFER_CLOSED' | 'OFFER_RESPONSE';
  rideId: string;
  reason?: string | null;
  offer?: Offer | null;
  ok?: boolean;
  status?: number;
  detail?: string | null;
}
