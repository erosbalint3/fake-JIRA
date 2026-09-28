import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FileQuestion, MessageSquare, Pencil, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { PriorityBadge, StatusBadge } from '../components/Badges';
import { ConfirmDialog } from '../components/Modal';
import { TaskFormModal } from '../components/TaskFormModal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { formatDate, timeAgo } from '../format';
import { STATUSES, STATUS_LABEL, type Comment, type Status, type Task } from '../types';

export function TaskDetailPage() {
  const { id } = useParams();
  const taskId = Number(id);
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [task, setTask] = useState<Task | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [comment, setComment] = useState('');

  const load = useCallback(() => {
    setError(null);
    Promise.all([api.task(taskId), api.comments(taskId)])
      .then(([t, c]) => {
        setTask(t);
        setComments(c);
      })
      .catch((e: ApiError) => setError(e));
  }, [taskId]);

  useEffect(load, [load]);

  if (error?.status === 404 || Number.isNaN(taskId)) {
    return (
      <div className="page">
        <EmptyState icon={<FileQuestion size={28} />} title="Task not found">
          It may have been deleted. <Link to="/backlog">Back to backlog</Link>
        </EmptyState>
      </div>
    );
  }
  if (error) return <div className="page"><ErrorBanner message={error.message} onRetry={load} /></div>;
  if (!task || !user) return <div className="page"><Spinner /></div>;

  const isReporter = task.reporter.id === user.id;
  const isAssignee = task.assignee?.id === user.id;
  const canEdit = isReporter || isAssignee;

  const run = async (action: () => Promise<Task>, message: string) => {
    setBusy(true);
    try {
      setTask(await action());
      toast(message);
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteTask(task.id);
      toast(`${task.key} deleted`);
      navigate('/backlog', { replace: true });
    } catch (e) {
      toast((e as ApiError).message, 'error');
      setBusy(false);
      setConfirmDelete(false);
    }
  };

  const postComment = async (event: FormEvent) => {
    event.preventDefault();
    if (!comment.trim()) return;
    setBusy(true);
    try {
      const created = await api.addComment(task.id, comment.trim());
      setComments([...comments, created]);
      setComment('');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <button className="back" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/backlog'))}>
        <ArrowLeft size={16} /> Back
      </button>

      <div className="detail">
        <article className="detail-main">
          <div className="detail-key">
            <span className="task-key">{task.key}</span>
            <StatusBadge status={task.status} />
          </div>
          <h1 className="detail-title">{task.title}</h1>

          <div className="detail-actions">
            {!task.assignee && (
              <button className="btn btn-primary" disabled={busy}
                onClick={() => run(() => api.acceptTask(task.id), `${task.key} added to your board`)}>
                Accept task
              </button>
            )}
            {isAssignee && (
              <button className="btn btn-soft" disabled={busy}
                onClick={() => run(() => api.releaseTask(task.id), `${task.key} returned to the backlog`)}>
                Release
              </button>
            )}
            {canEdit && (
              <button className="btn btn-ghost" onClick={() => setEditing(true)}>
                <Pencil size={16} /> Edit
              </button>
            )}
            {isReporter && (
              <button className="btn btn-ghost danger" onClick={() => setConfirmDelete(true)}>
                <Trash2 size={16} /> Delete
              </button>
            )}
          </div>

          <section className="panel">
            <h2 className="panel-title">Description</h2>
            {task.description
              ? <p className="description">{task.description}</p>
              : <p className="muted">No description provided.</p>}
          </section>

          <section className="panel">
            <h2 className="panel-title"><MessageSquare size={16} /> Comments <span className="count">{comments.length}</span></h2>
            <ul className="comments">
              {comments.map((c) => (
                <li key={c.id} className="comment">
                  <Avatar name={c.author.username} size={32} />
                  <div>
                    <div className="comment-head">
                      <strong>{c.author.username}</strong>
                      <span className="muted small">{timeAgo(c.createdAt)}</span>
                    </div>
                    <p>{c.body}</p>
                  </div>
                </li>
              ))}
            </ul>
            <form className="comment-form" onSubmit={postComment}>
              <Avatar name={user.username} size={32} />
              <div className="comment-input">
                <textarea
                  rows={2}
                  placeholder="Add a comment…"
                  value={comment}
                  maxLength={2000}
                  onChange={(e) => setComment(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) postComment(e);
                  }}
                  aria-label="Comment"
                />
                <button className="btn btn-primary btn-sm" disabled={busy || !comment.trim()}>Comment</button>
              </div>
            </form>
          </section>
        </article>

        <aside className="detail-side panel">
          <dl className="props">
            <dt>Status</dt>
            <dd>
              {canEdit ? (
                <select
                  value={task.status}
                  disabled={busy}
                  onChange={(e) => run(() => api.setStatus(task.id, e.target.value as Status),
                    `Moved to ${STATUS_LABEL[e.target.value as Status]}`)}
                  aria-label="Status"
                >
                  {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
                </select>
              ) : <StatusBadge status={task.status} />}
            </dd>
            <dt>Priority</dt>
            <dd><PriorityBadge priority={task.priority} /></dd>
            <dt>Assignee</dt>
            <dd>
              {task.assignee
                ? <span className="person"><Avatar name={task.assignee.username} size={24} /> {task.assignee.username}</span>
                : <span className="muted">Unassigned</span>}
            </dd>
            <dt>Reporter</dt>
            <dd><span className="person"><Avatar name={task.reporter.username} size={24} /> {task.reporter.username}</span></dd>
            <dt>Created</dt>
            <dd>{formatDate(task.createdAt)}</dd>
            <dt>Updated</dt>
            <dd>{timeAgo(task.updatedAt)}</dd>
          </dl>
        </aside>
      </div>

      {editing && (
        <TaskFormModal
          title={`Edit ${task.key}`}
          submitLabel="Save changes"
          initial={{ title: task.title, description: task.description, priority: task.priority }}
          onClose={() => setEditing(false)}
          onSubmit={async (input) => {
            setTask(await api.updateTask(task.id, input));
            setEditing(false);
            toast('Changes saved');
          }}
        />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={`Delete ${task.key}?`}
          message="This permanently removes the task and its comments. This cannot be undone."
          confirmLabel="Delete task"
          danger
          busy={busy}
          onConfirm={remove}
          onClose={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}
