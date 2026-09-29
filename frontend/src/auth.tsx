import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setUnauthorizedHandler, tokenStore, type AuthResponse } from './api';
import type { User } from './types';

interface AuthState {
  user: User | null;
  admin: boolean;
  loading: boolean;
  login: (login: string, password: string) => Promise<void>;
  /** Resolves to true when the account was created but waits for admin approval. */
  register: (username: string, email: string, password: string, inviteCode?: string) => Promise<boolean>;
  logout: () => void;
  /** Replaces the cached user after profile changes (name, avatar). */
  updateUser: (user: User) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [admin, setAdmin] = useState(false);
  const [loading, setLoading] = useState(() => tokenStore.get() !== null);

  const logout = useCallback(() => {
    tokenStore.set(null);
    setUser(null);
    setAdmin(false);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
    if (!tokenStore.get()) return;
    api.me()
      .then((me) => {
        setUser(me.user);
        setAdmin(me.admin);
      })
      .catch(() => logout())
      .finally(() => setLoading(false));
  }, [logout]);

  const value = useMemo<AuthState>(() => {
    const signIn = (response: AuthResponse) => {
      if (!response.token) return;
      tokenStore.set(response.token);
      setUser(response.user);
      setAdmin(response.admin);
    };
    return {
      user,
      admin,
      loading,
      logout,
      updateUser: setUser,
      login: async (login, password) => signIn(await api.login(login, password)),
      register: async (username, email, password, inviteCode) => {
        const response = await api.register(username, email, password, inviteCode);
        if (response.pending) return true;
        signIn(response);
        return false;
      },
    };
  }, [user, admin, loading, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
