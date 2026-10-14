import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '../api/types';
import { homeFor, useAuth } from './store';

/** Signed-out users go to the login page; signed-in users of another role go to their own home. */
export function RequireRole({ role, children }: { role: Role | Role[]; children: ReactNode }) {
  const user = useAuth((s) => s.session?.user);
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  const allowed = Array.isArray(role) ? role : [role];
  if (!allowed.includes(user.role)) return <Navigate to={homeFor(user.role)} replace />;
  return <>{children}</>;
}

export function RootRedirect() {
  const user = useAuth((s) => s.session?.user);
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />;
}
