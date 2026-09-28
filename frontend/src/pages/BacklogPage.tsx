import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Inbox, Plus, Search } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { PriorityBadge, StatusBadge } from '../components/Badges';
import { TaskFormModal } from '../components/TaskFormModal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { timeAgo } from '../format';
import {
  PRIORITIES, PRIORITY_LABEL, STATUSES, STATUS_LABEL, type Priority, type Scope, type Status, type Task,
} from '../types';

const TABS: { scope: Scope; label: string }[] = [
  { scope: 'AVAILABLE', label: 'Available' },
  { scope: 'REPORTED', label: 'Reported by me' },
  { scope: 'ALL', label: 'All tasks' },
];

export function BacklogPage() {
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const scope = (TABS.find((t) => t.scope === params.get('scope'))?.scope ?? 'AVAILABLE') as Scope;
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [priority, setPriority] = useState<Priority | ''>('');
  const [status, setStatus] = useState<Status | ''>('');
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [acceptingId, setAcceptingId] = useState<number | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(query), 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const load = useCallback(() => {
    setError('');
    api.tasks({ scope, q: debouncedQuery, priority, status })
      .then(setTasks)
      .catch((e: ApiError) => setError(e.message));
  }, [scope, debouncedQuery, priority, status]);

  useEffect(load, [load]);

  const accept = async (task: Task) => {
    setAcceptingId(task.id);
    try {
      await api.acceptTask(task.id);
      toast(`${task.key} added to your board`);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    } finally {
      setAcceptingId(null);
    }
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Backlog</h1>
          <p className="muted">Browse work that needs an owner, or everything your team is tracking.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>
          <Plus size={18} /> Create task
        </button>
      </header>

      <div className="tabs" role="tablist">
        {TABS.map((tab) => (
          <button
            key={tab.scope}
            role="tab"
            aria-selected={scope === tab.scope}
            className={`tab ${scope === tab.scope ? 'active' : ''}`}
            onClick={() => {
              setTasks(null);
              setParams(tab.scope === 'AVAILABLE' ? {} : { scope: tab.scope });
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <div className="toolbar">
        <label className="search">
          <Search size={16} />
          <input
            placeholder="Search tasks"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Search tasks"
          />
        </label>
        <select value={priority} onChange={(e) => setPriority(e.target.value as Priority | '')} aria-label="Priority">
          <option value="">Any priority</option>
          {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value as Status | '')} aria-label="Status">
          <option value="">Any status</option>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      </div>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}
      {tasks && tasks.length === 0 && (
        <EmptyState icon={<Inbox size={28} />} title={query || priority || status ? 'No matching tasks' : 'Nothing here yet'}>
          {scope === 'AVAILABLE' && !query && !priority && !status
            ? <>All caught up. <button className="link" onClick={() => setCreating(true)}>Create a task</button> to get things moving.</>
            : 'Try a different search or filter.'}
        </EmptyState>
      )}
      {tasks && tasks.length > 0 && (
        <ul className="task-list">
          {tasks.map((task) => (
            <li key={task.id} className="task-row">
              <Link to={`/tasks/${task.id}`} className="task-row-main">
                <PriorityBadge priority={task.priority} compact />
                <span className="task-key">{task.key}</span>
                <span className="task-title">{task.title}</span>
              </Link>
              <div className="task-row-meta">
                <StatusBadge status={task.status} />
                <span className="muted small hide-sm">{timeAgo(task.updatedAt)}</span>
                {task.assignee ? (
                  <Avatar name={task.assignee.username} />
                ) : (
                  <button
                    className="btn btn-soft btn-sm"
                    onClick={() => accept(task)}
                    disabled={acceptingId === task.id}
                  >
                    {acceptingId === task.id ? 'Accepting…' : 'Accept'}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {creating && user && (
        <TaskFormModal
          title="Create task"
          submitLabel="Create task"
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            const task = await api.createTask(input);
            setCreating(false);
            toast(`${task.key} created`);
            navigate(`/tasks/${task.id}`);
          }}
        />
      )}
    </div>
  );
}
