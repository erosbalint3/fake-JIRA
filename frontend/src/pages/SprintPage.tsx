import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, ClipboardCopy, ListPlus, MessageSquareHeart, Pencil, Presentation, ThumbsUp, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { PointsBadge, StatusBadge, TypeIcon } from '../components/Badges';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay } from '../format';
import type { RetroItem, RetroKind, ReviewTask, SprintReview } from '../types';

const COLUMNS: { kind: RetroKind; title: string; hint: string }[] = [
  { kind: 'WENT_WELL', title: 'Went well', hint: 'What should we keep doing?' },
  { kind: 'TO_IMPROVE', title: 'To improve', hint: 'What slowed us down?' },
  { kind: 'ACTION', title: 'Action items', hint: 'What will we change next sprint?' },
];

export function SprintPage() {
  const { key, project, loading } = useRouteProject();
  const { id } = useParams();
  const sprintId = Number(id);
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'retro' ? 'retro' : 'review';
  const [review, setReview] = useState<SprintReview | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.sprintReview(sprintId).then(setReview).catch((e: ApiError) => setError(e.message));
  }, [sprintId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id, load, 800);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;
  if (error) return <div className="page"><ErrorBanner message={error} onRetry={load} /></div>;
  if (!review) return <div className="page"><Spinner /></div>;
  const sprint = review.sprint;

  return (
    <div className="page page-wide">
      <Link to={`/p/${key}/backlog`} className="back"><ArrowLeft size={16} /> Backlog</Link>
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{sprint.name}</h1>
          <p className="muted">
            {sprint.state === 'ACTIVE' ? 'Active' : sprint.state === 'COMPLETED' ? 'Completed' : 'Planned'}
            {sprint.startDate && sprint.endDate && ` · ${formatDay(sprint.startDate)} – ${formatDay(sprint.endDate)}`}
            {sprint.goal && <> · Goal: {sprint.goal}</>}
          </p>
        </div>
      </header>
      <nav className="tabs" role="tablist" aria-label="Sprint">
        <button role="tab" aria-selected={tab === 'review'} className={`tab ${tab === 'review' ? 'active' : ''}`}
          onClick={() => setParams({}, { replace: true })}><Presentation size={15} /> Review</button>
        <button role="tab" aria-selected={tab === 'retro'} className={`tab ${tab === 'retro' ? 'active' : ''}`}
          onClick={() => setParams({ tab: 'retro' }, { replace: true })}><MessageSquareHeart size={15} /> Retrospective</button>
      </nav>
      {tab === 'review' ? <ReviewView review={review} /> : <RetroBoard sprintId={sprintId} projectId={project.id}
        open={sprint.state !== 'PLANNED'} />}
    </div>
  );
}

