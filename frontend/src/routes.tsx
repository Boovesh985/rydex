import { lazy } from 'react';
import { Navigate, type RouteObject } from 'react-router';
import { RequireRole, RootRedirect } from './auth/RequireRole';
import { AppShell } from './components/AppShell';
import { RouteError } from './components/RouteError';
import { LoginPage, RegisterPage } from './pages/AuthPages';

// one chunk per screen: a rider never downloads the admin dashboard
const RiderHome = lazy(() => import('./pages/rider/RiderHome').then((m) => ({ default: m.RiderHome })));
const LiveRide = lazy(() => import('./pages/rider/LiveRide').then((m) => ({ default: m.LiveRide })));
const DriverDashboard = lazy(() => import('./pages/driver/DriverDashboard').then((m) => ({ default: m.DriverDashboard })));
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard').then((m) => ({ default: m.AdminDashboard })));
const HistoryPage = lazy(() => import('./pages/HistoryPage').then((m) => ({ default: m.HistoryPage })));
const TripPage = lazy(() => import('./pages/TripPage').then((m) => ({ default: m.TripPage })));

export const routes: RouteObject[] = [
  {
    // a screen that fails to draw shows our own page (not React Router's developer page)
    errorElement: <RouteError />,
    children: [
      { path: '/', element: <RootRedirect /> },
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
      {
        element: <AppShell />,
        children: [
          { path: '/ride', element: <RequireRole role="RIDER"><RiderHome /></RequireRole> },
          { path: '/ride/:rideId', element: <RequireRole role="RIDER"><LiveRide /></RequireRole> },
          { path: '/drive', element: <RequireRole role="DRIVER"><DriverDashboard /></RequireRole> },
          { path: '/admin', element: <RequireRole role="ADMIN"><AdminDashboard /></RequireRole> },
          { path: '/history', element: <RequireRole role={['RIDER', 'DRIVER']}><HistoryPage /></RequireRole> },
          // a driver's past trip, or any ride for an admin (riders use /ride/:rideId)
          { path: '/trips/:rideId', element: <RequireRole role={['DRIVER', 'ADMIN']}><TripPage /></RequireRole> },
        ],
      },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
];
