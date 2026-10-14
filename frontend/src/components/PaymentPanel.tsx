import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CircleCheck, CircleX, Undo2 } from 'lucide-react';
import { paymentsApi } from '../api/endpoints';
import { ApiError, errorMessage } from '../api/http';
import type { Payment } from '../api/types';
import { useAuth } from '../auth/store';
import { inr } from '../lib/format';
import { openCheckout } from '../lib/razorpay';
import { toast } from './toastStore';
import { Button, ErrorNote, Spinner } from './ui';

/**
 * The payment for a finished ride (fare, or a late-cancellation fee). It appears a moment after
 * the ride ends; Razorpay payments wait for the rider to pay in Checkout.
 */
export function PaymentPanel({ rideId }: { rideId: string }) {
  const queryClient = useQueryClient();
  const user = useAuth((s) => s.session?.user);
  const payment = useQuery({
    queryKey: ['payment', rideId],
    queryFn: () => paymentsApi.forRide(rideId),
    // 404 until payment-service has handled the ride's last event; keep looking until it settles
    retry: (count, e) => e instanceof ApiError && e.status === 404 && count < 20,
    retryDelay: 1500,
    refetchInterval: (q) => (q.state.data && q.state.data.status === 'PENDING' ? 4000 : false),
  });

  const refresh = (p: Payment) => queryClient.setQueryData(['payment', rideId], p);

  const pay = useMutation({
    mutationFn: async (p: Payment) => {
      if (p.provider === 'MOCK' || !p.checkout) return paymentsApi.retry(p.id);
      const res = await openCheckout(p.checkout, { name: user?.name, email: user?.email, contact: user?.phone }, (reason) =>
        toast('Payment failed', reason, 'bad'),
      );
      if (!res) return null;
      return paymentsApi.verify(p.id, {
        razorpayOrderId: res.razorpay_order_id,
        razorpayPaymentId: res.razorpay_payment_id,
        razorpaySignature: res.razorpay_signature,
      });
    },
    onSuccess: (p) => {
      if (p) refresh(p);
      void queryClient.invalidateQueries({ queryKey: ['ride', rideId] });
    },
  });

  if (payment.isPending) return <Spinner label="Preparing your bill…" />;
  if (payment.error) return <ErrorNote>{errorMessage(payment.error)}</ErrorNote>;
  const p = payment.data;
  const what = p.kind === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Trip fare';
  const needsRider = p.status === 'FAILED' || (p.status === 'PENDING' && !!p.checkout);

  return (
    <div className="space-y-3 rounded-xl bg-chalk p-3">
      <div className="flex items-center justify-between gap-2">
        <div>
          <p className="text-sm text-slate">
            {what} · {p.provider === 'RAZORPAY' ? 'Razorpay (test mode)' : 'Demo wallet'}
          </p>
          <p className="font-mono text-xl font-bold tabular">{inr(p.amount)}</p>
        </div>
        <PaymentBadge status={p.status} />
      </div>
      {p.refundedAmount > 0 && (
        <p className="flex items-center gap-1.5 text-sm text-sea">
          <Undo2 className="size-4" aria-hidden /> {inr(p.refundedAmount)} refunded
        </p>
      )}
      {p.status === 'FAILED' && p.failureReason && <p className="text-sm text-signal">{p.failureReason}</p>}
      {needsRider && (
        <Button block busy={pay.isPending} onClick={() => pay.mutate(p)}>
          {p.status === 'FAILED' ? `Try again · ${inr(p.amount)}` : `Pay ${inr(p.amount)}`}
        </Button>
      )}
      {p.provider === 'RAZORPAY' && needsRider && (
        <p className="text-xs text-slate">Test mode: choose Netbanking, any bank, then Success. No real money moves.</p>
      )}
      <ErrorNote>{pay.error && errorMessage(pay.error)}</ErrorNote>
    </div>
  );
}

export function PaymentBadge({ status }: { status: Payment['status'] | null }) {
  if (!status) return null;
  const look = {
    SUCCEEDED: ['bg-go-soft text-go', 'Paid', CircleCheck],
    REFUNDED: ['bg-sea-soft text-sea', 'Refunded', Undo2],
    FAILED: ['bg-signal-soft text-signal', 'Failed', CircleX],
    PENDING: ['bg-auto/30 text-ink', 'Due', null],
  } as const;
  const [cls, label, Icon] = look[status];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-bold ${cls}`}>
      {Icon && <Icon className="size-3.5" aria-hidden />}
      {label}
    </span>
  );
}
