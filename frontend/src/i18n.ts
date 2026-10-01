import { useSyncExternalStore } from 'react';
import { HU } from './i18n.hu';
import { DE } from './i18n.de';
import { ES } from './i18n.es';

/**
 * Tiny translation layer: English text is the key, so untranslated strings simply stay English.
 * t('Create task'), t('{n} tasks', { n: 3 }).
 */
export type Language = 'en' | 'hu' | 'de' | 'es';
const LANGUAGES: Language[] = ['en', 'hu', 'de', 'es'];
const DICTIONARIES: Partial<Record<Language, Record<string, string>>> = { hu: HU, de: DE, es: ES };
const isLanguage = (value: unknown): value is Language => LANGUAGES.includes(value as Language);

const KEY = 'fakejira.lang';
const listeners = new Set<() => void>();

function initial(): Language {
  try {
    const saved = localStorage.getItem(KEY);
    if (isLanguage(saved)) return saved;
  } catch {
    /* storage unavailable */
  }
  const browser = typeof navigator !== 'undefined' ? navigator.language?.toLowerCase().slice(0, 2) : 'en';
  return isLanguage(browser) ? browser : 'en';
}

let current: Language = initial();
if (typeof document !== 'undefined') document.documentElement.lang = current;

export function language(): Language {
  return current;
}

/** BCP 47 tag for Intl date/number formatting. */
export function locale(): string | undefined {
  return { en: undefined, hu: 'hu-HU', de: 'de-DE', es: 'es-ES' }[current];
}

export function setLanguage(next: string | null | undefined) {
  const lang: Language = isLanguage(next) ? next : 'en';
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
  let out = DICTIONARIES[current]?.[text] ?? text;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) out = out.split(`{${k}}`).join(String(v));
  }
  return out;
}
