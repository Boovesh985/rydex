import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { AuthResponse, User } from '../api/types';

interface Session {
  accessToken: string;
  refreshToken: string;
  /** epoch ms */
  expiresAt: number;
  user: User;
}

interface AuthState {
  session: Session | null;
  signIn: (res: AuthResponse) => void;
  signOut: () => void;
}

/** The signed-in session, kept in localStorage so a reload stays signed in. */
export const useAuth = create<AuthState>()(
  persist(
    (set) => ({
      session: null,
      signIn: (res) =>
        set({
          session: {
            accessToken: res.accessToken,
            refreshToken: res.refreshToken,
            expiresAt: Date.now() + res.expiresIn * 1000,
            user: res.user,
          },
        }),
      signOut: () => set({ session: null }),
    }),
    { name: 'rydex.session', storage: createJSONStorage(() => localStorage) },
  ),
);

// Tabs share one session: a sign-in, sign-out or token refresh in one tab reaches the others.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === 'rydex.session' || e.key === null) void useAuth.persist.rehydrate();
  });
}

export const homeFor = (role: User['role']) => (role === 'DRIVER' ? '/drive' : role === 'ADMIN' ? '/admin' : '/ride');
