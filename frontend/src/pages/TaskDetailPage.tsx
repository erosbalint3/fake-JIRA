import { useCallback, useEffect, useRef, useState, type DragEvent, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, CheckSquare, CornerLeftUp, Download, Eye, EyeOff, FileQuestion, FileText, History, MessageSquare, Paperclip,
  Pencil, Trash2, Upload, X,
} from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { BlockedBadge, DueBadge, EpicChip, Labels, PriorityBadge, StatusBadge } from '../components/Badges';
import { useCreateTask } from '../components/Layout';
import { DevPanel } from '../components/task/DevPanel';
import { CommentItem } from '../components/task/CommentItem';
import { LinksPanel } from '../components/task/LinksPanel';
import { SubtasksPanel } from '../components/task/SubtasksPanel';
import { TimePanel } from '../components/task/TimePanel';
import { useProjectAccess } from '../useProject';
import { LabelInput } from '../components/LabelInput';
import { Markdown } from '../components/Markdown';
import { MarkdownEditor } from '../components/MarkdownEditor';
import { ConfirmDialog, Modal } from '../components/Modal';
import { TaskFormModal } from '../components/TaskFormModal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { fileSize, formatDate, formatMinutes, timeAgo } from '../format';
import {
  PRIORITIES, PRIORITY_LABEL, STATUSES, STATUS_LABEL, type Activity, type Attachment, type ChecklistItem, type Comment,
  type DevLink, type Epic, type Priority, type Sprint, type Status, type Task, type TaskInput, type TaskLink, type TimeEntry,
  type User,
} from '../types';

const MAX_UPLOAD = 10 * 1024 * 1024;

function inputOf(task: Task): TaskInput {
  return {
    title: task.title, description: task.description, priority: task.priority, dueDate: task.dueDate, labels: task.labels,
    storyPoints: task.storyPoints, epicId: task.epic?.id ?? null,
  };
}

