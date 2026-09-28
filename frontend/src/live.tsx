import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { streamEvents, type LiveMessage } from './api';
import { useAuth } from './auth';

type Listener = (message: LiveMessage) => void;

interface LiveState {
  connected: boolean;
  subscribe: (listener: Listener) => () => void;
}

const LiveContext = createContext<LiveState>({ connected: false, subscribe: () => () => {} });

/** Keeps one server-sent event stream open while signed in, reconnecting with backoff. */
export function LiveProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const listeners = useRef(new Set<Listener>());
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!user) return;
    const controller = new AbortController();
    let attempt = 0;
    let timer: number | undefined;

    const connect = async () => {
      try {
        await streamEvents((message) => {
          if (message.type === 'ready') {
            attempt = 0;
            setConnected(true);
          }
          listeners.current.forEach((listener) => listener(message));
        }, controller.signal);
      } catch {
        /* fall through to reconnect */
      }
      setConnected(false);
      if (controller.signal.aborted) return;
      attempt += 1;
      timer = window.setTimeout(connect, Math.min(30000, 1000 * 2 ** Math.min(attempt, 5)));
    };
    connect();
    return () => {
      controller.abort();
      window.clearTimeout(timer);
      setConnected(false);
    };
  }, [user]);

  const subscribe = useCallback((listener: Listener) => {
    listeners.current.add(listener);
    return () => {
      listeners.current.delete(listener);
    };
  }, []);

  const value = useMemo(() => ({ connected, subscribe }), [connected, subscribe]);
  return <LiveContext.Provider value={value}>{children}</LiveContext.Provider>;
}

export const useLive = () => useContext(LiveContext);

/**
 * Calls {@code refresh} (debounced) whenever a live event matches {@code filter}.
 * The latest callbacks are always used, so callers need not memoize them.
 */
export function useLiveRefresh(filter: (message: LiveMessage) => boolean, refresh: () => void, delay = 250) {
  const { subscribe } = useLive();
  const latest = useRef({ filter, refresh });
  latest.current = { filter, refresh };

  useEffect(() => {
    let timer: number | undefined;
    const unsubscribe = subscribe((message) => {
      if (!latest.current.filter(message)) return;
      window.clearTimeout(timer);
      timer = window.setTimeout(() => latest.current.refresh(), delay);
    });
    return () => {
      unsubscribe();
      window.clearTimeout(timer);
    };
  }, [subscribe, delay]);
}
