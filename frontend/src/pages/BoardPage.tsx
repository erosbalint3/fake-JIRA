import { useCallback, useEffect, useMemo, useState, type DragEvent } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Eye, KanbanSquare, Zap } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import {
  BlockedBadge, ChecklistProgress, DueBadge, EpicChip, Labels, PointsBadge, PriorityBadge, SubtaskBadge, TypeIcon,
} from '../components/Badges';
import { useCreateTask } from '../components/Layout';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import { PRIORITY_ORDER, type BoardColumn, type Sprint, type Task } from '../types';

/** The column a task shows in: its pinned column, or the first column of its status. */
function columnOf(task: Task, columns: BoardColumn[]) {
  return columns.find((c) => c.id === task.columnId && c.status === task.status)
    ?? columns.find((c) => c.status === task.status);
}

export function BoardPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const { user } = useAuth();
  const toast = useToast();
  const openCreate = useCreateTask();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [columns, setColumns] = useState<BoardColumn[]>([]);
  const [active, setActive] = useState<Sprint | null>(null);
  const [error, setError] = useState('');
  const [onlyMine, setOnlyMine] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!project) return;
    setError('');
    try {
      const [sprints, cols] = await Promise.all([api.sprints(key), api.columns(key)]);
      const current = sprints.find((s) => s.state === 'ACTIVE') ?? null;
      setActive(current);
      setColumns(cols);
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

  const shown = useMemo(
    () => (tasks ?? []).filter((t) => !onlyMine || t.assignee?.id === user?.id),
    [tasks, onlyMine, user],
  );

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const move = async (task: Task, column: BoardColumn, isUndo = false) => {
    const from = columnOf(task, columns);
    if (from?.id === column.id) return;
    const inTarget = shown.filter((t) => columnOf(t, columns)?.id === column.id).length;
    const previous = tasks;
    setTasks((current) => current?.map((t) => (t.id === task.id ? { ...t, status: column.status, columnId: column.id } : t)) ?? null);
    try {
      await api.moveToColumn(task.id, column.id);
      const undo = !isUndo && from ? {
        action: { label: 'Undo', onClick: () => move({ ...task, status: column.status, columnId: column.id }, from, true) },
      } : {};
      if (column.wipLimit && inTarget + 1 > column.wipLimit) {
        toast(`${column.name} is over its limit of ${column.wipLimit}`, 'error', undo);
      } else if (column.status === 'DONE') {
        toast(`${task.key} done — nice work!`, 'success', undo);
      } else if (!isUndo) {
        toast(`${task.key} moved to ${column.name}`, 'success', undo);
      }
    } catch (e) {
      setTasks(previous);
      toast((e as ApiError).message, 'error');
    }
  };

  const onDrop = (event: DragEvent, column: BoardColumn) => {
    event.preventDefault();
    setOver(null);
    // The card remounts in its new column, so its own dragend never reaches React.
    setDragging(null);
    const task = tasks?.find((t) => t.id === Number(event.dataTransfer.getData('text/plain')));
    if (task) move(task, column);
  };

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
          {!canEdit && <span className="readonly-badge"><Eye size={13} /> Read-only</span>}
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
            : canEdit && <button className="link" onClick={() => openCreate({ projectKey: key })}>Create the first task</button>}
        </EmptyState>
      )}
      {tasks && tasks.length > 0 && (
        <div className="board" style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(230px, 1fr))` }}>
          {columns.map((column, index) => {
            const cards = shown
              .filter((t) => columnOf(t, columns)?.id === column.id)
              .sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || a.id - b.id);
            const overLimit = column.wipLimit !== null && cards.length > column.wipLimit;
            return (
              <section
                key={column.id}
                className={`column column-${column.status.toLowerCase()} ${over === column.id ? 'drop-target' : ''} ${overLimit ? 'over-limit' : ''}`}
                onDragOver={(e) => {
                  if (!canEdit) return;
                  e.preventDefault();
                  setOver(column.id);
                }}
                onDragLeave={(e) => {
                  if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(null);
                }}
                onDrop={(e) => canEdit && onDrop(e, column)}
              >
                <header className="column-header">
                  <span className="column-dot" />
                  <h2>{column.name}</h2>
                  <span className={`count ${overLimit ? 'danger' : ''}`}
                    title={column.wipLimit ? `Limit: ${column.wipLimit}` : undefined}>
                    {cards.length}{column.wipLimit ? ` / ${column.wipLimit}` : ''}
                  </span>
                </header>
                <div className="column-body">
                  {cards.map((task) => (
                    <article
                      key={task.id}
                      className={`card ${dragging === task.id ? 'dragging' : ''} ${task.blocked ? 'is-blocked' : ''}`}
                      draggable={canEdit}
                      onDragStart={(e) => {
                        e.dataTransfer.setData('text/plain', String(task.id));
                        e.dataTransfer.effectAllowed = 'move';
                        setDragging(task.id);
                      }}
                      onDragEnd={() => setDragging(null)}
                    >
                      {task.parent && <span className="card-parent muted small">↳ {task.parent.key}</span>}
                      <Link to={`/tasks/${task.id}`} className="card-title">{task.title}</Link>
                      <div className="card-tags">
                        <EpicChip epic={task.epic} />
                        <Labels labels={task.labels} max={2} />
                      </div>
                      <div className="card-meta">
                        <BlockedBadge blocked={task.blocked} />
                        {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
                        <ChecklistProgress done={task.checklistDone} total={task.checklistTotal} />
                        <SubtaskBadge done={task.subtaskDone} total={task.subtaskTotal} />
                      </div>
                      <footer className="card-footer">
                        <TypeIcon type={task.type} />
                        <PriorityBadge priority={task.priority} compact />
                        <span className="task-key">{task.key}</span>
                        <PointsBadge points={task.storyPoints} />
                        <span className="spacer" />
                        {canEdit && (
                          <>
                            <button className="icon-button sm" disabled={index === 0}
                              onClick={() => move(task, columns[index - 1])}
                              aria-label={`Move ${task.key} to ${index > 0 ? columns[index - 1].name : ''}`}>
                              <ChevronLeft size={16} />
                            </button>
                            <button className="icon-button sm" disabled={index === columns.length - 1}
                              onClick={() => move(task, columns[index + 1])}
                              aria-label={`Move ${task.key} to ${index < columns.length - 1 ? columns[index + 1].name : ''}`}>
                              <ChevronRight size={16} />
                            </button>
                          </>
                        )}
                        {task.assignee
                          ? <Avatar user={task.assignee} size={24} />
                          : <span className="avatar-empty sm" title="Unassigned" />}
                      </footer>
                    </article>
                  ))}
                  {cards.length === 0 && <div className="column-empty">{canEdit ? 'Drop tasks here' : 'Empty'}</div>}
                </div>
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
