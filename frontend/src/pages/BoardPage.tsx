import { useCallback, useEffect, useState, type DragEvent } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, KanbanSquare, Zap } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { ChecklistProgress, DueBadge, Labels, PriorityBadge } from '../components/Badges';
import { useCreateTask } from '../components/Layout';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import { PRIORITY_ORDER, STATUSES, STATUS_LABEL, type Sprint, type Status, type Task } from '../types';

export function BoardPage() {
  const { key, project, loading } = useRouteProject();
  const { user } = useAuth();
  const toast = useToast();
  const openCreate = useCreateTask();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [active, setActive] = useState<Sprint | null>(null);
  const [error, setError] = useState('');
  const [onlyMine, setOnlyMine] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<Status | null>(null);

  const load = useCallback(async () => {
    if (!project) return;
    setError('');
    try {
      const sprints = await api.sprints(key);
      const current = sprints.find((s) => s.state === 'ACTIVE') ?? null;
      setActive(current);
      setTasks(await api.tasks({ project: key, sprint: current ? String(current.id) : undefined }));
    } catch (e) {
      setError((e as ApiError).message);
    }
  }, [key, project]);

  useEffect(() => {
    setTasks(null);
    load();
  }, [load]);

  useLiveRefresh(
    (m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id,
    load,
  );

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const move = async (task: Task, status: Status) => {
    if (task.status === status) return;
    const previous = tasks;
    setTasks((current) => current?.map((t) => (t.id === task.id ? { ...t, status } : t)) ?? null);
    try {
      await api.setStatus(task.id, status);
      if (status === 'DONE') toast(`${task.key} done — nice work!`);
    } catch (e) {
      setTasks(previous);
      toast((e as ApiError).message, 'error');
    }
  };

  const onDrop = (event: DragEvent, status: Status) => {
    event.preventDefault();
    setOver(null);
    // The card remounts in its new column, so its own dragend never reaches React.
    setDragging(null);
    const task = tasks?.find((t) => t.id === Number(event.dataTransfer.getData('text/plain')));
    if (task) move(task, status);
  };

  const shown = (tasks ?? []).filter((t) => !onlyMine || t.assignee?.id === user?.id);
  const daysLeft = active?.endDate
    ? Math.round((new Date(`${active.endDate}T00:00:00`).getTime() - new Date(`${todayIso()}T00:00:00`).getTime()) / 86400000)
    : null;

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Board</h1>
          {active ? (
            <p className="sprint-banner">
              <Zap size={15} /> <strong>{active.name}</strong>
              {active.startDate && active.endDate && <span className="muted">{formatDay(active.startDate)} – {formatDay(active.endDate)}</span>}
              {daysLeft !== null && (
                <span className={daysLeft < 0 ? 'overdue-text' : 'muted'}>
                  {daysLeft < 0 ? `${-daysLeft} day${daysLeft === -1 ? '' : 's'} over` : `${daysLeft} day${daysLeft === 1 ? '' : 's'} left`}
                </span>
              )}
              <Link to={`/p/${key}/reports`} className="small">Burndown</Link>
            </p>
          ) : (
            <p className="muted">No active sprint — showing every task. <Link to={`/p/${key}/backlog`}>Plan a sprint</Link></p>
          )}
        </div>
        <div className="header-actions">
          <label className="toggle">
            <input type="checkbox" checked={onlyMine} onChange={(e) => setOnlyMine(e.target.checked)} /> Only my tasks
          </label>
        </div>
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}
      {tasks && tasks.length === 0 && (
        <EmptyState icon={<KanbanSquare size={28} />} title={active ? 'This sprint has no tasks' : 'No tasks yet'}>
          {active
            ? <>Move tasks into {active.name} from the <Link to={`/p/${key}/backlog`}>backlog</Link>.</>
            : <button className="link" onClick={() => openCreate({ projectKey: key })}>Create the first task</button>}
        </EmptyState>
      )}
      {tasks && tasks.length > 0 && (
        <div className="board">
          {STATUSES.map((status, index) => {
            const column = shown
              .filter((t) => t.status === status)
              .sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || a.id - b.id);
            return (
              <section
                key={status}
                className={`column column-${status.toLowerCase()} ${over === status ? 'drop-target' : ''}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  setOver(status);
                }}
                onDragLeave={(e) => {
                  if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(null);
                }}
                onDrop={(e) => onDrop(e, status)}
              >
                <header className="column-header">
                  <span className="column-dot" />
                  <h2>{STATUS_LABEL[status]}</h2>
                  <span className="count">{column.length}</span>
                </header>
                <div className="column-body">
                  {column.map((task) => (
                    <article
                      key={task.id}
                      className={`card ${dragging === task.id ? 'dragging' : ''}`}
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.setData('text/plain', String(task.id));
                        e.dataTransfer.effectAllowed = 'move';
                        setDragging(task.id);
                      }}
                      onDragEnd={() => setDragging(null)}
                    >
                      <Link to={`/tasks/${task.id}`} className="card-title">{task.title}</Link>
                      <Labels labels={task.labels} max={3} />
                      <div className="card-meta">
                        {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
                        <ChecklistProgress done={task.checklistDone} total={task.checklistTotal} />
                      </div>
                      <footer className="card-footer">
                        <PriorityBadge priority={task.priority} compact />
                        <span className="task-key">{task.key}</span>
                        <span className="spacer" />
                        <button className="icon-button sm" disabled={index === 0}
                          onClick={() => move(task, STATUSES[index - 1])}
                          aria-label={`Move ${task.key} to ${index > 0 ? STATUS_LABEL[STATUSES[index - 1]] : ''}`}>
                          <ChevronLeft size={16} />
                        </button>
                        <button className="icon-button sm" disabled={index === STATUSES.length - 1}
                          onClick={() => move(task, STATUSES[index + 1])}
                          aria-label={`Move ${task.key} to ${index < STATUSES.length - 1 ? STATUS_LABEL[STATUSES[index + 1]] : ''}`}>
                          <ChevronRight size={16} />
                        </button>
                        {task.assignee
                          ? <Avatar name={task.assignee.username} size={24} />
                          : <span className="avatar-empty sm" title="Unassigned" />}
                      </footer>
                    </article>
                  ))}
                  {column.length === 0 && <div className="column-empty">Drop tasks here</div>}
                </div>
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
