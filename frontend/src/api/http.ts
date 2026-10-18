import { useAuth } from '../auth/store';
import { config } from '../config';
import type { AuthResponse } from './types';

/** An RFC 7807 Problem Details error from any Rydex service. */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly retryAfter: number | null;

  constructor(status: number, title: string, detail: string, retryAfter: number | null = null) {
    super(detail || title || `Request failed (${status})`);
    this.status = status;
    this.title = title;
    this.retryAfter = retryAfter;
  }
}

let refreshing: Promise<string | null> | null = null;

/** Runs `task` holding a lock shared by every tab of this site, so two tabs never refresh at once. */
function acrossTabs<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  return locks ? (locks.request('rydex.refresh', task) as Promise<T>) : task();
}

/**
 * Trades the refresh token for a new pair; one refresh at a time, shared by concurrent callers and
 * by every open tab. Refresh tokens rotate and a reused one revokes all of the user's sessions, so a
 * tab must never send a token another tab has already traded: after waiting for the lock it first
 * picks up whatever another tab stored meanwhile.
 */
export function refreshSession(): Promise<string | null> {
  const stale = useAuth.getState().session;
  if (!stale) return Promise.resolve(null);
  refreshing ??= acrossTabs(async () => {
    await useAuth.persist.rehydrate();
    const session = useAuth.getState().session;
    if (!session) return null;
    if (session.refreshToken !== stale.refreshToken) return session.accessToken; // another tab refreshed
    const res = await fetch(`${config.apiUrl}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    });
    if (!res.ok) {
      // 401: the refresh token was revoked or expired. Anything else: keep the session and retry later.
      if (res.status === 401 || res.status === 400) useAuth.getState().signOut();
      return null;
    }
    const body = (await res.json()) as AuthResponse;
    useAuth.getState().signIn(body);
    return body.accessToken;
  })
    .catch(() => null)
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

/** A valid access token, refreshed first if it expires within a minute. */
export async function freshToken(): Promise<string | null> {
  const session = useAuth.getState().session;
  if (!session) return null;
  if (session.expiresAt - Date.now() > 60_000) return session.accessToken;
  return (await refreshSession()) ?? useAuth.getState().session?.accessToken ?? null;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  /** Public endpoints (login, register) skip the token. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

async function toError(res: Response): Promise<ApiError> {
  let title = res.statusText;
  let detail = '';
  try {
    const body = await res.json();
    title = body.title ?? title;
    detail = body.detail ?? '';
  } catch {
    // not JSON
  }
  const retry = res.headers.get('Retry-After');
  return new ApiError(res.status, title, detail, retry ? Number(retry) : null);
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const send = async (token: string | null) => {
    const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    if (token) headers.Authorization = `Bearer ${token}`;
    return fetch(`${config.apiUrl}${path}`, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    });
  };

  let res = await send(opts.anonymous ? null : await freshToken());
  if (res.status === 401 && !opts.anonymous && useAuth.getState().session) {
    const token = await refreshSession();
    if (token) res = await send(token);
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204 || res.headers.get('Content-Length') === '0') return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** A readable message for any thrown value. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return `Too many requests. Try again in ${e.retryAfter ?? 60} s.`;
    // 502/504: a gateway (the dev proxy, Caddy) could not reach the service; its detail says which
    if (e.status === 502 || e.status === 504)
      return e.message.startsWith('Request failed') ? 'Rydex is not reachable right now. Try again in a moment.' : e.message;
    if (e.status >= 500 && e.status !== 503) return 'Something went wrong on our side. Try again.';
    return e.message;
  }
  if (e instanceof TypeError) return 'Cannot reach Rydex. Check your connection.';
  return e instanceof Error ? e.message : String(e);
}
