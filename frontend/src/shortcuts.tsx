import { useEffect, useRef } from 'react';

export const FOCUS_SEARCH = 'fakejira:focus-search';

export const SHORTCUTS: [string, string][] = [
  ['Ctrl+K', 'Command palette: search tasks, projects and pages'],
  ['c', 'Create a task'],
  ['/', 'Search this page'],
  ['g then b', 'Go to board'],
  ['g then k', 'Go to backlog'],
  ['g then r', 'Go to sprint reports'],
  ['g then o', 'Go to roadmap'],
  ['g then m', 'Go to my work'],
  ['g then n', 'Go to notifications'],
  ['g then p', 'Go to projects'],
  ['g then d', 'Go to dashboard'],
  ['g then s', 'Go to search'],
  ['g then c', 'Go to calendar'],
  ['g then t', 'Go to Today'],
  ['j / k', 'Move down / up in a task list or the inbox'],
  ['Enter or o', 'Open the highlighted item'],
  ['e', 'Edit the highlighted task'],
  ['a', 'Assign the highlighted task'],
  ['s', 'Change the status of the highlighted task (snooze in the inbox)'],
  ['x', 'Select the highlighted item'],
  ['d', 'Inbox: mark the highlighted notification done'],
  ['?', 'Show keyboard shortcuts'],
];

function isTyping(target: EventTarget | null) {
  const element = target as HTMLElement | null;
  if (!element) return false;
  return element.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName);
}

/** Global single-key shortcuts; ignored while typing or when a dialog is open. */
export function useShortcuts(handlers: {
  create: () => void;
  go: (target: 'b' | 'k' | 'r' | 'o' | 'm' | 'n' | 'p' | 'd' | 's' | 'c' | 't') => void;
  help: () => void;
}) {
  const latest = useRef(handlers);
  latest.current = handlers;

  useEffect(() => {
    let pendingG = 0;
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return;
      if (document.querySelector('.modal')) return;
      const key = event.key;
      if (pendingG && Date.now() - pendingG < 1200 && 'bkromnpdsct'.includes(key) && key.length === 1) {
        event.preventDefault();
        pendingG = 0;
        latest.current.go(key as 'b' | 'k' | 'r' | 'o' | 'm' | 'n' | 'p');
        return;
      }
      pendingG = 0;
      if (key === 'g') {
        pendingG = Date.now();
      } else if (key === 'c') {
        event.preventDefault();
        latest.current.create();
      } else if (key === '/') {
        event.preventDefault();
        window.dispatchEvent(new Event(FOCUS_SEARCH));
      } else if (key === '?') {
        event.preventDefault();
        latest.current.help();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
}

/** Lets a page's search box respond to the "/" shortcut. */
export function useFocusSearch(ref: React.RefObject<HTMLInputElement | null>) {
  useEffect(() => {
    const focus = () => {
      ref.current?.focus();
      ref.current?.select();
    };
    window.addEventListener(FOCUS_SEARCH, focus);
    return () => window.removeEventListener(FOCUS_SEARCH, focus);
  }, [ref]);
}
