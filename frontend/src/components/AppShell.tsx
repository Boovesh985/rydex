import { useQueryClient } from '@tanstack/react-query';
import { History, LogOut } from 'lucide-react';
import { Suspense, useEffect, useRef } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { authApi } from '../api/endpoints';
import type { Notification } from '../api/types';
import { homeFor, useAuth } from '../auth/store';
import { useConnected, useSubscription } from '../realtime/hooks';
import { realtime } from '../realtime/stomp';
import { toast } from './toastStore';

const GOOD = new Set(['DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'PAYMENT_SUCCEEDED', 'PAYMENT_REFUNDED']);
const BAD = new Set(['NO_DRIVER_FOUND', 'RIDE_CANCELLED', 'PAYMENT_FAILED']);

export function Wordmark({ className = '' }: { className?: string }) {
  return (
    <span className={`font-display-wide text-xl font-black tracking-tight text-ink ${className}`}>
      Ryde<span className="rounded-[4px] bg-auto px-0.5">x</span>
    </span>
  );
}

/**
 * Signed-in frame: the live connection, ride/payment notifications as toasts (and fresh data
 * for whichever screen shows that ride), and the floating top bar.
 */
export function AppShell() {
  const session = useAuth((s) => s.session);
  const signOut = useAuth((s) => s.signOut);
  const connected = useConnected();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const seen = useRef(new Set<string>());

  // tabs share one session: when another tab signs in as someone else, this tab follows, and must not
  // keep showing (or acting on) the previous account's rides
  const userId = session?.user.id;
  const shownUser = useRef(userId);
  useEffect(() => {
    const before = shownUser.current;
    shownUser.current = userId;
    if (!before || !userId || before === userId) return;
    const user = useAuth.getState().session?.user;
    queryClient.clear();
    seen.current.clear();
    if (user) {
      toast(`Signed in as ${user.name}`, 'Another tab signed in to a different account, so this one switched too. Use a private window to be two people at once.', 'info');
      navigate(homeFor(user.role), { replace: true });
    }
  }, [userId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!session) return;
    realtime.start();
    return () => {
      void realtime.stop();
    };
  }, [session?.user.id]); // eslint-disable-line react-hooks/exhaustive-deps

  useSubscription<Notification>(session ? '/user/queue/notifications' : null, (n) => {
    const key = `${n.eventId}:${n.type}`;
    if (seen.current.has(key)) return; // redelivered after a reconnect
    seen.current.add(key);
    toast(n.title, n.message, GOOD.has(n.type) ? 'good' : BAD.has(n.type) ? 'bad' : 'info');
    void queryClient.invalidateQueries({ queryKey: ['ride', n.rideId] });
    void queryClient.invalidateQueries({ queryKey: ['payment', n.rideId] });
    void queryClient.invalidateQueries({ queryKey: ['rides'] });
    void queryClient.invalidateQueries({ queryKey: ['driver', 'me'] });
  });

  if (!session) return null;
  const user = session.user;

  const logout = async () => {
    const refresh = session.refreshToken;
    signOut();
    queryClient.clear();
    navigate('/login');
    await authApi.logout(refresh).catch(() => undefined);
  };

  return (
    <div className="relative h-dvh overflow-hidden">
      <header className="pointer-events-none absolute inset-x-0 top-0 z-[1500] flex items-center justify-between gap-2 p-3 md:p-4">
        <Link to={homeFor(user.role)} className="pointer-events-auto rounded-xl bg-paper px-3 py-2 shadow-[var(--shadow-float)]">
          <Wordmark />
        </Link>
        <nav className="pointer-events-auto flex items-center gap-1 rounded-xl bg-paper p-1 shadow-[var(--shadow-float)]">
          <span
            className={`mx-2 size-2.5 rounded-full ${connected ? 'bg-go' : 'bg-signal'}`}
            title={connected ? 'Live updates on' : 'Reconnecting…'}
            role="img"
            aria-label={connected ? 'Live updates on' : 'Reconnecting'}
          />
          <span className="hidden px-2 text-sm font-bold sm:inline">{user.name}</span>
          {user.role !== 'ADMIN' && (
            <NavLink to="/history" className="grid size-10 place-items-center rounded-lg hover:bg-chalk" aria-label="Your rides">
              <History className="size-5" />
            </NavLink>
          )}
          <button onClick={logout} className="grid size-10 place-items-center rounded-lg hover:bg-chalk" aria-label="Sign out">
            <LogOut className="size-5" />
          </button>
        </nav>
      </header>
      <Suspense fallback={<div className="grid h-full place-items-center text-slate">Loading…</div>}>
        <Outlet />
      </Suspense>
    </div>
  );
}
