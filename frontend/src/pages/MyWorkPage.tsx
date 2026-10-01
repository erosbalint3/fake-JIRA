import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { BadgeCheck, Coffee, Search } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useFocusSearch } from '../shortcuts';
import { TaskRow } from '../components/TaskRow';
import { useListNavigation } from '../components/ListNavigation';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { dueState } from '../format';
import { PRIORITY_ORDER, STATUSES, STATUS_LABEL, type Approval, type Task } from '../types';
import { timeAgo } from '../format';
import { t } from '../i18n';

/** Everything assigned to the current user across projects, overdue first. */
export function MyWorkPage() {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [showDone, setShowDone] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);
  useFocusSearch(searchRef);
  const listRef = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    setError('');
    api.tasks({ scope: 'MINE' }).then(setTasks).catch((e: ApiError) => setError(e.message));
    api.myApprovals().then(setApprovals).catch(() => {});
  }, []);

  useEffect(load, [load]);
  const picker = useListNavigation(listRef, load);
  useLiveRefresh((m) => m.type === 'task' || m.type === 'project', load, 500);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (tasks ?? []).filter((t) => !q || t.title.toLowerCase().includes(q) || t.key.toLowerCase().includes(q));
  }, [tasks, query]);

  const overdue = filtered.filter((t) => t.dueDate && dueState(t.dueDate, t.status === 'DONE') === 'overdue');
  const sortTasks = (list: Task[]) => [...list].sort((a, b) =>
    (a.dueDate ?? '9999').localeCompare(b.dueDate ?? '9999') || PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("My work")}</h1>
          <p className="muted">{t("Everything assigned to you or that you help with, across all your projects.")}</p>
        </div>
        <label className="toggle">
          <input type="checkbox" checked={showDone} onChange={(e) => setShowDone(e.target.checked)} /> {t('Show done')}
        </label>
      </header>

      <div className="toolbar">
        <label className="search">
          <Search size={16} />
          <input ref={searchRef} placeholder={t("Search my tasks  ( / )")} value={query}
            onChange={(e) => setQuery(e.target.value)} aria-label={t("Search my tasks")} />
        </label>
      </div>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}
      {tasks && tasks.length === 0 && (
        <EmptyState icon={<Coffee size={28} />} title={t("Nothing assigned to you")}>
          {t("Pick something up from a project's")} <Link to="/projects">{t("backlog")}</Link>.
        </EmptyState>
      )}

      <div ref={listRef}>
      {approvals.length > 0 && (
        <section className="group">
          <h2 className="group-title"><BadgeCheck size={16} /> {t("Waiting for my approval")} <span className="count">{approvals.length}</span></h2>
          <ul className="task-list">
            {approvals.map((a) => (
              <li key={a.id} className="task-row approval-row">
                <Link to={`/tasks/${a.task.id}#approvals`} className="task-key">{a.task.key}</Link>
                <Link to={`/tasks/${a.task.id}#approvals`} className="task-title">{a.task.title}</Link>
                <span className="muted small">{t('{name} asked {when}', { name: a.requestedBy.displayName, when: timeAgo(a.createdAt) })}
                  {a.request ? ` — “${a.request}”` : ''}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {overdue.length > 0 && (
        <section className="group">
          <h2 className="group-title overdue-text">{t("Overdue")} <span className="count danger">{overdue.length}</span></h2>
          <ul className="task-list">{sortTasks(overdue).map((t) => <TaskRow key={t.id} task={t} showProject />)}</ul>
        </section>
      )}
      {tasks && STATUSES.filter((s) => showDone || s !== 'DONE').map((status) => {
        const list = filtered.filter((t) => t.status === status && !overdue.includes(t));
        if (!list.length) return null;
        return (
          <section key={status} className="group">
            <h2 className="group-title">{t(STATUS_LABEL[status])} <span className="count muted-count">{list.length}</span></h2>
            <ul className="task-list">{sortTasks(list).map((t) => <TaskRow key={t.id} task={t} showProject />)}</ul>
          </section>
        );
      })}
      </div>
      {tasks && tasks.length > 0 && <p className="muted small keyboard-hint">{t('Tip: j/k move, Enter opens, e edits, a assigns, s changes the status.')}</p>}
      {picker}
    </div>
  );
}
