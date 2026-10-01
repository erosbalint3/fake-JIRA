import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Coffee, Presentation } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, formatMinutes, todayIso } from '../format';
import type { Standup, StandupPerson, TaskRef } from '../types';
import { t } from '../i18n';

function shift(day: string, days: number) {
  const d = new Date(`${day}T00:00:00`);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** The daily stand-up from real activity; "Walk through" shows one person at a time. */
export function StandupPage() {
  const { key, project, loading } = useRouteProject();
  const [date, setDate] = useState(todayIso());
  const [data, setData] = useState<Standup | null>(null);
  const [error, setError] = useState('');
  const [walk, setWalk] = useState<number | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.standup(key, date).then(setData).catch((e: ApiError) => setError(e.message));
  }, [key, project, date]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.projectId === project?.id, load, 1500);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;
  const people = data?.people ?? [];
  const shown = walk === null ? people : people.slice(walk, walk + 1);

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t('Daily stand-up')}</h1>
          <p className="muted">{data ? t('What happened since {since}, what is in progress, and what is stuck.', { since: formatDay(data.since) }) : ''}</p>
        </div>
        <div className="header-actions">
          <div className="btn-group">
            <button className="icon-button" aria-label={t('Previous day')} onClick={() => setDate(shift(date, -1))}><ChevronLeft size={18} /></button>
            <input type="date" value={date} aria-label={t('Date')} onChange={(e) => e.target.value && setDate(e.target.value)} />
            <button className="icon-button" aria-label={t('Next day')} onClick={() => setDate(shift(date, 1))}><ChevronRight size={18} /></button>
          </div>
          {walk === null
            ? <button className="btn btn-primary" disabled={!people.length} onClick={() => setWalk(0)}><Presentation size={16} /> {t('Walk through')}</button>
            : (
              <div className="btn-group">
                <button className="btn btn-ghost" disabled={walk === 0} onClick={() => setWalk(walk - 1)}>{t('Previous')}</button>
                <span className="muted small">{walk + 1} / {people.length}</span>
                {walk < people.length - 1
                  ? <button className="btn btn-primary" onClick={() => setWalk(walk + 1)}>{t('Next person')}</button>
                  : <button className="btn btn-primary" onClick={() => setWalk(null)}>{t('Done')}</button>}
              </div>
            )}
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!data && !error && <Spinner />}
      <div className={`standup-grid ${walk !== null ? 'walking' : ''}`}>
        {shown.map((p) => <PersonCard key={p.user.id} person={p} />)}
      </div>
    </div>
  );
}

function List({ title, tasks, empty, tone }: { title: string; tasks: TaskRef[]; empty: string; tone?: string }) {
  return (
    <div className={`standup-list ${tone ?? ''}`}>
      <h4>{title}</h4>
      {tasks.length === 0 ? <p className="muted small">{empty}</p> : (
        <ul>{tasks.map((task) => <li key={task.id}><Link to={`/tasks/${task.id}`}><span className="task-key">{task.key}</span> {task.title}</Link></li>)}</ul>
      )}
    </div>
  );
}

function PersonCard({ person }: { person: StandupPerson }) {
  return (
    <article className="panel standup-card">
      <header className="standup-head">
        <Avatar user={person.user} size={32} />
        <strong>{person.user.displayName}</strong>
        {person.away && <span className="away-badge"><Coffee size={12} /> {t('away')}</span>}
        {person.minutesLogged > 0 && <span className="muted small">{t('{time} logged', { time: formatMinutes(person.minutesLogged) })}</span>}
      </header>
      <List title={t('Finished')} tasks={person.finished} empty={t('Nothing finished')} tone="done" />
      <List title={t('Also worked on')} tasks={person.workedOn} empty={t('No other activity')} />
      <List title={t('In progress now')} tasks={person.today} empty={t('Nothing in progress')} />
      <List title={t('Blocked')} tasks={person.blocked} empty={t('Nothing blocked')} tone="blocked" />
    </article>
  );
}
