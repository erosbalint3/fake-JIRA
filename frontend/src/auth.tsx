import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { setLanguage } from './i18n';
import { api, ApiError, clearOfflineCache, setUnauthorizedHandler, tokenStore, type AuthResponse } from './api';
import { loadPreferences, resetPreferences } from './prefs';
import type { User } from './types';

/** What a sign-in attempt led to. */
export type SignInResult = { kind: 'signed-in' } | { kind: 'code'; challenge: string };

interface AuthState {
  user: User | null;
  admin: boolean;
  /** An admin requires a new password before anything else. */
  mustChangePassword: boolean;
  loading: boolean;
  login: (login: string, password: string) => Promise<SignInResult>;
  /** Second step with an authenticator or recovery code. */
  verifyCode: (challenge: string, code: string) => Promise<void>;
  /** Signs in with a token from Google/GitHub sign-in. */
  acceptToken: (token: string) => Promise<void>;
  /** Resolves to true when the account was created but waits for admin approval. */
  register: (username: string, email: string, password: string, inviteCode?: string) => Promise<boolean>;
  logout: () => void;
  /** Replaces the cached user after profile changes (name, avatar). */
  updateUser: (user: User) => void;
  passwordChanged: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [admin, setAdmin] = useState(false);
  const [mustChangePassword, setMustChangePassword] = useState(false);
  const [loading, setLoading] = useState(() => tokenStore.get() !== null);

  const clear = useCallback(() => {
    tokenStore.set(null);
    clearOfflineCache();
    resetPreferences();
    setUser(null);
    setAdmin(false);
    setMustChangePassword(false);
  }, []);

  const logout = useCallback(() => {
    // End the session on the server too, so the token stops working everywhere.
    if (tokenStore.get()) api.logout().catch(() => {});
    clear();
  }, [clear]);

  const loadMe = useCallback(async () => {
    const me = await api.me();
    setUser(me.user);
    setAdmin(me.admin);
    setMustChangePassword(me.mustChangePassword);
    setLanguage(me.language);
    loadPreferences();
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(clear);
    if (!tokenStore.get()) return;
    // Only a rejected token signs you out; an unreachable server keeps it for the next try.
    loadMe().catch((e) => (e instanceof ApiError && e.status === 0 ? undefined : clear())).finally(() => setLoading(false));
  }, [clear, loadMe]);

  const value = useMemo<AuthState>(() => {
    const signIn = async (response: AuthResponse): Promise<SignInResult> => {
      if (response.challenge) return { kind: 'code', challenge: response.challenge };
      if (!response.token) return { kind: 'signed-in' };
      tokenStore.set(response.token);
      await loadMe();
      return { kind: 'signed-in' };
    };
    return {
      user,
      admin,
      mustChangePassword,
      loading,
      logout,
      updateUser: setUser,
      passwordChanged: () => setMustChangePassword(false),
      login: async (login, password) => signIn(await api.login(login, password)),
      verifyCode: async (challenge, code) => {
        await signIn(await api.loginSecondStep(challenge, code));
      },
      acceptToken: async (token) => {
        tokenStore.set(token);
        try {
          await loadMe();
        } catch (e) {
          clear();
          throw e;
        }
      },
      register: async (username, email, password, inviteCode) => {
        const response = await api.register(username, email, password, inviteCode);
        if (response.pending) return true;
        await signIn(response);
        return false;
      },
    };
  }, [user, admin, mustChangePassword, loading, logout, loadMe, clear]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
