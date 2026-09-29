import { useSyncExternalStore } from 'react';
import { HU } from './i18n.hu';

/**
 * Tiny translation layer: English text is the key, so untranslated strings simply stay English.
 * t('Create task'), t('{n} tasks', { n: 3 }).
 */
export type Language = 'en' | 'hu';

const KEY = 'fakejira.lang';
const listeners = new Set<() => void>();

function initial(): Language {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'en' || saved === 'hu') return saved;
  } catch {
    /* storage unavailable */
  }
  return typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('hu') ? 'hu' : 'en';
}

let current: Language = initial();
if (typeof document !== 'undefined') document.documentElement.lang = current;

export function language(): Language {
  return current;
}

/** BCP 47 tag for Intl date/number formatting. */
export function locale(): string | undefined {
  return current === 'hu' ? 'hu-HU' : undefined;
}

export function setLanguage(next: string | null | undefined) {
  const lang: Language = next === 'hu' ? 'hu' : 'en';
  try {
    localStorage.setItem(KEY, lang);
  } catch {
    /* storage unavailable */
  }
  if (lang === current) return;
  current = lang;
  document.documentElement.lang = lang;
  listeners.forEach((l) => l());
}

export function useLanguage(): Language {
  return useSyncExternalStore((cb) => {
    listeners.add(cb);
    return () => listeners.delete(cb);
  }, () => current, () => current);
}

export function t(text: string, vars?: Record<string, string | number>): string {
  let out = current === 'hu' ? HU[text] ?? text : text;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) out = out.split(`{${k}}`).join(String(v));
  }
  return out;
}