function ReviewView({ review }: { review: SprintReview }) {
  const toast = useToast();
  const percent = review.committedPoints ? Math.round((review.completedPoints / review.committedPoints) * 100) : null;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(review.markdown);
      toast('Review copied as Markdown');
    } catch {
      toast('Could not copy — your browser blocked the clipboard', 'error');
    }
  };
  return (
    <>
      <div className="stats">
        <div className="stat panel"><span className="muted">Completed</span><strong>{review.completedTasks}</strong>
          <span className="muted small">tasks</span></div>
        <div className="stat panel"><span className="muted">Points</span><strong>{review.completedPoints}</strong>
          <span className="muted small">of {review.committedPoints} committed{percent !== null ? ` (${percent}%)` : ''}</span></div>
        <div className="stat panel"><span className="muted">Scope change</span>
          <strong>+{review.added.length} / −{review.removed.length}</strong>
          <span className="muted small">tasks added / removed after the start</span></div>
      </div>
      <div className="review-actions">
        <button className="btn btn-ghost btn-sm" onClick={copy}><ClipboardCopy size={15} /> Copy as Markdown</button>
      </div>
      <div className="report-grid">
        <ReviewList title="Completed" tasks={review.completed} empty="Nothing was finished." />
        <ReviewList title="Not finished" tasks={review.unfinished} empty="Everything was finished." />
        {review.added.length > 0 && <ReviewList title="Added during the sprint" tasks={review.added} />}
        {review.removed.length > 0 && <ReviewList title="Removed during the sprint" tasks={review.removed} />}
        {review.people.length > 0 && (
          <section className="panel">
            <h2 className="panel-title">Who finished what</h2>
            <table className="viz-table">
              <thead><tr><th>Person</th><th className="num">Tasks</th><th className="num">Points</th></tr></thead>
              <tbody>
                {review.people.map((p) => (
                  <tr key={p.user.id}>
                    <td><span className="person"><Avatar user={p.user} size={22} /> {p.user.displayName}</span></td>
                    <td className="num">{p.tasks}</td>
                    <td className="num">{p.points}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </div>
    </>
  );
}

function ReviewList({ title, tasks, empty }: { title: string; tasks: ReviewTask[]; empty?: string }) {
  return (
    <section className="panel">
      <h2 className="panel-title">{title} <span className="count">{tasks.length}</span></h2>
      {tasks.length === 0 ? <p className="muted">{empty}</p> : (
        <ul className="review-list">
          {tasks.map((t) => (
            <li key={`${title}-${t.id}`}>
              {t.type && <TypeIcon type={t.type} />}
              <Link to={`/tasks/${t.id}`}><span className="task-key">{t.key}</span> {t.title}</Link>
              <span className="spacer" />
              {t.status && <StatusBadge status={t.status} />}
              <PointsBadge points={t.points} />
              {t.assignee && <Avatar user={t.assignee} size={20} />}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function RetroBoard({ sprintId, projectId, open }: { sprintId: number; projectId: number; open: boolean }) {
  const { canEdit } = useRouteProject();
  const toast = useToast();
  const [items, setItems] = useState<RetroItem[] | null>(null);
  const [error, setError] = useState('');
  const [drafts, setDrafts] = useState<Record<RetroKind, string>>({ WENT_WELL: '', TO_IMPROVE: '', ACTION: '' });
  const [editing, setEditing] = useState<{ id: number; text: string } | null>(null);

  const load = useCallback(() => {
    api.retro(sprintId).then(setItems).catch((e: ApiError) => setError(e.message));
  }, [sprintId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === projectId, load, 400);

  const run = async (action: () => Promise<unknown>, message?: string) => {
    try {
      await action();
      if (message) toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const add = (kind: RetroKind) => (event: FormEvent) => {
    event.preventDefault();
    const text = drafts[kind].trim();
    if (!text) return;
    setDrafts((d) => ({ ...d, [kind]: '' }));
    run(() => api.addRetroItem(sprintId, kind, text));
  };

  if (error) return <ErrorBanner message={error} onRetry={load} />;
  if (!items) return <Spinner />;
  if (!open) {
    return (
      <EmptyState icon={<MessageSquareHeart size={28} />} title="The retrospective opens when the sprint starts">
        Come back once the sprint is running.
      </EmptyState>
    );
  }

  return (
    <div className="retro-board">
      {COLUMNS.map((column) => {
        const cards = items.filter((i) => i.kind === column.kind);
        return (
          <section key={column.kind} className={`retro-column retro-${column.kind.toLowerCase()}`} aria-label={column.title}>
            <header>
              <h2>{column.title}</h2>
              <span className="count">{cards.length}</span>
            </header>
            <p className="muted small">{column.hint}</p>
            {canEdit && (
              <form onSubmit={add(column.kind)} className="retro-add">
                <textarea rows={2} maxLength={500} placeholder="Add a card…" value={drafts[column.kind]}
                  aria-label={`Add to ${column.title}`}
                  onChange={(e) => setDrafts((d) => ({ ...d, [column.kind]: e.target.value }))}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      add(column.kind)(e);
                    }
                  }} />
                <button className="btn btn-soft btn-sm" disabled={!drafts[column.kind].trim()}>Add</button>
              </form>
            )}
            <ul className="retro-cards">
              {cards.map((item) => (
                <li key={item.id} className="retro-card">
                  {editing?.id === item.id ? (
                    <form onSubmit={(e) => {
                      e.preventDefault();
                      const text = editing.text.trim();
                      setEditing(null);
                      if (text && text !== item.text) run(() => api.editRetroItem(item.id, item.kind, text));
                    }}>
                      <textarea rows={3} autoFocus maxLength={500} value={editing.text} aria-label="Edit card"
                        onChange={(e) => setEditing({ id: item.id, text: e.target.value })} />
                      <div className="retro-card-actions">
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(null)}>Cancel</button>
                        <button className="btn btn-primary btn-sm">Save</button>
                      </div>
                    </form>
                  ) : (
                    <>
                      <p>{item.text}</p>
                      <footer>
                        <span className="muted small person"><Avatar user={item.author} size={18} /> {item.author.displayName}</span>
                        <span className="spacer" />
                        {item.taskKey && item.taskId && <Link className="small" to={`/tasks/${item.taskId}`}>{item.taskKey}</Link>}
                        {canEdit && item.kind === 'ACTION' && !item.taskId && (
                          <button className="icon-button sm" title="Create a backlog task" aria-label="Create a backlog task"
                            onClick={() => run(() => api.retroToTask(item.id), 'Task created in the backlog')}>
                            <ListPlus size={15} />
                          </button>
                        )}
                        {item.mine && (
                          <>
                            <button className="icon-button sm" aria-label="Edit card" onClick={() => setEditing({ id: item.id, text: item.text })}>
                              <Pencil size={14} />
                            </button>
                            <button className="icon-button sm" aria-label="Delete card" onClick={() => run(() => api.deleteRetroItem(item.id))}>
                              <Trash2 size={14} />
                            </button>
                          </>
                        )}
                        <button className={`vote ${item.voted ? 'voted' : ''}`} disabled={!canEdit} aria-pressed={item.voted}
                          aria-label={`${item.voted ? 'Remove vote' : 'Vote'} (${item.votes})`}
                          onClick={() => run(() => api.voteRetroItem(item.id))}>
                          <ThumbsUp size={14} /> {item.votes}
                        </button>
                      </footer>
                    </>
                  )}
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
