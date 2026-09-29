import { api, tokenStore } from './api';

/**
 * Deletes wait a few seconds so they can be undone. If the page closes first, the delete is sent right
 * away (keepalive) so nothing is silently kept.
 */
const pending = new Map<number, { taskIds: number[]; timer: number }>();
let nextId = 1;

function flush(taskIds: number[]) {
  const token = tokenStore.get();
  if (!token) return;
  for (const id of taskIds) {
    fetch(`/api/tasks/${id}`, { method: 'DELETE', keepalive: true, headers: { Authorization: `Bearer ${token}` } })
      .catch(() => {});
  }
}

window.addEventListener('pagehide', () => {
  pending.forEach(({ taskIds, timer }) => {
    window.clearTimeout(timer);
    flush(taskIds);
  });
  pending.clear();
});

/** Schedules deleting tasks after {@code delay} ms; returns a function that cancels it. */
export function deleteLater(taskIds: number[], onDone: (error?: Error) => void, delay = 6500): () => void {
  const id = nextId++;
  const timer = window.setTimeout(async () => {
    pending.delete(id);
    try {
      if (taskIds.length === 1) await api.deleteTask(taskIds[0]);
      else await api.bulk(taskIds, { delete: true });
      onDone();
    } catch (e) {
      onDone(e as Error);
    }
  }, delay);
  pending.set(id, { taskIds, timer });
  return () => {
    window.clearTimeout(timer);
    pending.delete(id);
  };
}

/** Task ids whose delete is still pending (hidden from lists until then). */
export function pendingDeletes(): Set<number> {
  const ids = new Set<number>();
  pending.forEach(({ taskIds }) => taskIds.forEach((t) => ids.add(t)));
  return ids;
}
