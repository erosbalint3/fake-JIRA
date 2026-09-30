import { useEffect, useState, useSyncExternalStore } from 'react';

type Theme = 'light' | 'dark';
export type ThemeChoice = Theme | 'system';

/** How the app looks for this person; kept on the device and synced to their account. */
export interface Appearance {
  theme: ThemeChoice;
  contrast: 'normal' | 'high';
  density: 'comfortable' | 'compact';
  font: 'default' | 'system' | 'serif' | 'readable' | 'mono';
  textSize: 'small' | 'normal' | 'large' | 'larger';
  reduceMotion: boolean;
}

const KEY = 'fakejira.theme';
const APPEARANCE_KEY = 'fakejira.appearance';
export const DEFAULT_APPEARANCE: Appearance = {
  theme: 'system', contrast: 'normal', density: 'comfortable', font: 'default', textSize: 'normal', reduceMotion: false,
};

const listeners = new Set<() => void>();
let current: Appearance = readStored();

function readStored(): Appearance {
  try {
    const raw = localStorage.getItem(APPEARANCE_KEY);
    const stored = raw ? { ...DEFAULT_APPEARANCE, ...JSON.parse(raw) } as Appearance : { ...DEFAULT_APPEARANCE };
    // Older versions kept only the light/dark choice.
    const legacy = localStorage.getItem(KEY);
    if (!raw && (legacy === 'light' || legacy === 'dark')) stored.theme = legacy;
    return stored;
  } catch {
    return { ...DEFAULT_APPEARANCE };
  }
}

function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function resolved(choice: ThemeChoice): Theme {
  return choice === 'system' ? systemTheme() : choice;
}

function apply(a: Appearance) {
  const root = document.documentElement;
  root.dataset.theme = resolved(a.theme);
  root.dataset.contrast = a.contrast;
  root.dataset.density = a.density;
  root.dataset.font = a.font;
  root.dataset.textSize = a.textSize;
  if (a.reduceMotion) root.dataset.reduceMotion = 'true';
  else delete root.dataset.reduceMotion;
}

export function applyInitialTheme() {
  apply(current);
  // "Match system" follows the operating system when it switches.
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
    if (current.theme === 'system') apply(current);
  });
}

export function appearance(): Appearance {
  return current;
}

/** Changes the appearance here; {@code sync} (default) also saves it to the account. */
export function setAppearance(next: Appearance, sync = true) {
  current = next;
  apply(next);
  try {
    localStorage.setItem(APPEARANCE_KEY, JSON.stringify(next));
    localStorage.setItem(KEY, resolved(next.theme));
  } catch {
    /* ignore */
  }
  listeners.forEach((l) => l());
  if (sync) syncHandler?.(next);
}

let syncHandler: ((a: Appearance) => void) | null = null;
/** Set once signed in: saves appearance changes to the account (see prefs.ts). */
export function onAppearanceChange(handler: ((a: Appearance) => void) | null) {
  syncHandler = handler;
}

export function useAppearance(): Appearance {
  return useSyncExternalStore((cb) => {
    listeners.add(cb);
    return () => listeners.delete(cb);
  }, () => current, () => current);
}

/** The light/dark switch in the sidebar and command palette. */
export function useTheme() {
  const a = useAppearance();
  const [theme, setTheme] = useState<Theme>(() => resolved(a.theme));
  useEffect(() => setTheme(resolved(a.theme)), [a.theme]);
  return {
    theme,
    toggle: () => setAppearance({ ...current, theme: resolved(current.theme) === 'dark' ? 'light' : 'dark' }),
  };
}
