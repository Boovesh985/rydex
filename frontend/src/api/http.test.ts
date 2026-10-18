import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuth } from '../auth/store';
import { api, ApiError, errorMessage } from './http';

const user = { id: 'u1', name: 'Rider 1', email: 'rider1@rydex.dev', phone: null, role: 'RIDER' as const };
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

describe('api client', () => {
  beforeEach(() => {
    useAuth.getState().signIn({ accessToken: 'old', refreshToken: 'r1', tokenType: 'Bearer', expiresIn: 900, user });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    useAuth.getState().signOut();
  });

  it('sends the bearer token and parses JSON', async () => {
    const fetch = vi.fn().mockResolvedValue(json(200, { ok: true }));
    vi.stubGlobal('fetch', fetch);

    expect(await api('/api/me')).toEqual({ ok: true });
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer old');
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(401, { title: 'Unauthorized' }))
      .mockResolvedValueOnce(json(200, { accessToken: 'new', refreshToken: 'r2', tokenType: 'Bearer', expiresIn: 900, user }))
      .mockResolvedValueOnce(json(200, { id: 'ride-1' }));
    vi.stubGlobal('fetch', fetch);

    expect(await api('/api/rides/ride-1')).toEqual({ id: 'ride-1' });
    expect(fetch.mock.calls[1][0]).toBe('/api/auth/refresh');
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ refreshToken: 'r1' });
    expect(fetch.mock.calls[2][1].headers.Authorization).toBe('Bearer new');
    expect(useAuth.getState().session?.refreshToken).toBe('r2');
  });

  it('refreshes ahead of time when the access token is about to expire', async () => {
    useAuth.getState().signIn({ accessToken: 'stale', refreshToken: 'r1', tokenType: 'Bearer', expiresIn: 30, user });
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(200, { accessToken: 'new', refreshToken: 'r2', tokenType: 'Bearer', expiresIn: 900, user }))
      .mockResolvedValueOnce(json(200, {}));
    vi.stubGlobal('fetch', fetch);

    await api('/api/me');

    expect(fetch.mock.calls[0][0]).toBe('/api/auth/refresh');
    expect(fetch.mock.calls[1][1].headers.Authorization).toBe('Bearer new');
  });

  it('signs out when the refresh token is refused', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json(401, {}))
        .mockResolvedValueOnce(json(401, { title: 'Unauthorized' })),
    );

    await expect(api('/api/me')).rejects.toBeInstanceOf(ApiError);
    expect(useAuth.getState().session).toBeNull();
  });

  it('turns Problem Details into readable errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(429, { title: 'Too Many Requests', detail: 'slow down' }, { 'Retry-After': '42' })));

    const e = await api('/api/rides/estimate').catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).status).toBe(429);
    expect(errorMessage(e)).toBe('Too many requests. Try again in 42 s.');
    expect(errorMessage(new ApiError(409, 'Conflict', 'Ride already started'))).toBe('Ride already started');
    expect(errorMessage(new ApiError(500, 'Internal Server Error', 'NPE'))).toBe('Something went wrong on our side. Try again.');
    expect(errorMessage(new ApiError(502, 'Bad Gateway', 'ride-service is not running'))).toBe('ride-service is not running');
    expect(errorMessage(new ApiError(504, '', ''))).toBe('Rydex is not reachable right now. Try again in a moment.');
  });

  it('returns nothing for 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    expect(await api('/api/drivers/me/offer')).toBeUndefined();
  });
});

describe('refresh across tabs', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    useAuth.getState().signOut();
  });

  it('uses the tokens another tab already refreshed instead of replaying a used refresh token', async () => {
    useAuth.getState().signIn({ accessToken: 'old', refreshToken: 'r1', tokenType: 'Bearer', expiresIn: 30, user });
    // another tab traded r1 for r2 and stored the new pair
    const stored = JSON.parse(localStorage.getItem('rydex.session')!);
    stored.state.session = { ...stored.state.session, accessToken: 'from-other-tab', refreshToken: 'r2', expiresAt: Date.now() + 900_000 };
    localStorage.setItem('rydex.session', JSON.stringify(stored));
    const fetch = vi.fn().mockResolvedValue(json(200, { ok: true }));
    vi.stubGlobal('fetch', fetch);

    await api('/api/me');

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][0]).toBe('/api/me');
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer from-other-tab');
  });
});
