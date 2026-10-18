import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { authApi } from '../api/endpoints';
import { errorMessage } from '../api/http';
import type { Role } from '../api/types';
import { homeFor, useAuth } from '../auth/store';
import { Wordmark } from '../components/AppShell';
import { prefetchMap } from '../map/prefetch';
import { Check, Circle } from 'lucide-react';
import { Button, ErrorNote, Field, PasswordField } from '../components/ui';

/** Seeded demo accounts (README); the password is the same for all. */
const DEMO = [
  { label: 'Rider', email: 'rider1@rydex.dev' },
  { label: 'Driver', email: 'driver01@rydex.dev' },
  { label: 'Admin', email: 'admin@rydex.dev' },
];
const DEMO_PASSWORD = 'Rydex@123';

function AuthFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="grid min-h-dvh md:grid-cols-[1.1fr_1fr]">
      <aside className="relative hidden overflow-hidden bg-ink p-10 text-chalk md:flex md:flex-col md:justify-between">
        <span className="font-display-wide text-2xl font-black">
          Ryde<span className="rounded-[4px] bg-auto px-0.5 text-ink">x</span>
        </span>
        <div>
          <p className="max-w-md font-display-wide text-5xl leading-[1.02] font-black">
            Central to Marina,
            <br />
            <span className="text-auto">metered live.</span>
          </p>
          <p className="mt-5 max-w-sm text-chalk/70">Book a car, see the fare before you go, and watch your driver come to you on the map.</p>
        </div>
        <p className="font-mono text-xs text-chalk/50">13.0827° N · 80.2707° E</p>
        {/* the meter's tick marks, as a quiet texture */}
        <div className="pointer-events-none absolute -right-24 -bottom-24 size-96 rounded-full border-[22px] border-dashed border-auto/15" aria-hidden />
      </aside>
      <section className="flex flex-col justify-center px-5 py-10 sm:px-12">
        <div className="mx-auto w-full max-w-sm">
          <Wordmark className="md:hidden" />
          <h1 className="mt-6 font-display-wide text-3xl font-black md:mt-0">{title}</h1>
          <div className="mt-6">{children}</div>
        </div>
      </section>
    </main>
  );
}

function useSignedInRedirect() {
  const user = useAuth((s) => s.session?.user);
  return user ? <Navigate to={homeFor(user.role)} replace /> : null;
}

/**
 * While the user types, fetch the map's code (MapLibre, ~300 KB compressed, and its worker): every
 * screen after sign-in opens on a map, which then draws without waiting for the download.
 */
function usePrefetchMap() {
  useEffect(() => {
    if (import.meta.env.MODE === 'test') return;
    const timer = setTimeout(prefetchMap, 600);
    return () => clearTimeout(timer);
  }, []);
}

export function LoginPage() {
  usePrefetchMap();
  const signIn = useAuth((s) => s.signIn);
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const redirect = useSignedInRedirect();
  if (redirect) return redirect;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const res = await authApi.login(email.trim(), password);
      signIn(res);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : homeFor(res.user.role), { replace: true });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="Sign in">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <PasswordField
          label="Password"
          autoComplete="current-password"
          required
          maxLength={128}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <ErrorNote>{error}</ErrorNote>
        <Button type="submit" block busy={busy}>
          Sign in
        </Button>
      </form>
      <div className="mt-6 rounded-xl border border-dashed border-mist p-3">
        <p className="text-sm text-slate">Try a demo account (password {DEMO_PASSWORD}):</p>
        <div className="mt-2 flex gap-2">
          {DEMO.map((d) => (
            <button
              key={d.email}
              type="button"
              className="rounded-lg bg-chalk px-3 py-1.5 text-sm font-bold hover:bg-mist"
              onClick={() => {
                setEmail(d.email);
                setPassword(DEMO_PASSWORD);
              }}
            >
              {d.label}
            </button>
          ))}
        </div>
      </div>
      <p className="mt-6 text-sm text-slate">
        New to Rydex?{' '}
        <Link to="/register" className="font-bold text-ink underline underline-offset-4">
          Create an account
        </Link>
      </p>
    </AuthFrame>
  );
}

export function RegisterPage() {
  const signIn = useAuth((s) => s.signIn);
  const navigate = useNavigate();
  const [role, setRole] = useState<Role>('RIDER');
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const redirect = useSignedInRedirect();
  if (redirect) return redirect;

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const phone = form.phone.replace(/[\s-]/g, '');
      const res = await authApi.register({ ...form, email: form.email.trim(), phone: phone || undefined, role });
      signIn(res);
      navigate(homeFor(res.user.role), { replace: true });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="Create your account">
      <form onSubmit={submit} className="space-y-4">
        <fieldset>
          <legend className="mb-1 text-sm font-bold">I want to</legend>
          <div className="grid grid-cols-2 gap-1 rounded-xl bg-chalk p-1">
            {(
              [
                ['RIDER', 'Ride'],
                ['DRIVER', 'Drive'],
              ] as const
            ).map(([value, label]) => (
              <label
                key={value}
                className={`cursor-pointer rounded-lg py-2.5 text-center font-display-wide font-bold transition-colors has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-sea ${role === value ? 'bg-ink text-chalk' : 'text-slate hover:text-ink'}`}
              >
                <input type="radio" name="role" value={value} checked={role === value} onChange={() => setRole(value)} className="sr-only" />
                {label}
              </label>
            ))}
          </div>
        </fieldset>
        <Field label="Full name" autoComplete="name" required maxLength={100} value={form.name} onChange={set('name')} />
        <Field label="Email" type="email" autoComplete="email" required value={form.email} onChange={set('email')} />
        <Field
          label="Phone (optional)"
          type="tel"
          autoComplete="tel"
          placeholder="+91 98765 43210"
          value={form.phone}
          onChange={set('phone')}
          pattern="\+?[0-9 \-]{10,20}"
        />
        <PasswordField label="Password" autoComplete="new-password" required minLength={8} maxLength={128} value={form.password} onChange={set('password')}>
          <PasswordRules password={form.password} email={form.email} />
        </PasswordField>
        <ErrorNote>{error}</ErrorNote>
        <Button type="submit" block busy={busy}>
          {role === 'DRIVER' ? 'Create account and add your car' : 'Create account'}
        </Button>
      </form>
      <p className="mt-6 text-sm text-slate">
        Already have an account?{' '}
        <Link to="/login" className="font-bold text-ink underline underline-offset-4">
          Sign in
        </Link>
      </p>
    </AuthFrame>
  );
}

/** The server's rules, ticked off as they are met. */
function PasswordRules({ password, email }: { password: string; email: string }) {
  const local = email.trim().toLowerCase().split('@')[0] ?? '';
  const rules = [
    ['At least 8 characters', password.length >= 8],
    ['A letter and a digit', /\p{L}/u.test(password) && /\d/.test(password)],
    ['Not your email address', !(local.length >= 4 && password.toLowerCase().includes(local))],
  ] as const;
  return (
    <ul className="mt-1.5 grid gap-0.5 text-xs" aria-label="Password rules">
      {rules.map(([text, ok]) => (
        <li key={text} className={`flex items-center gap-1.5 ${ok && password ? 'text-go' : 'text-slate'}`}>
          {ok && password ? <Check className="size-3.5" aria-hidden /> : <Circle className="size-3" aria-hidden />}
          {text}
          <span className="sr-only">{ok && password ? ' (done)' : ''}</span>
        </li>
      ))}
    </ul>
  );
}
