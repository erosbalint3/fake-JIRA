import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import * as Y from 'yjs';
import { api, ApiError } from './api';
import { useLive } from './live';
import type { User } from './types';

/** One id per browser tab, so the same person in two tabs counts as two editors. */
export const CLIENT_ID = (() => {
  const bytes = new Uint8Array(9);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(36).padStart(2, '0')).join('').slice(0, 16);
})();

function toBase64(bytes: Uint8Array) {
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary);
}

function fromBase64(text: string) {
  return Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
}

export interface Present {
  user: User;
  editing: boolean;
}

/**
 * Tells the server this tab has the task open (and whether it is editing), every 20 seconds, and returns the
 * other people who have it open.
 */
export function usePresence(taskId: number | null, editing: boolean, selfId?: number) {
  const { subscribe } = useLive();
  const [present, setPresent] = useState<Present[]>([]);
  const editingRef = useRef(editing);
  editingRef.current = editing;

  useEffect(() => {
    if (!taskId) return;
    let stopped = false;
    const beat = () => api.presenceHeartbeat(taskId, CLIENT_ID, editingRef.current)
      .then((list) => !stopped && setPresent(list)).catch(() => {});
    beat();
    const timer = window.setInterval(beat, 20000);
    const unsubscribe = subscribe((m) => {
      if (m.type === 'presence' && m.data.taskId === taskId) {
        api.presence(taskId).then((list) => !stopped && setPresent(list)).catch(() => {});
      }
    });
    // Best effort; the server also forgets tabs that stop sending heartbeats.
    const leave = () => api.presenceLeave(taskId, CLIENT_ID).catch(() => {});
    window.addEventListener('pagehide', leave);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      unsubscribe();
      window.removeEventListener('pagehide', leave);
      api.presenceLeave(taskId, CLIENT_ID).catch(() => {});
    };
  }, [taskId, subscribe]);

  // Tell others straight away when this tab starts or stops editing.
  useEffect(() => {
    if (taskId) api.presenceHeartbeat(taskId, CLIENT_ID, editing).then(setPresent).catch(() => {});
  }, [taskId, editing]);

  return present.filter((p) => p.user.id !== selfId);
}

/** Replaces the smallest changed span, so concurrent edits elsewhere in the text survive. */
function applyDiff(text: Y.Text, before: string, after: string) {
  let start = 0;
  while (start < before.length && start < after.length && before[start] === after[start]) start++;
  let endBefore = before.length;
  let endAfter = after.length;
  while (endBefore > start && endAfter > start && before[endBefore - 1] === after[endAfter - 1]) {
    endBefore--;
    endAfter--;
  }
  if (endBefore > start) text.delete(start, endBefore - start);
  if (endAfter > start) text.insert(start, after.slice(start, endAfter));
}

export type CollabStatus = 'off' | 'connecting' | 'live' | 'offline';

/**
 * Live co-editing of a task's description (a Yjs CRDT relayed by the server). {@code value}/{@code setValue}
 * stay the source of truth for the form; remote edits are merged in and the caret is kept in place.
 */
export function useCollaborativeText(taskId: number | null, value: string, setValue: (text: string) => void,
  textarea: RefObject<HTMLTextAreaElement | null>): CollabStatus {
  const { subscribe } = useLive();
  const [status, setStatus] = useState<CollabStatus>(taskId ? 'connecting' : 'off');
  const docRef = useRef<Y.Doc | null>(null);
  const valueRef = useRef(value);
  valueRef.current = value;
  const setValueRef = useRef(setValue);
  setValueRef.current = setValue;
  const selection = useRef<{ start: number; end: number } | null>(null);
  // A later joiner waits for the shared text before sending its own edits, so the text is never doubled.
  const synced = useRef(false);

  useEffect(() => {
    if (!taskId) return;
    const doc = new Y.Doc();
    const text = doc.getText('description');
    let alive = true;
    let joined = false;
    synced.current = false;
    const pending: string[] = [];

    doc.on('update', (update: Uint8Array, origin: unknown) => {
      if (origin === 'remote' || !alive) return;
      const encoded = toBase64(update);
      if (!joined) {
        pending.push(encoded);
        return;
      }
      api.collabUpdate(taskId, CLIENT_ID, encoded).catch((e: ApiError) => alive && setStatus(e.status === 409 ? 'offline' : 'live'));
    });

    const applyRemote = (encoded: string) => {
      const area = textarea.current;
      const focused = area && document.activeElement === area;
      const relStart = focused ? Y.createRelativePositionFromTypeIndex(text, area.selectionStart) : null;
      const relEnd = focused ? Y.createRelativePositionFromTypeIndex(text, area.selectionEnd) : null;
      Y.applyUpdate(doc, fromBase64(encoded), 'remote');
      synced.current = true;
      const next = text.toString();
      if (next !== valueRef.current) {
        if (relStart && relEnd) {
          selection.current = {
            start: Y.createAbsolutePositionFromRelativePosition(relStart, doc)?.index ?? 0,
            end: Y.createAbsolutePositionFromRelativePosition(relEnd, doc)?.index ?? 0,
          };
        }
        valueRef.current = next;
        setValueRef.current(next);
      }
    };

    const unsubscribe = subscribe((m) => {
      if (m.type === 'collab' && m.data.taskId === taskId && m.data.clientId !== CLIENT_ID && m.data.update) {
        applyRemote(m.data.update);
      }
    });

    api.collabJoin(taskId, CLIENT_ID).then((result) => {
      if (!alive) return;
      result.updates.forEach(applyRemote);
      joined = true;
      if (result.seed) {
        // First editor: the saved description becomes the shared starting point.
        synced.current = true;
        doc.transact(() => text.insert(0, valueRef.current), 'local');
      }
      pending.splice(0).forEach((u) => api.collabUpdate(taskId, CLIENT_ID, u).catch(() => {}));
      docRef.current = doc;
      setStatus('live');
    }).catch(() => alive && setStatus('offline'));

    return () => {
      alive = false;
      unsubscribe();
      docRef.current = null;
      api.collabLeave(taskId, CLIENT_ID).catch(() => {});
      doc.destroy();
    };
  }, [taskId, subscribe, textarea]);

  // Local typing: push the change into the shared document.
  useEffect(() => {
    const doc = docRef.current;
    if (!doc || !synced.current) return;
    const text = doc.getText('description');
    const current = text.toString();
    if (current !== value) doc.transact(() => applyDiff(text, current, value), 'local');
  }, [value, status]);

  // Put the caret back where it was after a remote edit re-rendered the textarea.
  useLayoutEffect(() => {
    const area = textarea.current;
    if (area && selection.current) {
      area.setSelectionRange(selection.current.start, selection.current.end);
      selection.current = null;
    }
  });

  return status;
}
