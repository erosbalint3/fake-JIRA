import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  AlarmClock, ArrowDown, ArrowUp, CalendarCheck, ChevronLeft, ChevronRight, ClipboardCopy, Plus, Search, Sun, X,
} from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { formatDay, formatMinutes, todayIso } from '../format';
import { TaskRow } from '../components/TaskRow';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { Modal } from '../components/Modal';
import { useListNavigation } from '../components/ListNavigation';
import { RemindersPanel } from '../components/Reminders';
import type { DaySummary, Task, TodayList } from '../types';
import { t } from '../i18n';

function shift(day: string, days: number) {
  const [y, m, d] = day.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Plan the day: pick tasks, work through them, and wrap up with a summary. */
export function TodayPage() {
  const toast = useToast();
  const [day, setDay] = useState(todayIso());
  const [data, setData] = useState<TodayList | null>(null);
  const [error, setError] = useState('');
  const [summary, setSummary] = useState<DaySummary | null>(null);
  const [adding, setAdding] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const isToday = day === todayIso();

  const load = useCallback(() => {
    setError('');
    api.today(day).then(setData).catch((e: ApiError) => setError(e.message));
  }, [day]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'today' || m.type === 'task', load, 400);
  const picker = useListNavigation(listRef, load);

  const run = async (action: Promise<TodayList>) => {
    try {
      setData(await action);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const move = (index: number, by: number) => {
    if (!data) return;
    const ids = data.picks.map((p) => p.id);
    const [id] = ids.splice(index, 1);
    ids.splice(index + by, 0, id);
    run(api.reorderToday(ids, day));
  };

  const openSummary = () => api.daySummary(day).then(setSummary).catch((e: ApiError) => toast(e.message, 'error'));

  const done = data?.picks.filter((p) => p.status === 'DONE').length ?? 0;
  const total = data?.picks.length ?? 0;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1><Sun size={24} aria-hidden /> {isToday ? t('Today') : formatDay(day, true)}</h1>
          <p className="muted">{t('Pick what you will work on; your plan is private to you.')}</p>
        </div>
        <div className="row-actions">
          <button className="icon-button" onClick={() => setDay(shift(day, -1))} aria-label={t('Previous day')}><ChevronLeft size={18} /></button>
          <button className="btn btn-soft" onClick={() => setDay(todayIso())} disabled={isToday}>{t('Today')}</button>
          <button className="icon-button" onClick={() => setDay(shift(day, 1))} aria-label={t('Next day')}><ChevronRight size={18} /></button>
          <button className="btn btn-primary" onClick={openSummary}><CalendarCheck size={18} /> {t('Wrap up the day')}</button>
        </div>
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!data && !error && <Spinner />}

      {data && data.carryOver.length > 0 && (
        <section className="panel carry-over">
          <div className="panel-title-row">
            <h2>{t('Unfinished from {day}', { day: formatDay(data.previousDay ?? '') })} <span className="count">{data.carryOver.length}</span></h2>
            <button className="btn btn-soft" onClick={() => run(api.carryOver(day))}>{t('Carry all over')}</button>
          </div>
          <ul className="task-list">
            {data.carryOver.map((task) => (
              <TaskRow key={task.id} task={task} showProject actions={
                <button className="btn btn-soft btn-sm" onClick={() => run(api.pickToday(task.id, day))}>
                  <Plus size={14} /> {t('Add')}
                </button>
              } />
            ))}
          </ul>
        </section>
      )}

      {data && (
        <div ref={listRef}>
          <section className="group">
            <div className="panel-title-row">
              <h2 className="group-title">{t('My plan')} <span className="count muted-count">{total}</span></h2>
              {total > 0 && (
                <div className="today-progress" aria-label={t('{done} of {total} done', { done, total })}>
                  <div className="progress"><span style={{ width: `${(done / total) * 100}%` }} /></div>
                  <span className="muted small">{t('{done} of {total} done', { done, total })}</span>
                </div>
              )}
            </div>
            {total === 0 ? (
              <EmptyState icon={<Sun size={28} />} title={t('Nothing planned yet')}>
                {t('Add tasks from the suggestions below, or search for one.')}
              </EmptyState>
            ) : (
              <ul className="task-list">
                {data.picks.map((task, index) => (
                  <TaskRow key={task.id} task={task} showProject className={task.status === 'DONE' ? 'is-done' : ''} actions={
                    <span className="row-actions">
                      <button className="icon-button" disabled={index === 0} onClick={() => move(index, -1)}
                        aria-label={t('Move {key} up', { key: task.key })}><ArrowUp size={15} /></button>
                      <button className="icon-button" disabled={index === total - 1} onClick={() => move(index, 1)}
                        aria-label={t('Move {key} down', { key: task.key })}><ArrowDown size={15} /></button>
                      <button className="icon-button" onClick={() => run(api.unpickToday(task.id, day))}
                        aria-label={t('Remove {key} from the plan', { key: task.key })}><X size={15} /></button>
                    </span>
                  } />
                ))}
              </ul>
            )}
            <button className="btn btn-ghost" onClick={() => setAdding(true)}><Search size={16} /> {t('Find a task to add')}</button>
          </section>

          {data.suggestions.length > 0 && (
            <section className="group">
              <h2 className="group-title">{t('Suggested')} <span className="count muted-count">{data.suggestions.length}</span></h2>
              <p className="muted small">{t('Assigned to you and overdue, due soon or in progress.')}</p>
              <ul className="task-list">
                {data.suggestions.map((task) => (
                  <TaskRow key={task.id} task={task} showProject actions={
                    <button className="btn btn-soft btn-sm" onClick={() => run(api.pickToday(task.id, day))}>
                      <Plus size={14} /> {t('Add')}
                    </button>
                  } />
                ))}
              </ul>
            </section>
          )}
        </div>
      )}

      <RemindersPanel />

      {adding && <AddTaskModal onClose={() => setAdding(false)} exclude={data?.picks ?? []}
        onPick={(task) => run(api.pickToday(task.id, day))} />}
      {summary && <SummaryModal summary={summary} onClose={() => setSummary(null)} />}
      {picker}
    </div>
  );
}

function AddTaskModal({ onPick, onClose, exclude }: { onPick: (task: Task) => void; onClose: () => void; exclude: Task[] }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Task[] | null>(null);

  useEffect(() => {
    const q = query.trim();
    const timer = window.setTimeout(() => {
      api.tasks(q ? { q } : { scope: 'MINE' })
        .then((list) => setResults(list.filter((t) => t.status !== 'DONE' && !exclude.some((e) => e.id === t.id)).slice(0, 30)))
        .catch(() => setResults([]));
    }, q ? 250 : 0);
    return () => window.clearTimeout(timer);
  }, [query, exclude]);

  return (
    <Modal title={t('Add a task to your day')} onClose={onClose} wide>
      <label className="search">
        <Search size={16} />
        <input value={query} placeholder={t('Search by key or title')} aria-label={t('Search tasks')}
          onChange={(e) => setQuery(e.target.value)} />
      </label>
      {!results && <Spinner />}
      {results && results.length === 0 && <p className="muted">{t('No open tasks match.')}</p>}
      {results && results.length > 0 && (
        <ul className="task-list picker-list">
          {results.map((task) => (
            <TaskRow key={task.id} task={task} showProject actions={
              <button className="btn btn-soft btn-sm" onClick={() => {
                onPick(task);
                onClose();
              }}><Plus size={14} /> {t('Add')}</button>
            } />
          ))}
        </ul>
      )}
    </Modal>
  );
}

function SummaryModal({ summary, onClose }: { summary: DaySummary; onClose: () => void }) {
  const toast = useToast();
  const copy = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await navigator.clipboard.writeText(summary.text);
      toast(t('Summary copied'));
    } catch {
      toast(t('Could not copy; select the text instead.'), 'error');
    }
  };
  return (
    <Modal title={t('Your day: {day}', { day: formatDay(summary.date, true) })} onClose={onClose} wide footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Close')}</button>
        <button className="btn btn-primary" onClick={copy}><ClipboardCopy size={16} /> {t('Copy for stand-up')}</button>
      </>
    }>
      <div className="summary-stats">
        <div><strong>{summary.completed.length}</strong><span className="muted small">{t('finished')}</span></div>
        <div><strong>{summary.unfinished.length}</strong><span className="muted small">{t('still open')}</span></div>
        <div><strong>{formatMinutes(summary.minutesLogged)}</strong><span className="muted small">{t('logged')}</span></div>
        <div><strong>{summary.comments}</strong><span className="muted small">{t('comments')}</span></div>
      </div>
      {summary.completed.length > 0 && (
        <>
          <h3>{t('Finished')}</h3>
          <ul className="plain-list">
            {summary.completed.map((task) => <li key={task.id}><Link to={`/tasks/${task.id}`} onClick={onClose}>{task.key}</Link> {task.title}</li>)}
          </ul>
        </>
      )}
      {summary.unfinished.length > 0 && (
        <p className="muted"><AlarmClock size={14} aria-hidden /> {t('Unfinished tasks are offered again on your next planned day.')}</p>
      )}
      <label className="field">
        <span>{t('Text to share')}</span>
        <textarea readOnly value={summary.text} rows={Math.min(14, summary.text.split('\n').length + 1)} />
      </label>
    </Modal>
  );
}
