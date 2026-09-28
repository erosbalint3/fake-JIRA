import { useCallback, useEffect, useState, type DragEvent } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, KanbanSquare } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { PriorityBadge } from '../components/Badges';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { STATUSES, STATUS_LABEL, type Status, type Task } from '../types';

const PRIORITY_ORDER = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 } as const;

export function BoardPage() {
  const toast = useToast();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [error, setError] = useState('');
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<Status | null>(null);

  const load = useCallback(() => {
    setError('');
    api.tasks({ scope: 'MINE' }).then(setTasks).catch((e: ApiError) => setError(e.message));
  }, []);

  useEffect(load, [load]);

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
    const id = Number(event.dataTransfer.getData('text/plain'));
    const task = tasks?.find((t) => t.id === id);
    if (task) move(task, status);
  };

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <h1>My board</h1>
          <p className="muted">Drag cards between columns to update their status.</p>
        </div>
        <Link to="/backlog" className="btn btn-soft">Find more work</Link>
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}
      {tasks && tasks.length === 0 && (
        <EmptyState icon={<KanbanSquare size={28} />} title="Your board is empty">
          Accept a task from the <Link to="/backlog">backlog</Link> and it will show up here.
        </EmptyState>
      )}
      {tasks && tasks.length > 0 && (
        <div className="board">
          {STATUSES.map((status, index) => {
            const column = tasks
              .filter((t) => t.status === status)
              .sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority]);
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
                      <footer className="card-footer">
                        <PriorityBadge priority={task.priority} compact />
                        <span className="task-key">{task.key}</span>
                        <span className="spacer" />
                        <button
                          className="icon-button sm"
                          disabled={index === 0}
                          onClick={() => move(task, STATUSES[index - 1])}
                          aria-label={`Move ${task.key} to ${index > 0 ? STATUS_LABEL[STATUSES[index - 1]] : ''}`}
                        >
                          <ChevronLeft size={16} />
                        </button>
                        <button
                          className="icon-button sm"
                          disabled={index === STATUSES.length - 1}
                          onClick={() => move(task, STATUSES[index + 1])}
                          aria-label={`Move ${task.key} to ${index < STATUSES.length - 1 ? STATUS_LABEL[STATUSES[index + 1]] : ''}`}
                        >
                          <ChevronRight size={16} />
                        </button>
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
