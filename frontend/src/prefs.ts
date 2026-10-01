import { useCallback, useSyncExternalStore } from 'react';
import { api } from './api';
import { DEFAULT_APPEARANCE, onAppearanceChange, setAppearance, type Appearance } from './theme';

/**
 * Personal settings that follow people across devices (card fields, saved views, appearance…), loaded once after
 * sign-in and saved as they change.
 */
const cache = new Map<string, unknown>();
const listeners = new Set<() => void>();
let loading: Promise<void> | null = null;
let version = 0;

function notify() {
  version++;
  listeners.forEach((l) => l());
}

export function loadPreferences(): Promise<void> {
  loading ??= api.preferences().then((all) => {
    Object.entries(all).forEach(([key, value]) => cache.set(key, value));
    const stored = all.appearance as Partial<Appearance> | undefined;
    if (stored && typeof stored === 'object') setAppearance({ ...DEFAULT_APPEARANCE, ...stored }, false);
    onAppearanceChange((a) => {
      cache.set('appearance', a);
      api.setPreference('appearance', a).catch(() => {});
    });
    notify();
  }).catch(() => {
    loading = null;
  });
  return loading;
}

export function resetPreferences() {
  cache.clear();
  loading = null;
  onAppearanceChange(null);
  notify();
}

/** A synced setting: [value, set]. {@code fallback} until the server's copy arrives (or when there is none). */
export function usePreference<T>(key: string, fallback: T): [T, (value: T | null) => void] {
  useSyncExternalStore((cb) => {
    listeners.add(cb);
    return () => listeners.delete(cb);
  }, () => version, () => version);
  const value = cache.has(key) ? (cache.get(key) as T) : fallback;
  const set = useCallback((next: T | null) => {
    if (next === null) {
      cache.delete(key);
      api.deletePreference(key).catch(() => {});
    } else {
      cache.set(key, next);
      api.setPreference(key, next).catch(() => {});
    }
    notify();
  }, [key]);
  return [value, set];
}
