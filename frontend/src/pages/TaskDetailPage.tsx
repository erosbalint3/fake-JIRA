import { isReadOnlyRole } from '../types';
import { useCallback, useEffect, useRef, useState, type DragEvent, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, BookOpen, CheckSquare, CornerLeftUp, Download, Eye, EyeOff, FileQuestion, FileText, History, MessageSquare, Paperclip,
  Pencil, Trash2, Upload, X,
} from 'lucide-react';
import { api, ApiError } from '../api';
import { useTransitionGuard } from '../components/TransitionGuard';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { PokerPanel } from '../components/task/PokerPanel';
import { ShareModal } from '../components/task/ShareModal';
import { CustomFieldsPanel } from '../components/task/CustomFieldsPanel';
import { ApprovalsPanel } from '../components/task/ApprovalsPanel';
import { PresenceBar } from '../components/task/PresenceBar';
import { InlineComments } from '../components/task/InlineComments';
import { PollsPanel } from '../components/task/PollsPanel';
import { KudosPanel } from '../components/task/KudosPanel';
import { PersonalPanel } from '../components/task/PersonalPanel';
import { SlaPanel } from '../components/task/SlaPanel';
import { RequesterPanel } from '../components/task/RequesterPanel';
import { TimerButton } from '../components/Timer';
import { usePresence } from '../collab';
import { ChipPicker } from '../components/ChipPicker';
import { BlockedBadge, DueBadge, EpicChip, Labels, PriorityBadge, StatusBadge, TypeIcon,
} from '../components/Badges';
import { useCreateTask } from '../components/Layout';
import { DevPanel } from '../components/task/DevPanel';
import { CommentItem } from '../components/task/CommentItem';
import { LinksPanel } from '../components/task/LinksPanel';
import { SubtasksPanel } from '../components/task/SubtasksPanel';
import { TimePanel } from '../components/task/TimePanel';
import { useProjectAccess } from '../useProject';
import { deleteLater } from '../undo';
import { useDraft } from '../drafts';
import { ActionMenu } from '../components/ActionMenu';
import { LabelInput } from '../components/LabelInput';
import { Markdown } from '../components/Markdown';
import { MarkdownEditor } from '../components/MarkdownEditor';
import { ConfirmDialog, Modal } from '../components/Modal';
import { TaskFormModal } from '../components/TaskFormModal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { fileSize, formatDate, formatDay, formatMinutes, timeAgo } from '../format';
import {
  PRIORITIES, PRIORITY_LABEL, RESOLUTIONS, RESOLUTION_LABEL, STATUSES, STATUS_LABEL, type ProjectComponent, type Resolution,
  type Activity, type Attachment, type ChecklistItem, type Comment,
  type DevLink, type Epic, type Release, type Priority, type Sprint, type Status, type Task, type TaskInput, type TaskLink, type TimeEntry,
  type User,
} from '../types';
import { t } from '../i18n';

const MAX_UPLOAD = 10 * 1024 * 1024;

function inputOf(task: Task): TaskInput {
  return {
    title: task.title, description: task.description, priority: task.priority, dueDate: task.dueDate, labels: task.labels,
    storyPoints: task.storyPoints, epicId: task.epic?.id ?? null, type: task.type,
  };
}

