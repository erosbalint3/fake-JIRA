import { useCallback, useEffect, useRef, useState } from 'react';

const PREFIX = 'fakejira.draft.';
const MAX_AGE = 14 * 24 * 60 * 60 * 1000;

function read(key: string): string | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return null;
    const { text, savedAt } = JSON.parse(raw) as { text: string; savedAt: number };
    return Date.now() - savedAt > MAX_AGE ? null : text;
  } catch {
    return null;
  }
}

function write(key: string, text: string) {
  try {
    if (text.trim()) localStorage.setItem(PREFIX + key, JSON.stringify({ text, savedAt: Date.now() }));
    else localStorage.removeItem(PREFIX + key);
  } catch {
    /* storage unavailable: no drafts */
  }
}

/**
 * Text that survives a page refresh until it is sent: a comment, a description being edited, a new task.
 * Returns the value, a setter that also saves (debounced), and clear() for after sending.
 */
export function useDraft(key: string | null, initial = ''): [string, (text: string) => void, () => void] {
  const [value, setValue] = useState(() => (key ? read(key) : null) ?? initial);
  const timer = useRef<number>(undefined);
  const keyRef = useRef(key);

  useEffect(() => {
    if (keyRef.current !== key) {
      keyRef.current = key;
      setValue((key ? read(key) : null) ?? initial);
    }
  }, [key, initial]);

  const set = useCallback((text: string) => {
    setValue(text);
    window.clearTimeout(timer.current);
    const current = keyRef.current;
    if (current) timer.current = window.setTimeout(() => write(current, text), 300);
  }, []);

  const clear = useCallback(() => {
    window.clearTimeout(timer.current);
    setValue('');
    if (keyRef.current) write(keyRef.current, '');
  }, []);

  return [value, set, clear];
}

/** Whether a saved draft exists (for a "Draft" hint). */
export function hasDraft(key: string) {
  return read(key) !== null;
}

/** Stores structured drafts (e.g. the new-task form). */
export const draftStore = {
  get: <T,>(key: string): T | null => {
    const text = read(key);
    if (!text) return null;
    try {
      return JSON.parse(text) as T;
    } catch {
      return null;
    }
  },
  set: (key: string, value: unknown) => write(key, JSON.stringify(value)),
  clear: (key: string) => write(key, ''),
};
