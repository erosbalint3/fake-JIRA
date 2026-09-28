const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

export function timeAgo(iso: string): string {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31536000], ['month', 2592000], ['week', 604800],
    ['day', 86400], ['hour', 3600], ['minute', 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  }
  return 'just now';
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/** Formats a calendar date (yyyy-mm-dd) without shifting it through time zones. */
export function formatDay(day: string, withYear = false): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(year, month - 1, date).toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', ...(withYear ? { year: 'numeric' } : {}),
  });
}

export function todayIso(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

export type DueState = 'overdue' | 'today' | 'soon' | 'later';

export function dueState(day: string, done: boolean): DueState | null {
  if (done) return null;
  const today = todayIso();
  if (day < today) return 'overdue';
  if (day === today) return 'today';
  const [y, m, d] = day.split('-').map(Number);
  const days = (new Date(y, m - 1, d).getTime() - new Date(`${today}T00:00:00`).getTime()) / 86400000;
  return days <= 3 ? 'soon' : 'later';
}

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
