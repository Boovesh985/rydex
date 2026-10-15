import type { RideStatus } from '../api/types';

const look: Record<RideStatus, [string, string]> = {
  REQUESTED: ['Finding driver', 'bg-auto/30'],
  DRIVER_ASSIGNED: ['Driver coming', 'bg-auto/30'],
  DRIVER_ARRIVED: ['Driver arrived', 'bg-auto/30'],
  IN_PROGRESS: ['On trip', 'bg-sea-soft text-sea'],
  COMPLETED: ['Completed', 'bg-go-soft text-go'],
  CANCELLED: ['Cancelled', 'bg-signal-soft text-signal'],
  NO_DRIVER_FOUND: ['No driver', 'bg-chalk text-slate'],
};

export function RideStatusChip({ status }: { status: RideStatus }) {
  const [label, cls] = look[status];
  return <span className={`rounded-full px-2 py-0.5 text-xs font-bold whitespace-nowrap ${cls}`}>{label}</span>;
}