export function TaskDetailPage() {
  const { id } = useParams();
  const taskId = Number(id);
  const { user } = useAuth();
  const { byKey } = useProjects();
  const toast = useToast();
  const guard = useTransitionGuard();
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
  // The task's updatedAt when the edit form opened, to catch someone else saving in the meantime.
  const [editBase, setEditBase] = useState<string | null>(null);
  const [conflict, setConflict] = useState<{ input: TaskInput; message: string } | null>(null);
  const [anchor, setAnchor] = useState<string | null>(null);
  const [pollSignal, setPollSignal] = useState(0);
  const [wikiMentions, setWikiMentions] = useState<{ title: string; slug: string }[]>([]);
  const [editingLabels, setEditingLabels] = useState<string[] | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [comment, setComment, clearComment] = useDraft(`comment:${taskId}`);
  const [diff, setDiff] = useState<Activity | null>(null);
  const [moving, setMoving] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [newItem, setNewItem] = useState('');
  const [tab, setTab] = useState<'comments' | 'activity'>('comments');
  const [subtasks, setSubtasks] = useState<Task[]>([]);
  const [links, setLinks] = useState<TaskLink[]>([]);
  const [timeEntries, setTimeEntries] = useState<TimeEntry[]>([]);
  const [devLinks, setDevLinks] = useState<DevLink[]>([]);
  const [epics, setEpics] = useState<Epic[]>([]);
  const [releases, setReleases] = useState<Release[]>([]);
  const [components, setComponents] = useState<ProjectComponent[]>([]);
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
    if (!Number.isNaN(taskId)) api.viewed(taskId).catch(() => {});
  }, [taskId]);

  useEffect(() => {
    if (!task) return;
    api.sprints(task.projectKey).then((list) => setSprints(list.filter((s) => s.state !== 'COMPLETED'))).catch(() => {});
    api.epics(task.projectKey).then(setEpics).catch(() => {});
    api.releases(task.projectKey).then(setReleases).catch(() => {});
    api.components(task.projectKey).then(setComponents).catch(() => {});
  }, [task?.projectKey]);

  const present = usePresence(Number.isNaN(taskId) ? null : taskId, editing, user?.id);
  useEffect(() => {
    if (!Number.isNaN(taskId)) api.taskWikiMentions(taskId).then(setWikiMentions).catch(() => {});
  }, [taskId]);

  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, () => {
    // A live "deleted" event arrives after someone else removed the task.
    api.task(taskId).then(() => load()).catch((e: ApiError) => e.status === 404 ? setDeleted(true) : undefined);
  });

  const project = task ? byKey(task.projectKey) : undefined;
  const { canEdit, isOwner, canComment } = useProjectAccess(project);
  const startEditing = () => {
    setEditBase(task?.updatedAt ?? null);
    setEditing(true);
  };
  // "e" in a task list opens the task straight in the editor.
  const [params, setParams] = useSearchParams();
  useEffect(() => {
    if (task && params.get('edit') === '1') {
      setParams({}, { replace: true });
      if (canEdit) startEditing();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task, canEdit, params]);

  if (deleted || error?.status === 404 || Number.isNaN(taskId)) {
    return (
      <div className="page">
        <EmptyState icon={<FileQuestion size={28} />} title={deleted ? 'This task was deleted' : 'Task not found'}>
          It may have been deleted, or you are not a member of its project. <Link to="/my-work">{t("Go to my work")}</Link>
        </EmptyState>
      </div>
    );
  }
  if (error) return <div className="page"><ErrorBanner message={error.message} onRetry={load} /></div>;
  if (!task || !user) return <div className="page"><Spinner /></div>;

  const members = project?.members ?? [];
  const assignable = members.filter((m) => !isReadOnlyRole(m.role));
  const isAssignee = task.assignee?.id === user.id;
  const canDelete = canEdit && (task.reporter.id === user.id || isOwner);

  /** Runs a change; with {@code undo}, the toast offers to reverse it. */
  const run = async (action: () => Promise<Task>, message?: string, undo?: () => Promise<Task>) => {
    setBusy(true);
    try {
      setTask(await action());
      if (message) toast(message, 'success', undo ? { action: { label: 'Undo', onClick: () => run(undo, 'Change undone') } } : {});
      api.activity(taskId).then(setActivity).catch(() => {});
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    } finally {
      setBusy(false);
    }
  };

  const update = (change: Partial<TaskInput>, message?: string) => {
    const before = inputOf(task);
    return run(() => api.updateTask(task.id, { ...before, ...change }), message,
      () => api.updateTask(task.id, before));
  };

  const remove = () => {
    // The delete waits a few seconds so it can be undone.
    const { id: deletedId, key: deletedKey, projectKey } = task;
    const cancel = deleteLater([deletedId], (error) => error && toast(error.message, 'error'));
    setConfirmDelete(false);
    navigate(`/p/${projectKey}/backlog`, { replace: true });
    toast(`${deletedKey} deleted`, 'success', {
      action: { label: 'Undo', onClick: () => {
        cancel();
        navigate(`/tasks/${deletedId}`);
      } },
    });
  };

  const postComment = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!comment.trim()) return;
    setBusy(true);
    try {
      const created = await api.addComment(task.id, comment.trim(), anchor);
      setComments([...comments, created]);
      clearComment();
      setAnchor(null);
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

  /** Pasted or dropped images become attachments, shown inline with attachment:<id>. */
  const uploadImage = async (file: File) => {
    if (file.size > MAX_UPLOAD) {
      toast(`${file.name || 'The image'} is larger than 10 MB`, 'error');
      throw new Error('too large');
    }
    const named = file.name ? file : new File([file], `pasted-${Date.now()}.png`, { type: file.type });
    const created = await api.uploadAttachment(task.id, named);
    setAttachments((current) => [...current, created]);
    return `![${created.filename.replace(/[[\]]/g, '')}](attachment:${created.id})`;
  };

  const copyText = async (text: string, what: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast(`${what} copied`);
    } catch {
      window.prompt(`Copy the ${what.toLowerCase()}:`, text);
    }
  };

  const doneCount = checklist.filter((i) => i.done).length;
  const threads = comments.filter((c) => !c.parentId);
  const repliesOf = (id: number) => comments.filter((c) => c.parentId === id);

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
        {!canEdit && <span className="readonly-badge"><Eye size={13} /> {canComment ? t("Guest") : t("Read-only")}</span>}
        <PresenceBar present={present} />
      </div>

      <div className="detail">
        <article className="detail-main">
          <div className="detail-key">
            <TypeIcon type={task.type} label />
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
              <button className="btn btn-ghost" onClick={startEditing}>
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
            {canEdit && <TimerButton task={task} />}
            {canDelete && (
              <button className="btn btn-ghost danger" onClick={() => setConfirmDelete(true)}>
                <Trash2 size={16} /> Delete
              </button>
            )}
            <ActionMenu label={t("More actions")} actions={[
              { label: 'Copy branch name', onSelect: () => copyText(branchName(task), 'Branch name') },
              { label: 'Copy git checkout command', onSelect: () => copyText(`git checkout -b ${branchName(task)}`, 'Command') },
              { label: 'Copy commit message', onSelect: () => copyText(`${task.key}: ${task.title}`, 'Commit message') },
              { label: 'Copy link', onSelect: () => copyText(`${window.location.origin}/tasks/${task.id}`, 'Link') },
              { label: 'Clone', hidden: !canEdit, onSelect: async () => {
                try {
                  const copy = await api.cloneTask(task.id, subtasks.length > 0);
                  toast(`Cloned as ${copy.key}`);
                  navigate(`/tasks/${copy.id}`);
                } catch (e) {
                  toast((e as ApiError).message, 'error');
                }
              } },
              { label: 'Move to another project…', hidden: !canEdit || !!task.parent, onSelect: () => setMoving(true) },
              { label: 'Share publicly…', hidden: !canEdit, onSelect: () => setSharing(true) },
              { label: 'Start a poll…', hidden: !canEdit, onSelect: () => setPollSignal((n) => n + 1) },
              { label: task.archivedAt ? 'Restore from archive' : 'Archive', hidden: !canEdit, onSelect: () => run(
                () => (task.archivedAt ? api.unarchiveTask(task.id) : api.archiveTask(task.id)),
                task.archivedAt ? 'Restored from the archive' : 'Archived — it no longer shows on boards and lists') },
            ]} />
          </div>

          {task.archivedAt && (
            <div className="alert info archived-note">
              {t('This task is archived and hidden from boards, lists and search.')}{' '}
              {canEdit && <button className="link" onClick={() => run(() => api.unarchiveTask(task.id), 'Restored from the archive')}>
                {t('Restore')}</button>}
            </div>
          )}
          <section className="panel">
            <h2 className="panel-title"><FileText size={16} /> {t("Description")}</h2>
            {task.description
              ? <InlineComments comments={comments} canComment={canComment} onComment={(quote) => {
                  setAnchor(quote);
                  setTab('comments');
                  window.setTimeout(() => {
                    const box = document.querySelector<HTMLTextAreaElement>('.comment-form textarea');
                    box?.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    box?.focus();
                  }, 50);
                }}><Markdown>{task.description}</Markdown></InlineComments>
              : <p className="muted">No description yet.{canEdit && <> <button className="link" onClick={startEditing}>{t("Add one")}</button></>}</p>}
          </section>

          <RequesterPanel task={task} canEdit={canEdit} />

          <PollsPanel taskId={task.id} canCreate={canEdit} canVote={canComment} startSignal={pollSignal} />

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
                aria-label={t("Checklist progress")}>
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
                <input value={newItem} maxLength={200} placeholder={t("Add an item…")} onChange={(e) => setNewItem(e.target.value)}
                  aria-label={t("New checklist item")} />
                <button className="btn btn-soft btn-sm" disabled={!newItem.trim()}>{t("Add")}</button>
              </form>
            )}
            {!canEdit && checklist.length === 0 && <p className="muted">{t("No checklist.")}</p>}
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
          <DevPanel taskId={task.id} links={devLinks} onChanged={() => api.devLinks(task.id).then(setDevLinks).catch(() => {})} />

          <section className="panel">
            <div className="tabs compact" role="tablist">
              <button role="tab" aria-selected={tab === 'comments'} className={`tab ${tab === 'comments' ? 'active' : ''}`}
                onClick={() => setTab('comments')}>
                <MessageSquare size={15} /> {t("Comments")} <span className="count">{comments.length}</span>
              </button>
              <button role="tab" aria-selected={tab === 'activity'} className={`tab ${tab === 'activity' ? 'active' : ''}`}
                onClick={() => setTab('activity')}>
                <History size={15} /> Activity
              </button>
            </div>

            {tab === 'comments' ? (
              <>
                <ul className="comments">
                  {threads.map((c) => (
                    <CommentItem key={c.id} taskId={task.id} comment={c} me={user} canEdit={canComment} isOwner={isOwner}
                      members={members} replies={repliesOf(c.id)} uploadImage={canEdit ? uploadImage : undefined}
                      onReplied={(reply) => setComments((list) => [...list, reply])}
                      onChanged={(updated) => setComments((list) => list.map((x) => (x.id === updated.id ? updated : x)))}
                      onDeleted={(id) => setComments((list) => list.filter((x) => x.id !== id && x.parentId !== id))} />
                  ))}
                </ul>
                {canComment && <form className="comment-form" onSubmit={postComment}>
                  <Avatar user={user} size={32} />
                  <div className="comment-input">
                    {anchor && (
                      <div className="comment-anchor-draft">
                        <blockquote className="comment-anchor">“{anchor}”</blockquote>
                        <button type="button" className="icon-button sm" aria-label={t("Comment on the whole task instead")}
                          onClick={() => setAnchor(null)}><X size={13} /></button>
                      </div>
                    )}
                    <MarkdownEditor value={comment} onChange={setComment} members={members} rows={3} maxLength={2000}
                      label={t("Comment")} placeholder={t("Add a comment… Type @ to mention someone. Ctrl+Enter to send.")}
                      onSubmitShortcut={() => postComment()} onUploadImage={canEdit ? uploadImage : undefined} />
                    <button className="btn btn-primary btn-sm" disabled={busy || !comment.trim()}>{t("Comment")}</button>
                  </div>
                </form>}
              </>
            ) : (
              <ul className="activity">
                {activity.map((a) => (
                  <li key={a.id}>
                    <Avatar user={a.actor} size={24} />
                    <span><strong>{a.actor.displayName}</strong> {a.message}
                      {a.before !== null && a.after !== null && (
                        <> · <button className="link small" onClick={() => setDiff(a)}>{t("Show changes")}</button></>
                      )}
                    </span>
                    <span className="muted small" title={new Date(a.createdAt).toLocaleString()}>{timeAgo(a.createdAt)}</span>
                  </li>
                ))}
                {activity.length === 0 && <li className="muted">{t("No activity yet.")}</li>}
              </ul>
            )}
          </section>
        </article>

        <aside className="detail-side panel">
          <dl className="props">
            <dt>{t("Status")}</dt>
            <dd>
              <select value={task.status} disabled={busy || !canEdit} aria-label={t("Status")}
                onChange={(e) => {
                  const previous = task.status;
                  const next = e.target.value as Status;
                  guard(task, (resolution) => api.setStatus(task.id, next, resolution)).then((moved) => {
                    if (!moved) return;
                    setTask(moved);
                    toast(`Moved to ${STATUS_LABEL[next]}`, 'success',
                      { action: { label: 'Undo', onClick: () => run(() => api.setStatus(task.id, previous), 'Change undone') } });
                    api.activity(taskId).then(setActivity).catch(() => {});
                  }).catch((err: ApiError) => toast(err.message, 'error'));
                }}>
                {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
              </select>
            </dd>
            {task.status === 'DONE' && (
              <>
                <dt>{t("Resolution")}</dt>
                <dd>
                  <select value={task.resolution ?? 'DONE'} disabled={busy || !canEdit} aria-label={t("Resolution")}
                    onChange={(e) => run(() => api.setResolution(task.id, e.target.value as Resolution), 'Resolution updated')}>
                    {RESOLUTIONS.map((r) => <option key={r} value={r}>{t(RESOLUTION_LABEL[r])}</option>)}
                  </select>
                </dd>
              </>
            )}
            <dt>{t("Assignee")}</dt>
            <dd>
              <select value={task.assignee?.id ?? ''} disabled={busy || !canEdit} aria-label={t("Assignee")}
                onChange={(e) => {
                  const previous = task.assignee?.id ?? null;
                  const next = assignable.find((m) => m.id === Number(e.target.value));
                  run(() => api.assign(task.id, e.target.value ? Number(e.target.value) : null),
                    !e.target.value ? 'Unassigned' : next?.awayUntil ? `Assigned — note: ${next.displayName} is away until ${formatDay(next.awayUntil)}`
                      : 'Assignee updated', () => api.assign(task.id, previous));
                }}>
                <option value="">{t("Unassigned")}</option>
                {assignable.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}{m.id === user.id ? ' (me)' : ''}{m.awayUntil ? ` — away until ${formatDay(m.awayUntil)}` : ''}
                  </option>
                ))}
              </select>
              {task.assignee?.awayUntil && (
                <span className="away-note small">Away until {formatDay(task.assignee.awayUntil)}</span>
              )}
            </dd>
            <dt>{t("Helpers")}</dt>
            <dd>
              <ChipPicker label={t("Add a helper")} disabled={busy || !canEdit} max={5}
                options={assignable.filter((m) => m.id !== task.assignee?.id).map((m) => ({ id: m.id, label: m.displayName }))}
                selected={task.helpers.map((h) => h.id)}
                onChange={(ids) => run(() => api.setHelpers(task.id, ids), 'Helpers updated')} />
            </dd>
            <dt>{t("Priority")}</dt>
            <dd>
              <div className="with-icon">
                <PriorityBadge priority={task.priority} compact />
                <select value={task.priority} disabled={busy || !canEdit} aria-label={t("Priority")}
                  onChange={(e) => update({ priority: e.target.value as Priority }, 'Priority updated')}>
                  {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
                </select>
              </div>
            </dd>
            <dt>{t("Sprint")}</dt>
            <dd>
              <select value={task.sprint?.id ?? ''} disabled={busy || !canEdit} aria-label={t("Sprint")}
                onChange={(e) => {
                  const previous = task.sprint?.state === 'COMPLETED' ? null : task.sprint?.id ?? null;
                  run(() => api.moveToSprint(task.id, e.target.value ? Number(e.target.value) : null),
                    e.target.value ? 'Moved to sprint' : 'Moved to backlog', () => api.moveToSprint(task.id, previous));
                }}>
                <option value="">{t("Backlog")}</option>
                {task.sprint?.state === 'COMPLETED' && <option value={task.sprint.id}>{task.sprint.name} (completed)</option>}
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
              </select>
            </dd>
            <dt>{t("Due date")}</dt>
            <dd>
              <input type="date" value={task.dueDate ?? ''} disabled={busy || !canEdit} aria-label={t("Due date")}
                onChange={(e) => update({ dueDate: e.target.value || null }, e.target.value ? 'Due date set' : 'Due date removed')} />
            </dd>
            <dt>{t("Start date")}</dt>
            <dd>
              <input type="date" value={task.startDate ?? ''} disabled={busy || !canEdit} aria-label={t("Start date")}
                max={task.dueDate ?? undefined}
                onChange={(e) => run(() => api.schedule(task.id, { startDate: e.target.value || null, dueDate: task.dueDate,
                  estimateMinutes: task.estimateMinutes }), e.target.value ? 'Start date set' : 'Start date removed')} />
            </dd>
            <dt>{t("Estimate")}</dt>
            <dd className="with-unit">
              <input type="number" min={0} step={0.5} key={`est-${task.estimateMinutes}`} placeholder="–"
                defaultValue={task.estimateMinutes != null ? task.estimateMinutes / 60 : ''} disabled={busy || !canEdit}
                aria-label={t("Estimate (hours)")} title={t("Estimate (hours)")}
                onBlur={(e) => {
                  const minutes = e.target.value === '' ? null : Math.round(Math.max(0, Number(e.target.value)) * 60);
                  if (minutes !== task.estimateMinutes) {
                    run(() => api.schedule(task.id, { startDate: task.startDate, dueDate: task.dueDate, estimateMinutes: minutes }),
                      'Estimate updated');
                  }
                }} />
              <span className="muted small"> h</span>
            </dd>
            <dt>{t("Points")}</dt>
            <dd>
              <input type="number" min={0} max={100} key={`sp-${task.storyPoints}`} defaultValue={task.storyPoints ?? ''}
                disabled={busy || !canEdit} aria-label={t("Story points")} placeholder="–"
                onBlur={(e) => {
                  const value = e.target.value === '' ? null : Math.max(0, Math.min(100, Number(e.target.value)));
                  if (value !== task.storyPoints) update({ storyPoints: value }, 'Estimate updated');
                }} />
            </dd>
            <dt>{t("Epic")}</dt>
            <dd>
              <select value={task.epic?.id ?? ''} disabled={busy || !canEdit} aria-label={t("Epic")}
                onChange={(e) => update({ epicId: e.target.value ? Number(e.target.value) : null }, 'Epic updated')}>
                <option value="">{t("No epic")}</option>
                {epics.map((epic) => <option key={epic.id} value={epic.id}>{epic.name}</option>)}
              </select>
            </dd>
            <dt>{t("Components")}</dt>
            <dd>
              <ChipPicker label={t("Add a component")} disabled={busy || !canEdit} max={10}
                options={components.map((c) => ({ id: c.id, label: c.name }))}
                selected={task.components.map((c) => c.id)}
                onChange={(ids) => run(() => api.setTaskComponents(task.id, ids), 'Components updated')} />
            </dd>
            <dt>{t("Release")}</dt>
            <dd>
              <select value={task.release?.id ?? ''} disabled={busy || !canEdit} aria-label={t("Release")}
                onChange={(e) => {
                  const previous = task.release?.id ?? null;
                  run(() => api.setTaskRelease(task.id, e.target.value ? Number(e.target.value) : null),
                    e.target.value ? 'Release updated' : 'Removed from release', () => api.setTaskRelease(task.id, previous));
                }}>
                <option value="">{t("No release")}</option>
                {task.release && !releases.some((r) => r.id === task.release!.id) && (
                  <option value={task.release.id}>{task.release.name}</option>
                )}
                {releases.filter((r) => !r.released || r.id === task.release?.id).map((r) => (
                  <option key={r.id} value={r.id}>{r.name}{r.released ? ' (released)' : ''}</option>
                ))}
              </select>
            </dd>
            <dt>{t("Labels")}</dt>
            <dd>
              <button className="labels-button" disabled={!canEdit} onClick={() => setEditingLabels(task.labels)} aria-label={t("Edit labels")}>
                {task.labels.length ? <Labels labels={task.labels} /> : <span className="muted">{t("None")}</span>}
              </button>
            </dd>
            <dt>{t("Time spent")}</dt>
            <dd>{task.timeSpentMinutes ? formatMinutes(task.timeSpentMinutes) : <span className="muted">{t("None")}</span>}
              {task.estimateMinutes ? <span className="muted small"> {t('of {estimate}', { estimate: formatMinutes(task.estimateMinutes) })}</span> : null}</dd>
            <dt>{t("Reporter")}</dt>
            <dd><span className="person"><Avatar user={task.reporter} size={24} /> {task.reporter.displayName}</span></dd>
            <dt>{t("Created")}</dt>
            <dd>{formatDate(task.createdAt)}</dd>
            <dt>{t("Updated")}</dt>
            <dd>{timeAgo(task.updatedAt)}</dd>
          </dl>
          <CustomFieldsPanel taskId={task.id} canEdit={canEdit} />
          <ApprovalsPanel taskId={task.id} members={members} canEdit={canEdit} userId={user.id} />
          <KudosPanel task={task} me={user} canThank={canComment} />
          <SlaPanel task={task} />
          <PersonalPanel task={task} />
          {wikiMentions.length > 0 && (
            <section className="side-section">
              <h3 className="side-title"><BookOpen size={15} /> {t("Mentioned in the wiki")}</h3>
              <ul className="mini-list">
                {wikiMentions.map((p) => <li key={p.slug}><Link to={`/p/${task.projectKey}/wiki/${p.slug}`}>{p.title}</Link></li>)}
              </ul>
            </section>
          )}
          {!task.parent && <PokerPanel task={task} canEdit={canEdit} onAccepted={load} />}
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
            taskId: task.id,
            coEditors: present.filter((p) => p.editing).map((p) => p.user.displayName),
            onSubmit: async (input) => {
              try {
                setTask(await api.updateTask(task.id, input, editBase ?? undefined));
              } catch (e) {
                const err = e as ApiError;
                if (err.status === 409) {
                  setConflict({ input, message: err.message });
                  return;
                }
                throw e;
              }
              setEditing(false);
              toast(t("Changes saved"));
              api.activity(taskId).then(setActivity).catch(() => {});
            },
          }}
          onClose={() => setEditing(false)}
          uploadImage={uploadImage}
        />
      )}
      {conflict && (
        <ConfirmDialog title={t("Someone else changed this task")} message={conflict.message}
          confirmLabel={t("Save mine anyway")} onClose={() => setConflict(null)}
          onConfirm={async () => {
            try {
              setTask(await api.updateTask(task.id, conflict.input));
              toast(t("Changes saved"));
              setEditing(false);
            } catch (e) {
              toast((e as ApiError).message, 'error');
            }
            setConflict(null);
          }}>
          <p className="muted small">{t("Saving replaces their title, priority, due date, labels and description with yours. Cancel to keep editing; your text stays in the form.")}</p>
        </ConfirmDialog>
      )}
      {diff && <DiffModal activity={diff} onClose={() => setDiff(null)} />}
      {sharing && <ShareModal task={task} onClose={() => setSharing(false)} />}
      {moving && (
        <MoveModal task={task} onClose={() => setMoving(false)} onMoved={(moved) => {
          setMoving(false);
          toast(`${task.key} is now ${moved.key}`);
          setTask(moved);
          navigate(`/tasks/${moved.id}`, { replace: true });
        }} />
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
          message={t("This removes the task with its subtasks, comments, checklist and files. You can undo for a few seconds.")}
          confirmLabel={t("Delete task")}
          danger
          busy={busy}
          onConfirm={remove}
          onClose={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}

/** A git-friendly branch name: WEB-12-fix-checkout-rounding. */
function branchName(task: Task) {
  const slug = task.title.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 48).replace(/-+$/, '');
  return slug ? `${task.key}-${slug}` : task.key;
}

/** Shows what a description change did, line by line. */
function DiffModal({ activity, onClose }: { activity: Activity; onClose: () => void }) {
  const lines = diffLines(activity.before ?? '', activity.after ?? '');
  return (
    <Modal title={t("Description changes")} onClose={onClose} wide>
      <p className="muted small">{activity.actor.displayName} · {new Date(activity.createdAt).toLocaleString()}</p>
      <pre className="diff" aria-label={t("Changes")}>
        {lines.map((line, i) => (
          <div key={i} className={`diff-${line.kind}`}>
            <span className="diff-sign" aria-hidden>{line.kind === 'add' ? '+' : line.kind === 'del' ? '−' : ' '}</span>
            <span className="sr-only">{line.kind === 'add' ? 'added: ' : line.kind === 'del' ? 'removed: ' : ''}</span>
            {line.text || ' '}
          </div>
        ))}
      </pre>
    </Modal>
  );
}

/** Line diff by longest common subsequence (descriptions are at most 5000 characters). */
function diffLines(before: string, after: string): { kind: 'same' | 'add' | 'del'; text: string }[] {
  const a = before.split('\n');
  const b = after.split('\n');
  const table = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const out: { kind: 'same' | 'add' | 'del'; text: string }[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] });
      i++;
      j++;
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      out.push({ kind: 'del', text: a[i++] });
    } else {
      out.push({ kind: 'add', text: b[j++] });
    }
  }
  while (i < a.length) out.push({ kind: 'del', text: a[i++] });
  while (j < b.length) out.push({ kind: 'add', text: b[j++] });
  return out;
}

