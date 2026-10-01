import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CalendarPlus, ChevronLeft, ChevronRight, ClipboardCopy } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useProjects } from '../projects';
import { Modal } from '../components/Modal';
import { ErrorBanner, Spinner } from '../components/States';
import { todayIso } from '../format';
import type { CalendarEvent } from '../types';
import { locale, t } from '../i18n';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function iso(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Monday on or before the 1st of the month, through the Sunday after its last day. */
function monthGrid(year: number, month: number) {
  const first = new Date(year, month, 1);
  const start = new Date(year, month, 1 - ((first.getDay() + 6) % 7));
  const last = new Date(year, month + 1, 0);
  const end = new Date(year, month + 1, last.getDay() === 0 ? 0 : 7 - last.getDay());
  const days: string[] = [];
  for (const d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) days.push(iso(d));
  return days;
}

function eventLink(e: CalendarEvent) {
  switch (e.kind) {
    case 'TASK': return `/tasks/${e.id}`;
    case 'SPRINT': return `/p/${e.projectKey}/sprints/${e.id}`;
    case 'RELEASE': return `/p/${e.projectKey}/releases`;
    case 'EPIC': return `/p/${e.projectKey}/roadmap`;
    default: return null;
  }
}

function label(e: CalendarEvent) {
  switch (e.kind) {
    case 'TASK': return `${e.key} ${e.title}`;
    case 'SPRINT': return `${e.title}`;
    case 'RELEASE': return `🚀 ${e.projectKey} ${e.title}`;
    case 'EPIC': return e.title;
    default: return `${e.person?.displayName ?? 'Someone'}: ${e.title}`;
  }
}

export function CalendarPage() {
  const { projects } = useProjects();
  const today = todayIso();
  const [month, setMonth] = useState(() => {
    const d = new Date();
    return { year: d.getFullYear(), month: d.getMonth() };
  });
  const [project, setProject] = useState('');
  const [show, setShow] = useState({ TASK: true, SPRINT: true, RELEASE: true, EPIC: false, AWAY: true });
  const [events, setEvents] = useState<CalendarEvent[] | null>(null);
  const [error, setError] = useState('');
  const [subscribing, setSubscribing] = useState(false);
  const days = useMemo(() => monthGrid(month.year, month.month), [month]);

  const load = useCallback(() => {
    api.calendar(days[0], days[days.length - 1], project || undefined)
      .then(setEvents).catch((e: ApiError) => setError(e.message));
  }, [days, project]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' || m.type === 'project', load, 1000);

  const byDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    (events ?? []).filter((e) => show[e.kind]).forEach((e) => {
      // Multi-day events show on each day of the visible grid.
      for (const day of days) {
        if (day >= e.start && day <= e.end) map.set(day, [...(map.get(day) ?? []), e]);
      }
    });
    return map;
  }, [events, show, days]);

  const title = new Date(month.year, month.month, 1).toLocaleDateString(locale(), { month: 'long', year: 'numeric' });
  const shift = (delta: number) => setMonth(({ year, month: m }) => {
    const d = new Date(year, m + delta, 1);
    return { year: d.getFullYear(), month: d.getMonth() };
  });

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t("Calendar")}</span>
          <h1>{title}</h1>
        </div>
        <div className="header-actions">
          <select value={project} onChange={(e) => setProject(e.target.value)} aria-label={t("Project")}>
            <option value="">{t("All projects")}</option>
            {(projects ?? []).map((p) => <option key={p.key} value={p.key}>{p.key} · {p.name}</option>)}
          </select>
          <div className="btn-group">
            <button className="icon-button" aria-label={t("Previous month")} onClick={() => shift(-1)}><ChevronLeft size={18} /></button>
            <button className="btn btn-ghost btn-sm" onClick={() => {
              const d = new Date();
              setMonth({ year: d.getFullYear(), month: d.getMonth() });
            }}>{t('Today')}</button>
            <button className="icon-button" aria-label={t("Next month")} onClick={() => shift(1)}><ChevronRight size={18} /></button>
          </div>
          <button className="btn btn-soft btn-sm" onClick={() => setSubscribing(true)}><CalendarPlus size={15} /> {t("Subscribe")}</button>
        </div>
      </header>
      <div className="chip-row cal-legend" role="group" aria-label={t("Show")}>
        {(['TASK', 'SPRINT', 'RELEASE', 'EPIC', 'AWAY'] as const).map((kind) => (
          <label key={kind} className={`chip ${show[kind] ? 'active' : ''}`}>
            <input type="checkbox" className="sr-only" checked={show[kind]} onChange={(e) => setShow({ ...show, [kind]: e.target.checked })} />
            <span className={`cal-dot cal-${kind.toLowerCase()}`} />
            {t({ TASK: 'Due dates', SPRINT: 'Sprints', RELEASE: 'Releases', EPIC: 'Epics', AWAY: 'Time off' }[kind])}
          </label>
        ))}
      </div>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!events && !error && <Spinner />}
      {events && (
        <div className="calendar" role="group" aria-label={title}>
          {WEEKDAYS.map((d) => <div key={d} className="cal-head" aria-hidden>{t(d)}</div>)}
          {days.map((day) => {
            const list = byDay.get(day) ?? [];
            const inMonth = Number(day.slice(5, 7)) - 1 === month.month;
            return (
              <div key={day} role="group" className={`cal-day ${inMonth ? '' : 'other'} ${day === today ? 'today' : ''}`}
                aria-label={`${day}: ${list.length === 1 ? t('1 item') : t('{n} items', { n: list.length })}`}>
                <span className="cal-date">{Number(day.slice(8))}</span>
                <ul>
                  {list.slice(0, 4).map((e) => {
                    const to = eventLink(e);
                    const className = `cal-event cal-${e.kind.toLowerCase()} ${e.kind === 'TASK' && e.status === 'DONE' ? 'done' : ''}
                      ${e.kind === 'TASK' && e.status !== 'DONE' && e.start < today ? 'late' : ''}`;
                    return (
                      <li key={`${e.kind}-${e.id}`}>
                        {to ? <Link to={to} className={className} title={label(e)}>{label(e)}</Link>
                          : <span className={className} title={label(e)}>{label(e)}</span>}
                      </li>
                    );
                  })}
                  {list.length > 4 && <li className="muted small">{t('+{n} more', { n: list.length - 4 })}</li>}
                </ul>
              </div>
            );
          })}
        </div>
      )}
      {subscribing && <SubscribeModal onClose={() => setSubscribing(false)} />}
    </div>
  );
}