export function TaskDetailPage() {
  const { id } = useParams();
  const taskId = Number(id);
  const { user } = useAuth();
  const { byKey } = useProjects();
  const toast = useToast();
  const navigate = useNavigate();
  const [task, setTask] = useState<Task | null>(null);
  const [comments, setComments] = useState<Comment[]>([]);
  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [activity, setActivity] = useState<Activity[]>([]);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [deleted, setDeleted] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editingLabels, setEditingLabels] = useState<string[] | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [comment, setComment] = useState('');
  const [newItem, setNewItem] = useState('');
  const [tab, setTab] = useState<'comments' | 'activity'>('comments');
  const [subtasks, setSubtasks] = useState<Task[]>([]);
  const [links, setLinks] = useState<TaskLink[]>([]);
  const [timeEntries, setTimeEntries] = useState<TimeEntry[]>([]);
  const [devLinks, setDevLinks] = useState<DevLink[]>([]);
  const [epics, setEpics] = useState<Epic[]>([]);
  const [watching, setWatching] = useState(false);
  const [watchers, setWatchers] = useState<User[]>([]);
  const openCreate = useCreateTask();

  const load = useCallback(() => {
    setError(null);
    Promise.all([
      api.task(taskId), api.comments(taskId), api.checklist(taskId), api.attachments(taskId), api.activity(taskId),
      api.subtasks(taskId), api.links(taskId), api.time(taskId), api.devLinks(taskId), api.watchers(taskId),
    ])
      .then(([t, c, items, files, history, subs, taskLinks, time, dev, watch]) => {
        setTask(t);
        setComments(c);
        setChecklist(items);
        setAttachments(files);
        setActivity(history);
        setSubtasks(subs);
        setLinks(taskLinks);
        setTimeEntries(time);
        setDevLinks(dev);
        setWatching(watch.watching);
        setWatchers(watch.watchers);
      })
      .catch((e: ApiError) => setError(e));
  }, [taskId]);

  useEffect(load, [load]);

  useEffect(() => {
    if (!task) return;
    api.sprints(task.projectKey).then((list) => setSprints(list.filter((s) => s.state !== 'COMPLETED'))).catch(() => {});
    api.epics(task.projectKey).then(setEpics).catch(() => {});
  }, [task?.projectKey]);

  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, () => {
    // A live "deleted" event arrives after someone else removed the task.
    api.task(taskId).then(() => load()).catch((e: ApiError) => e.status === 404 ? setDeleted(true) : undefined);
  });

  const project = task ? byKey(task.projectKey) : undefined;
  const { canEdit, isOwner } = useProjectAccess(project);

  if (deleted || error?.status === 404 || Number.isNaN(taskId)) {
    return (
      <div className="page">
        <EmptyState icon={<FileQuestion size={28} />} title={deleted ? 'This task was deleted' : 'Task not found'}>
          It may have been deleted, or you are not a member of its project. <Link to="/my-work">Go to my work</Link>
        </EmptyState>
      </div>
    );
  }
  if (error) return <div className="page"><ErrorBanner message={error.message} onRetry={load} /></div>;
  if (!task || !user) return <div className="page"><Spinner /></div>;

  const members = project?.members ?? [];
  const assignable = members.filter((m) => m.role !== 'VIEWER');
  const isAssignee = task.assignee?.id === user.id;
  const canDelete = canEdit && (task.reporter.id === user.id || isOwner);

  const run = async (action: () => Promise<Task>, message?: string) => {
    setBusy(true);
    try {
      setTask(await action());
      if (message) toast(message);
      api.activity(taskId).then(setActivity).catch(() => {});
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    } finally {
      setBusy(false);
    }
  };

  const update = (change: Partial<TaskInput>, message?: string) =>
    run(() => api.updateTask(task.id, { ...inputOf(task), ...change }), message);

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteTask(task.id);
      toast(`${task.key} deleted`);
      navigate(`/p/${task.projectKey}/backlog`, { replace: true });
    } catch (e) {
      toast((e as ApiError).message, 'error');
      setBusy(false);
      setConfirmDelete(false);
    }
  };

  const postComment = async (event?: FormEvent) => {
    event?.preventDefault();
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

  const addItem = async (event: FormEvent) => {
    event.preventDefault();
    if (!newItem.trim()) return;
    try {
      const item = await api.addChecklistItem(task.id, newItem.trim());
      setChecklist([...checklist, item]);
      setNewItem('');
      setTask({ ...task, checklistTotal: task.checklistTotal + 1 });
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const toggleItem = async (item: ChecklistItem) => {
    setChecklist(checklist.map((i) => (i.id === item.id ? { ...i, done: !i.done } : i)));
    try {
      await api.updateChecklistItem(task.id, item.id, { done: !item.done });
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    }
  };

  const removeItem = async (item: ChecklistItem) => {
    setChecklist(checklist.filter((i) => i.id !== item.id));
    try {
      await api.deleteChecklistItem(task.id, item.id);
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    }
  };

  const upload = async (files: FileList | File[]) => {
    for (const file of Array.from(files)) {
      if (file.size > MAX_UPLOAD) {
        toast(`${file.name} is larger than 10 MB`, 'error');
        continue;
      }
      try {
        const created = await api.uploadAttachment(task.id, file);
        setAttachments((current) => [...current, created]);
        toast(`${created.filename} attached`);
      } catch (e) {
        toast((e as ApiError).message, 'error');
      }
    }
    api.activity(taskId).then(setActivity).catch(() => {});
  };

  const doneCount = checklist.filter((i) => i.done).length;

  return (
    <div className="page page-detail">
      <div className="detail-nav">
        <button className="back" onClick={() => (window.history.length > 1 ? navigate(-1) : navigate('/my-work'))}>
          <ArrowLeft size={16} /> Back
        </button>
        <span className="breadcrumb muted">
          <Link to={`/p/${task.projectKey}/board`}>{task.projectName}</Link>
          {task.parent && <> / <Link to={`/tasks/${task.parent.id}`}>{task.parent.key}</Link></>} / {task.key}
        </span>
        {!canEdit && <span className="readonly-badge"><Eye size={13} /> Read-only</span>}
      </div>

      <div className="detail">
        <article className="detail-main">
          <div className="detail-key">
            <span className="task-key">{task.key}</span>
            <StatusBadge status={task.status} />
            <BlockedBadge blocked={task.blocked} />
            {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
          </div>
          {task.parent && (
            <Link to={`/tasks/${task.parent.id}`} className="parent-link">
              <CornerLeftUp size={14} /> Subtask of {task.parent.key} · {task.parent.title}
            </Link>
          )}
          <h1 className="detail-title">{task.title}</h1>
          <div className="detail-tags">
            <EpicChip epic={task.epic} />
            <Labels labels={task.labels} />
          </div>

          <div className="detail-actions">
            {canEdit && !task.assignee && (
              <button className="btn btn-primary" disabled={busy}
                onClick={() => run(() => api.acceptTask(task.id), `${task.key} assigned to you`)}>
                Accept task
              </button>
            )}
            {canEdit && isAssignee && (
              <button className="btn btn-soft" disabled={busy}
                onClick={() => run(() => api.releaseTask(task.id), `${task.key} released`)}>
                Release
              </button>
            )}
            {canEdit && (
              <button className="btn btn-ghost" onClick={() => setEditing(true)}>
                <Pencil size={16} /> Edit
              </button>
            )}
            <button className="btn btn-ghost" aria-pressed={watching}
              title={watchers.length ? `Watching: ${watchers.map((w) => w.displayName).join(', ')}` : 'Nobody else is watching'}
              onClick={async () => {
                const result = await api.watch(task.id, !watching);
                setWatching(result.watching);
                setWatchers(result.watchers);
                toast(result.watching ? 'You will be notified about changes' : 'Stopped watching');
              }}>
              {watching ? <EyeOff size={16} /> : <Eye size={16} />} {watching ? 'Unwatch' : 'Watch'}
              {watchers.length > 0 && <span className="count muted-count">{watchers.length}</span>}
            </button>
            {canDelete && (
              <button className="btn btn-ghost danger" onClick={() => setConfirmDelete(true)}>
                <Trash2 size={16} /> Delete
              </button>
            )}
          </div>

          <section className="panel">
            <h2 className="panel-title"><FileText size={16} /> Description</h2>
            {task.description
              ? <Markdown>{task.description}</Markdown>
              : <p className="muted">No description yet.{canEdit && <> <button className="link" onClick={() => setEditing(true)}>Add one</button></>}</p>}
          </section>

          {!task.parent && (
            <SubtasksPanel subtasks={subtasks} canEdit={canEdit}
              onAdd={() => openCreate({ projectKey: task.projectKey, parent: { id: task.id, key: task.key } })} />
          )}

          <section className="panel">
            <h2 className="panel-title">
              <CheckSquare size={16} /> Checklist
              {checklist.length > 0 && <span className="muted small">{doneCount}/{checklist.length}</span>}
            </h2>
            {checklist.length > 0 && (
              <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={checklist.length} aria-valuenow={doneCount}
                aria-label="Checklist progress">
                <span style={{ width: `${(doneCount / checklist.length) * 100}%` }} />
              </div>
            )}
            <ul className="checklist">
              {checklist.map((item) => (
                <li key={item.id} className={item.done ? 'done' : ''}>
                  <label>
                    <input type="checkbox" checked={item.done} disabled={!canEdit} onChange={() => toggleItem(item)} />
                    <span>{item.text}</span>
                  </label>
                  {canEdit && (
                    <button className="icon-button sm" onClick={() => removeItem(item)} aria-label={`Delete ${item.text}`}>
                      <X size={14} />
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {canEdit && (
              <form className="inline-add" onSubmit={addItem}>
                <input value={newItem} maxLength={200} placeholder="Add an item…" onChange={(e) => setNewItem(e.target.value)}
                  aria-label="New checklist item" />
                <button className="btn btn-soft btn-sm" disabled={!newItem.trim()}>Add</button>
              </form>
            )}
            {!canEdit && checklist.length === 0 && <p className="muted">No checklist.</p>}
          </section>

          <LinksPanel taskId={task.id} projectKey={task.projectKey} links={links} canEdit={canEdit} onChange={load} />

          <Attachments attachments={attachments} userId={user.id} ownerId={project?.owner.id} canEdit={canEdit}
            onUpload={upload}
            onDelete={async (attachment) => {
              try {
                await api.deleteAttachment(attachment.id);
                setAttachments(attachments.filter((a) => a.id !== attachment.id));
              } catch (e) {
                toast((e as ApiError).message, 'error');
              }
            }} />

          <TimePanel taskId={task.id} entries={timeEntries} userId={user.id} isOwner={isOwner} canEdit={canEdit} onChange={load} />
          <DevPanel links={devLinks} />

          <section className="panel">
            <div className="tabs compact" role="tablist">
              <button role="tab" aria-selected={tab === 'comments'} className={`tab ${tab === 'comments' ? 'active' : ''}`}
                onClick={() => setTab('comments')}>
                <MessageSquare size={15} /> Comments <span className="count">{comments.length}</span>
              </button>
              <button role="tab" aria-selected={tab === 'activity'} className={`tab ${tab === 'activity' ? 'active' : ''}`}
                onClick={() => setTab('activity')}>
                <History size={15} /> Activity
              </button>
            </div>

            {tab === 'comments' ? (
              <>
                <ul className="comments">
                  {comments.map((c) => (
                    <CommentItem key={c.id} taskId={task.id} comment={c} me={user} canEdit={canEdit} isOwner={isOwner}
                      members={members}
                      onChanged={(updated) => setComments((list) => list.map((x) => (x.id === updated.id ? updated : x)))}
                      onDeleted={(id) => setComments((list) => list.filter((x) => x.id !== id))} />
                  ))}
                </ul>
                {canEdit && <form className="comment-form" onSubmit={postComment}>
                  <Avatar user={user} size={32} />
                  <div className="comment-input">
                    <MarkdownEditor value={comment} onChange={setComment} members={members} rows={3} maxLength={2000}
                      label="Comment" placeholder="Add a comment… Type @ to mention someone. Ctrl+Enter to send."
                      onSubmitShortcut={() => postComment()} />
                    <button className="btn btn-primary btn-sm" disabled={busy || !comment.trim()}>Comment</button>
                  </div>
                </form>}
              </>
            ) : (
              <ul className="activity">
                {activity.map((a) => (
                  <li key={a.id}>
                    <Avatar user={a.actor} size={24} />
                    <span><strong>{a.actor.displayName}</strong> {a.message}</span>
                    <span className="muted small" title={new Date(a.createdAt).toLocaleString()}>{timeAgo(a.createdAt)}</span>
                  </li>
                ))}
                {activity.length === 0 && <li className="muted">No activity yet.</li>}
              </ul>
            )}
          </section>
        </article>

        <aside className="detail-side panel">
          <dl className="props">
            <dt>Status</dt>
            <dd>
              <select value={task.status} disabled={busy || !canEdit} aria-label="Status"
                onChange={(e) => run(() => api.setStatus(task.id, e.target.value as Status),
                  `Moved to ${STATUS_LABEL[e.target.value as Status]}`)}>
                {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
              </select>
            </dd>
            <dt>Assignee</dt>
            <dd>
              <select value={task.assignee?.id ?? ''} disabled={busy || !canEdit} aria-label="Assignee"
                onChange={(e) => run(() => api.assign(task.id, e.target.value ? Number(e.target.value) : null),
                  e.target.value ? 'Assignee updated' : 'Unassigned')}>
                <option value="">Unassigned</option>
                {assignable.map((m) => <option key={m.id} value={m.id}>{m.displayName}{m.id === user.id ? ' (me)' : ''}</option>)}
              </select>
            </dd>
            <dt>Priority</dt>
            <dd>
              <div className="with-icon">
                <PriorityBadge priority={task.priority} compact />
                <select value={task.priority} disabled={busy || !canEdit} aria-label="Priority"
                  onChange={(e) => update({ priority: e.target.value as Priority }, 'Priority updated')}>
                  {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
                </select>
              </div>
            </dd>
            <dt>Sprint</dt>
            <dd>
              <select value={task.sprint?.id ?? ''} disabled={busy || !canEdit} aria-label="Sprint"
                onChange={(e) => run(() => api.moveToSprint(task.id, e.target.value ? Number(e.target.value) : null),
                  e.target.value ? 'Moved to sprint' : 'Moved to backlog')}>
                <option value="">Backlog</option>
                {task.sprint?.state === 'COMPLETED' && <option value={task.sprint.id}>{task.sprint.name} (completed)</option>}
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
              </select>
            </dd>
            <dt>Due date</dt>
            <dd>
              <input type="date" value={task.dueDate ?? ''} disabled={busy || !canEdit} aria-label="Due date"
                onChange={(e) => update({ dueDate: e.target.value || null }, e.target.value ? 'Due date set' : 'Due date removed')} />
            </dd>
            <dt>Points</dt>
            <dd>
              <input type="number" min={0} max={100} key={`sp-${task.storyPoints}`} defaultValue={task.storyPoints ?? ''}
                disabled={busy || !canEdit} aria-label="Story points" placeholder="–"
                onBlur={(e) => {
                  const value = e.target.value === '' ? null : Math.max(0, Math.min(100, Number(e.target.value)));
                  if (value !== task.storyPoints) update({ storyPoints: value }, 'Estimate updated');
                }} />
            </dd>
            <dt>Epic</dt>
            <dd>
              <select value={task.epic?.id ?? ''} disabled={busy || !canEdit} aria-label="Epic"
                onChange={(e) => update({ epicId: e.target.value ? Number(e.target.value) : null }, 'Epic updated')}>
                <option value="">No epic</option>
                {epics.map((epic) => <option key={epic.id} value={epic.id}>{epic.name}</option>)}
              </select>
            </dd>
            <dt>Labels</dt>
            <dd>
              <button className="labels-button" disabled={!canEdit} onClick={() => setEditingLabels(task.labels)} aria-label="Edit labels">
                {task.labels.length ? <Labels labels={task.labels} /> : <span className="muted">None</span>}
              </button>
            </dd>
            <dt>Time spent</dt>
            <dd>{task.timeSpentMinutes ? formatMinutes(task.timeSpentMinutes) : <span className="muted">None</span>}</dd>
            <dt>Reporter</dt>
            <dd><span className="person"><Avatar user={task.reporter} size={24} /> {task.reporter.displayName}</span></dd>
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
          mode={{
            kind: 'edit',
            projectKey: task.projectKey,
            initial: inputOf(task),
            onSubmit: async (input) => {
              setTask(await api.updateTask(task.id, input));
              setEditing(false);
              toast('Changes saved');
              api.activity(taskId).then(setActivity).catch(() => {});
            },
          }}
          onClose={() => setEditing(false)}
        />
      )}
      {editingLabels && (
        <LabelsModal projectKey={task.projectKey} value={editingLabels} onClose={() => setEditingLabels(null)}
          onSave={(labels) => {
            setEditingLabels(null);
            update({ labels }, 'Labels updated');
          }} />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={`Delete ${task.key}?`}
          message="This permanently removes the task with its comments, checklist and files. This cannot be undone."
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

function LabelsModal({ projectKey, value, onSave, onClose }: {
  projectKey: string; value: string[]; onSave: (labels: string[]) => void; onClose: () => void;
}) {
  const [labels, setLabels] = useState(value);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  useEffect(() => {
    api.labels(projectKey).then(setSuggestions).catch(() => {});
  }, [projectKey]);
  return (
    <Modal title="Labels" onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => onSave(labels)}>Save</button>
      </>
    }>
      <LabelInput value={labels} onChange={setLabels} suggestions={suggestions} />
    </Modal>
  );
}

function Attachments({ attachments, userId, ownerId, canEdit, onUpload, onDelete }: {
  attachments: Attachment[];
  userId: number;
  ownerId?: number;
  canEdit: boolean;
  onUpload: (files: FileList | File[]) => Promise<void>;
  onDelete: (attachment: Attachment) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [previews, setPreviews] = useState<Record<number, string>>({});
  const [viewing, setViewing] = useState<Attachment | null>(null);
  const objectUrls = useRef<string[]>([]);
  const toast = useToast();

  // Images are fetched with the auth header and shown from blob URLs.
  useEffect(() => {
    let cancelled = false;
    attachments.filter((a) => a.contentType.startsWith('image/') && !previews[a.id]).forEach((a) => {
      api.attachmentBlob(a.id).then((blob) => {
        if (cancelled) return;
        const url = URL.createObjectURL(blob);
        objectUrls.current.push(url);
        setPreviews((current) => ({ ...current, [a.id]: url }));
      }).catch(() => {});
    });
    return () => {
      cancelled = true;
    };
  }, [attachments]);

  useEffect(() => () => objectUrls.current.forEach((url) => URL.revokeObjectURL(url)), []);

  const handle = async (files: FileList | File[] | null) => {
    if (!files || !files.length) return;
    setUploading(true);
    await onUpload(files);
    setUploading(false);
  };

  const download = async (attachment: Attachment) => {
    try {
      const url = previews[attachment.id] ?? URL.createObjectURL(await api.attachmentBlob(attachment.id));
      const link = document.createElement('a');
      link.href = url;
      link.download = attachment.filename;
      link.click();
      if (!previews[attachment.id]) window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section
      className={`panel attachments ${dragOver ? 'drop-target' : ''}`}
      onDragOver={(e: DragEvent) => {
        if (canEdit && e.dataTransfer.types.includes('Files')) {
          e.preventDefault();
          setDragOver(true);
        }
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragOver(false);
      }}
      onDrop={(e: DragEvent) => {
        e.preventDefault();
        setDragOver(false);
        handle(e.dataTransfer.files);
      }}
    >
      <h2 className="panel-title">
        <Paperclip size={16} /> Attachments {attachments.length > 0 && <span className="count">{attachments.length}</span>}
        <span className="spacer" />
        {canEdit && (
          <button className="btn btn-soft btn-sm" onClick={() => inputRef.current?.click()} disabled={uploading}>
            <Upload size={15} /> {uploading ? 'Uploading…' : 'Upload'}
          </button>
        )}
        <input ref={inputRef} type="file" multiple hidden onChange={(e) => {
          handle(e.target.files);
          e.target.value = '';
        }} />
      </h2>
      {attachments.length === 0 ? (
        <p className={canEdit ? 'muted drop-hint' : 'muted'}>{canEdit ? 'Drop files here or use Upload (max 10 MB each).' : 'No attachments.'}</p>
      ) : (
        <ul className="attachment-grid">
          {attachments.map((a) => (
            <li key={a.id} className="attachment">
              {previews[a.id] ? (
                <button className="attachment-thumb" onClick={() => setViewing(a)} aria-label={`Preview ${a.filename}`}>
                  <img src={previews[a.id]} alt={a.filename} />
                </button>
              ) : (
                <div className="attachment-thumb file"><FileText size={26} /></div>
              )}
              <div className="attachment-info">
                <span className="attachment-name" title={a.filename}>{a.filename}</span>
                <span className="muted small">{fileSize(a.size)} · {a.uploader.displayName}</span>
              </div>
              <div className="attachment-actions">
                <button className="icon-button sm" onClick={() => download(a)} aria-label={`Download ${a.filename}`}>
                  <Download size={15} />
                </button>
                {canEdit && (a.uploader.id === userId || ownerId === userId) && (
                  <button className="icon-button sm" onClick={() => onDelete(a)} aria-label={`Delete ${a.filename}`}>
                    <Trash2 size={15} />
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {viewing && previews[viewing.id] && (
        <Modal title={viewing.filename} onClose={() => setViewing(null)} wide>
          <img className="image-preview" src={previews[viewing.id]} alt={viewing.filename} />
        </Modal>
      )}
    </section>
  );
}
