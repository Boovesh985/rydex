import { useEffect, useState } from 'react';
import type { Offer, RideType } from '../../api/types';
import { Button, ErrorNote } from '../../components/ui';
import { distance, inr, minutes, rideTypeLabel, shortAddress } from '../../lib/format';

interface Props {
  offer: Offer;
  /** the driver's own vehicle class: a different one means they are the free upgrade (ADR 0021) */
  vehicle?: RideType;
  busy: 'accept' | 'reject' | null;
  error: string;
  onAccept: () => void;
  onReject: () => void;
}

/** Seconds left until `expiresAt`, ticking 4 times a second. */
function useSecondsLeft(expiresAt: string) {
  const [left, setLeft] = useState(() => Math.max(0, (Date.parse(expiresAt) - Date.now()) / 1000));
  useEffect(() => {
    const timer = setInterval(() => setLeft(Math.max(0, (Date.parse(expiresAt) - Date.now()) / 1000)), 250);
    return () => clearInterval(timer);
  }, [expiresAt]);
  return left;
}

/** An incoming ride: 15 seconds to take it. The ring drains as the time runs out. */
export function OfferModal({ offer, vehicle, busy, error, onAccept, onReject }: Props) {
  const left = useSecondsLeft(offer.expiresAt);
  const total = Math.max(1, (Date.parse(offer.expiresAt) - Date.parse(offer.offeredAt)) / 1000);
  const r = 26;
  const circumference = 2 * Math.PI * r;
  const urgent = left <= 5;

  return (
    <div
      className="fixed inset-0 z-[1800] flex items-end justify-center bg-ink/40 p-3 backdrop-blur-[2px] sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="offer-title"
    >
      <div className="w-full max-w-md overflow-hidden rounded-[var(--radius-sheet)] bg-paper shadow-[var(--shadow-float)]">
        <div className="flex items-center gap-4 bg-ink p-4 text-chalk">
          <div className="relative grid size-16 shrink-0 place-items-center">
            <svg viewBox="0 0 64 64" className="absolute inset-0 -rotate-90" aria-hidden>
              <circle cx="32" cy="32" r={r} fill="none" stroke="rgb(255 255 255 / 0.12)" strokeWidth="6" />
              <circle
                cx="32"
                cy="32"
                r={r}
                fill="none"
                stroke={urgent ? '#d7362b' : '#ffc21a'}
                strokeWidth="6"
                strokeLinecap="round"
                strokeDasharray={circumference}
                strokeDashoffset={circumference * (1 - left / total)}
                style={{ transition: 'stroke-dashoffset 250ms linear' }}
              />
            </svg>
            <span className="font-mono text-xl font-bold tabular" aria-label={`${Math.ceil(left)} seconds left`}>
              {Math.ceil(left)}
            </span>
          </div>
          <div className="min-w-0">
            <p id="offer-title" className="font-display-wide text-xl font-black">
              New {rideTypeLabel[offer.rideType]} ride
            </p>
            <p className="text-sm text-chalk/75">
              Pickup {distance(offer.distanceToPickupM)} away · {minutes(offer.pickupEtaS)}
            </p>
            {vehicle && vehicle !== offer.rideType && (
              <p className="text-sm font-bold text-auto">No {rideTypeLabel[offer.rideType]} nearby: you are the upgrade, at its fare</p>
            )}
          </div>
          <span className="ml-auto font-mono text-2xl font-bold text-auto tabular">{inr(offer.estFare)}</span>
        </div>
        <div className="space-y-3 p-4">
          <div className="space-y-2 text-sm">
            <p className="flex items-start gap-3">
              <span className="mt-1 block size-3 shrink-0 rounded-full border-[3px] border-ink" aria-hidden />
              <span>
                <span className="font-bold">{shortAddress(offer.pickup.address, 'Pickup pin')}</span>
                {offer.pickup.address && offer.pickup.address !== shortAddress(offer.pickup.address) && (
                  <span className="block text-slate">{offer.pickup.address}</span>
                )}
              </span>
            </p>
            <p className="flex items-start gap-3">
              <span className="mt-1 block size-3 shrink-0 rounded-[2px] bg-ink" aria-hidden />
              <span>
                <span className="font-bold">{shortAddress(offer.drop.address, 'Drop pin')}</span>
                <span className="block text-slate">
                  {distance(offer.estDistanceM)} trip · about {minutes(offer.estDurationS)}
                </span>
              </span>
            </p>
          </div>
          <ErrorNote>{error}</ErrorNote>
          <div className="grid grid-cols-[1fr_2fr] gap-2">
            <Button variant="quiet" busy={busy === 'reject'} disabled={!!busy} onClick={onReject}>
              Decline
            </Button>
            <Button busy={busy === 'accept'} disabled={!!busy || left <= 0} onClick={onAccept}>
              Accept ride
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