/** Shows (and creates/resets/turns off) the personal iCal feed URL. */
export function SubscribeModal({ onClose }: { onClose: () => void }) {
  const toast = useToast();
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    api.calendarFeed().then((r) => setUrl(r.url)).catch(() => setUrl(null));
  }, []);
  const run = (action: () => Promise<{ url: string | null }>, message: string) =>
    action().then((r) => {
      setUrl(r.url);
      toast(message);
    }).catch((e: ApiError) => toast(e.message, 'error'));
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url ?? '');
      toast(t("Feed URL copied"));
    } catch {
      toast(t("Could not copy"), 'error');
    }
  };
  return (
    <Modal title={t("Subscribe in your calendar app")} onClose={onClose} footer={<button className="btn btn-ghost" onClick={onClose}>{t("Close")}</button>}>
      <div className="form">
        <p className="muted">{t("Add FakeJIRA to Google Calendar, Outlook or Apple Calendar: your tasks' due dates, sprints and releases. The link is private — anyone who has it can see these events.")}
          </p>
        {url === undefined ? <Spinner /> : url ? (
          <>
            <div className="copy-field">
              <input readOnly value={url} aria-label={t("Feed URL")} onFocus={(e) => e.target.select()} />
              <button className="btn btn-primary btn-sm" onClick={copy}><ClipboardCopy size={15} /> {t("Copy")}</button>
            </div>
            <div className="chip-row">
              <button className="btn btn-ghost btn-sm" onClick={() => run(api.resetCalendarFeed, t('New link created; the old one stopped working'))}>
                {t('Reset link')}</button>
              <button className="btn btn-ghost btn-sm danger" onClick={() => run(api.disableCalendarFeed, t('Calendar feed turned off'))}>
                {t('Turn off')}</button>
            </div>
          </>
        ) : (
          <div><button className="btn btn-primary" onClick={() => run(api.resetCalendarFeed, t('Calendar feed created'))}>
            <CalendarPlus size={16} /> {t("Create my feed link")}</button></div>
        )}
      </div>
    </Modal>
  );
}
