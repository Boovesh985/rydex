import type { CheckoutOptions } from '../api/types';

/** What Razorpay Checkout hands its success handler. */
export interface RazorpaySuccess {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', cb: (res: { error?: { description?: string } }) => void) => void;
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance;
  }
}

const SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';
let loading: Promise<void> | null = null;

function loadScript(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  loading ??= new Promise<void>((resolve, reject) => {
    const s = document.createElement('script');
    s.src = SCRIPT;
    s.async = true;
    s.onload = () => resolve();
    s.onerror = () => {
      loading = null;
      reject(new Error('Could not load Razorpay Checkout. Check your connection and try again.'));
    };
    document.head.appendChild(s);
  });
  return loading;
}

/**
 * Opens Razorpay Checkout (test mode) for a Rydex payment. Resolves with the signed response
 * to forward to payment-service, or null if the rider closed the window.
 */
export async function openCheckout(
  options: CheckoutOptions,
  prefill: { name?: string; email?: string; contact?: string | null },
  onFailed: (reason: string) => void,
): Promise<RazorpaySuccess | null> {
  await loadScript();
  return new Promise((resolve) => {
    const rzp = new window.Razorpay!({
      key: options.keyId,
      order_id: options.orderId,
      amount: options.amount,
      currency: options.currency,
      name: options.name,
      description: options.description,
      prefill: { name: prefill.name, email: prefill.email, contact: prefill.contact ?? undefined },
      theme: { color: '#16181D' },
      handler: (res: RazorpaySuccess) => resolve(res),
      modal: { ondismiss: () => resolve(null) },
    });
    rzp.on('payment.failed', (res) => onFailed(res.error?.description ?? 'The payment did not go through.'));
    rzp.open();
  });
}
