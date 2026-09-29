import { locale, t } from './i18n';

export function timeAgo(iso: string): string {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31536000], ['month', 2592000], ['week', 604800],
    ['day', 86400], ['hour', 3600], ['minute', 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) {
      return new Intl.RelativeTimeFormat(locale(), { numeric: 'auto' }).format(Math.round(seconds / size), unit);
    }
  }
  return t('just now');
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'short', day: 'numeric' });
}

/** Formats a calendar date (yyyy-mm-dd) without shifting it through time zones. */
export function formatDay(day: string, withYear = false): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(year, month - 1, date).toLocaleDateString(locale(), {
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

/** 90 -> "1h 30m", 45 -> "45m", 120 -> "2h". */
export function formatMinutes(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (!hours) return `${rest}m`;
  return rest ? `${hours}h ${rest}m` : `${hours}h`;
}

/** Parses "1h 30m", "1.5h", "90m", "90" (minutes) or "1:30"; returns null when invalid. */
export function parseDuration(text: string): number | null {
  const value = text.trim().toLowerCase();
  if (!value) return null;
  const clock = /^(\d+):([0-5]\d)$/.exec(value);
  if (clock) return Number(clock[1]) * 60 + Number(clock[2]);
  if (/^\d+$/.test(value)) return Number(value);
  const match = /^(?:(\d+(?:[.,]\d+)?)\s*h)?\s*(?:(\d+)\s*m)?$/.exec(value);
  if (!match || (!match[1] && !match[2])) return null;
  const minutes = Math.round(Number((match[1] ?? '0').replace(',', '.')) * 60) + Number(match[2] ?? 0);
  return minutes > 0 ? minutes : null;
}