function MoveModal({ task, onClose, onMoved }: { task: Task; onClose: () => void; onMoved: (task: Task) => void }) {
  const { projects } = useProjects();
  const { user } = useAuth();
  const targets = (projects ?? []).filter((p) => p.key !== task.projectKey
    && p.members.some((m) => m.id === user?.id && !isReadOnlyRole(m.role)));
  const [target, setTarget] = useState(targets[0]?.key ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  return (
    <Modal title={`Move ${task.key}`} onClose={onClose} footer={<>
      <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
      <button className="btn btn-primary" disabled={busy || !target} onClick={async () => {
        setBusy(true);
        try {
          onMoved(await api.moveTask(task.id, target));
        } catch (e) {
          setError((e as ApiError).message);
          setBusy(false);
        }
      }}>Move</button>
    </>}>
      <div className="form">
        {error && <div className="alert">{error}</div>}
        {targets.length === 0 ? <p className="muted">{t("You can't edit any other project.")}</p> : (
          <label className="field">
            <span>{t("Project")}</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)}>
              {targets.map((p) => <option key={p.key} value={p.key}>{p.name} ({p.key})</option>)}
            </select>
          </label>
        )}
        <p className="muted small">
          The task{task.subtaskTotal ? ' and its subtasks' : ''} get a new key there; {task.key} keeps working as a link.
          Sprint, epic and board column are cleared.
        </p>
      </div>
    </Modal>
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
    <Modal title={t("Labels")} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" onClick={() => onSave(labels)}>{t("Save")}</button>
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
