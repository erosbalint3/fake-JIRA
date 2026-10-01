import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from 'react';
import { Bookmark, CalendarRange, Eye, Inbox, MoreHorizontal, Plus, Search, Zap } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useFocusSearch } from '../shortcuts';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { FILTERS_CHANGED, useCreateTask } from '../components/Layout';
import { BulkBar } from '../components/BulkBar';
import { ConfirmDialog, Modal } from '../components/Modal';
import { useListNavigation } from '../components/ListNavigation';
import { TaskRow } from '../components/TaskRow';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import { PRIORITIES, PRIORITY_LABEL, PRIORITY_ORDER, type Epic, type Priority, type Sprint, type Task, TASK_TYPES, TASK_TYPE_LABEL,
  type VelocityEntry,
} from '../types';
import { t } from '../i18n';
import { SprintGoals } from '../components/sprint/SprintGoals';

type SprintDialog = { kind: 'edit' | 'start'; sprint: Sprint } | { kind: 'create' } | null;

export function BacklogPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const { user } = useAuth();
  const toast = useToast();
  const openCreate = useCreateTask();
  const searchRef = useRef<HTMLInputElement>(null);
  useFocusSearch(searchRef);

  const [tasks, setTasks] = useState<Task[] | null>(null);
  // Tasks whose delete can still be undone.
  const [hidden, setHidden] = useState<number[] | null>(null);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [error, setError] = useState('');
  // Filters live in the URL so they can be bookmarked, shared and saved.
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';
  const assignee = params.get('assignee') ?? '';
  const priority = (params.get('priority') ?? '') as Priority | '';
  const label = params.get('label') ?? '';
  const epicFilter = params.get('epic') ?? '';
  const typeFilter = params.get('type') ?? '';
  const setFilter = (name: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value);
    else next.delete(name);
    setParams(next, { replace: true });
  };
  const [epics, setEpics] = useState<Epic[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [lastSelected, setLastSelected] = useState<number | null>(null);
  const [savingFilter, setSavingFilter] = useState(false);
  const [showDone, setShowDone] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const [dialog, setDialog] = useState<SprintDialog>(null);
  const [confirm, setConfirm] = useState<{ kind: 'complete' | 'delete'; sprint: Sprint } | null>(null);
  const [velocity, setVelocity] = useState<VelocityEntry[]>([]);

  const load = useCallback(() => {
    if (!project) return;
    setError('');
    Promise.all([api.tasks({ project: key }), api.sprints(key), api.epics(key),
      project.kanban ? Promise.resolve([]) : api.velocity(key)])
      .then(([t, s, e, v]) => {
        setTasks(t);
        setSprints(s);
        setEpics(e);
        setVelocity(v);
        setSelected((current) => current.filter((id) => t.some((task) => task.id === id)));
      })
      .catch((e: ApiError) => setError(e.message));
  }, [key, project]);

  useEffect(() => {
    setTasks(null);
    load();
  }, [load]);

  useLiveRefresh(
    (m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id,
    load,
  );

  const labels = useMemo(() => [...new Set((tasks ?? []).flatMap((t) => t.labels))].sort(), [tasks]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (tasks ?? [])
      .filter((t) => !hidden?.includes(t.id))
      .filter((t) => !q || t.title.toLowerCase().includes(q) || t.key.toLowerCase().includes(q)
        || t.description.toLowerCase().includes(q))
      .filter((t) => !priority || t.priority === priority)
      .filter((t) => !label || t.labels.includes(label))
      .filter((t) => !epicFilter || (epicFilter === 'none' ? !t.epic : t.epic?.id === Number(epicFilter)))
      .filter((t) => !typeFilter || t.type === typeFilter)
      .filter((t) => !assignee
        || (assignee === 'none' ? !t.assignee : t.assignee?.id === Number(assignee === 'me' ? user?.id : assignee)))
      .sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || a.id - b.id);
  }, [tasks, query, priority, label, assignee, epicFilter, typeFilter, user, hidden]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !document.querySelector('.modal')) setSelected([]);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  const pageRef = useRef<HTMLDivElement>(null);
  const navPicker = useListNavigation(pageRef, load);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const kanban = project.kanban;
  const openSprints = kanban ? [] : sprints.filter((s) => s.state !== 'COMPLETED')
    .sort((a, b) => (a.state === 'ACTIVE' ? -1 : b.state === 'ACTIVE' ? 1 : a.id - b.id));
  const hasActive = openSprints.some((s) => s.state === 'ACTIVE');
  const backlog = visible.filter((t) => !t.sprint && (showDone || t.status !== 'DONE'));
  const filtered = !!(query || priority || label || assignee || epicFilter || typeFilter);
  // Planning helper: what the team usually finishes, from the last three completed sprints.
  const recent = velocity.slice(-3);
  const averageVelocity = recent.length
    ? Math.round(recent.reduce((sum, v) => sum + v.completedPoints, 0) / recent.length) : null;
  const completedSprints = kanban ? [] : sprints.filter((s) => s.state === 'COMPLETED').sort((a, b) => b.id - a.id);
  const ordered = [...openSprints.flatMap((s) => visible.filter((t) => t.sprint?.id === s.id)), ...backlog];

  /** Click toggles one row; shift-click selects the range since the last click. */
  const toggleSelect = (task: Task, shiftKey: boolean) => {
    if (shiftKey && lastSelected !== null) {
      const from = ordered.findIndex((t) => t.id === lastSelected);
      const to = ordered.findIndex((t) => t.id === task.id);
      if (from >= 0 && to >= 0) {
        const range = ordered.slice(Math.min(from, to), Math.max(from, to) + 1).map((t) => t.id);
        setSelected((current) => [...new Set([...current, ...range])]);
        setLastSelected(task.id);
        return;
      }
    }
    setSelected((current) => (current.includes(task.id) ? current.filter((id) => id !== task.id) : [...current, task.id]));
    setLastSelected(task.id);
  };

  const sectionCheckbox = (items: Task[]) => {
    if (!canEdit || !items.length) return null;
    const all = items.every((t) => selected.includes(t.id));
    return (
      <input type="checkbox" className="row-check" checked={all} aria-label={t("Select all in section")}
        onChange={() => setSelected((current) => (all
          ? current.filter((id) => !items.some((t) => t.id === id))
          : [...new Set([...current, ...items.map((t) => t.id)])]))} />
    );
  };

  const move = async (task: Task, sprintId: number | null) => {
    if ((task.sprint?.id ?? null) === sprintId) return;
    const target = sprints.find((s) => s.id === sprintId);
    setTasks((current) => current?.map((t) => (t.id === task.id
      ? { ...t, sprint: target ? { id: target.id, name: target.name, state: target.state } : null } : t)) ?? null);
    try {
      await api.moveToSprint(task.id, sprintId);
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    }
  };

  const onDrop = (event: DragEvent, sprintId: number | null) => {
    event.preventDefault();
    setDropTarget(null);
    setDragging(null);
    const task = tasks?.find((t) => t.id === Number(event.dataTransfer.getData('text/plain')));
    if (task) move(task, sprintId);
  };

  const dropProps = (id: string, sprintId: number | null) => (!canEdit ? {} : {
    onDragOver: (e: DragEvent) => {
      e.preventDefault();
      setDropTarget(id);
    },
    onDragLeave: (e: DragEvent<HTMLElement>) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node)) setDropTarget(null);
    },
    onDrop: (e: DragEvent) => onDrop(e, sprintId),
  });

  /** Saves an in-place edit optimistically; the toast offers undo. */
  const patch = async (task: Task, change: Partial<Task>, save: (t: Task) => Promise<Task>, undo: () => Promise<Task>,
    message: string) => {
    setTasks((current) => current?.map((t) => (t.id === task.id ? { ...t, ...change } : t)) ?? null);
    try {
      const saved = await save(task);
      setTasks((current) => current?.map((t) => (t.id === saved.id ? saved : t)) ?? null);
      toast(message, 'success', { action: { label: t('Undo'), onClick: () => undo().then(load).catch(load) } });
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    }
  };

  const inputOf = (t: Task) => ({
    title: t.title, description: t.description, priority: t.priority, dueDate: t.dueDate, labels: t.labels,
    storyPoints: t.storyPoints, epicId: t.epic?.id ?? null, type: t.type,
  });

  const inline = canEdit && project ? {
    members: project.members,
    onRename: (t: Task, title: string) => patch(t, { title }, () => api.updateTask(t.id, { ...inputOf(t), title }),
      () => api.updateTask(t.id, inputOf(t)), `${t.key} renamed`),
    onPoints: (t: Task, storyPoints: number | null) => patch(t, { storyPoints },
      () => api.updateTask(t.id, { ...inputOf(t), storyPoints }), () => api.updateTask(t.id, inputOf(t)),
      storyPoints === null ? `${t.key} estimate removed` : `${t.key} estimated at ${storyPoints}`),
    onAssign: (t: Task, userId: number | null) => patch(t,
      { assignee: project.members.find((m) => m.id === userId) ?? null },
      () => api.assign(t.id, userId), () => api.assign(t.id, t.assignee?.id ?? null),
      userId ? `${t.key} assigned` : `${t.key} unassigned`),
  } : undefined;

  const row = (task: Task) => (
    <TaskRow key={task.id} task={task} className={dragging === task.id ? 'dragging' : ''}
      selected={selected.includes(task.id)} inline={inline}
      onToggleSelect={canEdit ? (shift) => toggleSelect(task, shift) : undefined}
      rowProps={{
        draggable: canEdit,
        onDragStart: (e) => {
          e.dataTransfer.setData('text/plain', String(task.id));
          e.dataTransfer.effectAllowed = 'move';
          setDragging(task.id);
        },
        onDragEnd: () => setDragging(null),
      }}
      actions={canEdit &&
        <select className="move-select" value={task.sprint?.id ?? ''} aria-label={t('Move {key}', { key: task.key })}
          onChange={(e) => move(task, e.target.value ? Number(e.target.value) : null)}>
          <option value="">{t("Backlog")}</option>
          {openSprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      } />
  );

  const runSprint = async (action: () => Promise<unknown>, message: string) => {
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <div className="page page-wide" ref={pageRef}>
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t("Backlog")}</h1>
          <p className="muted">{kanban
            ? t('Kanban: every open task is on the board. Order the queue by priority here.')
            : t('Plan sprints by dragging tasks between sections.')}</p>
        </div>
        <div className="header-actions">
          {canEdit ? (
            <>
              {!kanban && (
                <button className="btn btn-ghost" onClick={() => setDialog({ kind: 'create' })}>
                  <CalendarRange size={17} /> {t('Create sprint')}
                </button>
              )}
              <button className="btn btn-primary" onClick={() => openCreate({ projectKey: key })}>
                <Plus size={18} /> {t('Create task')}
              </button>
            </>
          ) : <span className="readonly-badge"><Eye size={13} /> {t("Read-only")}</span>}
        </div>
      </header>

      <div className="toolbar">
        <label className="search">
          <Search size={16} />
          <input ref={searchRef} placeholder={t("Search tasks  ( / )")} value={query}
            onChange={(e) => setFilter('q', e.target.value)} aria-label={t("Search tasks")} />
        </label>
        <select value={assignee} onChange={(e) => setFilter('assignee', e.target.value)} aria-label={t("Assignee")}>
          <option value="">{t("Anyone")}</option>
          <option value="me">{t("Assigned to me")}</option>
          <option value="none">{t("Unassigned")}</option>
          {project.members.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
        </select>
        <select value={priority} onChange={(e) => setFilter('priority', e.target.value)} aria-label={t("Priority")}>
          <option value="">{t("Any priority")}</option>
          {PRIORITIES.map((p) => <option key={p} value={p}>{t(PRIORITY_LABEL[p])}</option>)}
        </select>
        <select value={label} onChange={(e) => setFilter('label', e.target.value)} aria-label={t("Label")}>
          <option value="">{t("Any label")}</option>
          {labels.map((l) => <option key={l} value={l}>{l}</option>)}
        </select>
        <select value={typeFilter} onChange={(e) => setFilter('type', e.target.value)} aria-label={t("Type")}>
          <option value="">{t("Any type")}</option>
          {TASK_TYPES.map((type) => <option key={type} value={type}>{t(TASK_TYPE_LABEL[type])}</option>)}
        </select>
        <select value={epicFilter} onChange={(e) => setFilter('epic', e.target.value)} aria-label={t("Epic")}>
          <option value="">{t("Any epic")}</option>
          <option value="none">{t("No epic")}</option>
          {epics.map((epic) => <option key={epic.id} value={epic.id}>{epic.name}</option>)}
        </select>
        {filtered && (
          <>
            <button className="btn btn-ghost btn-sm filter-action" onClick={() => setSavingFilter(true)}>
              <Bookmark size={15} /> {t('Save filter')}
            </button>
            <button className="link small filter-action" onClick={() => setParams({}, { replace: true })}>{t("Clear")}</button>
          </>
        )}
      </div>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}

      {tasks && openSprints.map((sprint) => {
        const items = visible.filter((t) => t.sprint?.id === sprint.id);
        const done = items.filter((t) => t.status === 'DONE').length;
        const planned = items.reduce((sum, t) => sum + (t.storyPoints ?? 0), 0);
        const unestimated = items.filter((t) => t.storyPoints === null).length;
        const overCapacity = averageVelocity !== null && planned > averageVelocity * 1.1;
        const perPerson = new Map<string, number>();
        items.forEach((task) => {
          const name = task.assignee?.displayName ?? t('Unassigned');
          perPerson.set(name, (perPerson.get(name) ?? 0) + (task.storyPoints ?? 0));
        });
        return (
          <section key={sprint.id} className={`sprint-section ${dropTarget === `s${sprint.id}` ? 'drop-target' : ''}`}
            {...dropProps(`s${sprint.id}`, sprint.id)}>
            <header className="sprint-header">
              <div className="sprint-title">
                {sectionCheckbox(items)}
                {sprint.state === 'ACTIVE' && <Zap size={16} className="sprint-active-icon" />}
                <h2>{sprint.name}</h2>
                <span className={`sprint-state state-${sprint.state.toLowerCase()}`}>{sprint.state === 'ACTIVE' ? 'Active' : 'Planned'}</span>
                {sprint.startDate && sprint.endDate && (
                  <span className="muted small">{formatDay(sprint.startDate)} – {formatDay(sprint.endDate)}</span>
                )}
                <span className="muted small">{items.length === 1 ? t('1 task') : t('{n} tasks', { n: items.length })}{items.length ? ` · ${t('{n} done', { n: done })}` : ''}</span>
              </div>
              {canEdit && <div className="sprint-actions">
                {sprint.state === 'PLANNED' && (
                  <button className="btn btn-soft btn-sm" disabled={hasActive}
                    title={hasActive ? t('Complete the active sprint first') : undefined}
                    onClick={() => setDialog({ kind: 'start', sprint })}>{t("Start sprint")}</button>
                )}
                {sprint.state === 'ACTIVE' && (
                  <button className="btn btn-soft btn-sm" onClick={() => setConfirm({ kind: 'complete', sprint })}>{t("Complete sprint")}</button>
                )}
                {sprint.state === 'ACTIVE' && (
                  <Link className="btn btn-ghost btn-sm" to={`/p/${key}/sprints/${sprint.id}`}>{t("Review & retro")}</Link>
                )}
                <Link className="btn btn-ghost btn-sm" to={`/p/${key}/sprints/${sprint.id}?tab=capacity`}>{t("Capacity")}</Link>
                <SprintMenu
                  onEdit={() => setDialog({ kind: 'edit', sprint })}
                  onDelete={sprint.state === 'PLANNED' ? () => setConfirm({ kind: 'delete', sprint }) : undefined}
                  onAddTask={() => openCreate({ projectKey: key, sprintId: sprint.id })}
                />
              </div>}
            </header>
            {sprint.goal && <p className="sprint-goal muted">{sprint.goal}</p>}
            <SprintGoals sprintId={sprint.id} canEdit={canEdit} compact />
            {items.length > 0 && (
              <div className={`capacity ${overCapacity ? 'over' : ''}`} aria-label={t("Sprint capacity")}>
                <strong>{t('{n} pts planned', { n: planned })}</strong>
                {averageVelocity !== null
                  ? <span>· {t('team average {n} pts', { n: averageVelocity })}{overCapacity ? ` — ${t('more than the team usually finishes')}` : ''}</span>
                  : <span className="muted">{t("· complete a sprint to see the team's velocity")}</span>}
                {unestimated > 0 && <span className="muted">· {t('{n} unestimated', { n: unestimated })}</span>}
                <span className="capacity-people">
                  {[...perPerson.entries()].sort((a, b) => b[1] - a[1]).map(([name, pts]) => (
                    <span key={name} className="chip">{name} {pts}</span>
                  ))}
                </span>
              </div>
            )}
            {items.length ? <ul className="task-list">{items.map(row)}</ul>
              : <div className="column-empty">{filtered ? t('No matching tasks.') : t('Drag tasks here to plan this sprint.')}</div>}
          </section>
        );
      })}

      {tasks && (
        <section className={`sprint-section backlog-section ${dropTarget === 'backlog' ? 'drop-target' : ''}`}
          {...dropProps('backlog', null)}>
          <header className="sprint-header">
            <div className="sprint-title">
              {sectionCheckbox(backlog)}
              <h2>{t("Backlog")}</h2>
              <span className="muted small">{backlog.length === 1 ? t('1 task') : t('{n} tasks', { n: backlog.length })}</span>
            </div>
            <label className="toggle small">
              <input type="checkbox" checked={showDone} onChange={(e) => setShowDone(e.target.checked)} /> {t('Show done')}
            </label>
          </header>
          {backlog.length ? <ul className="task-list">{backlog.map(row)}</ul> : (
            <EmptyState icon={<Inbox size={28} />} title={filtered ? t('No matching tasks') : t('The backlog is empty')}>
              {filtered ? t('Try a different filter.') : canEdit ? <>{t('Everything is planned.')} <button className="link" onClick={() => openCreate({ projectKey: key })}>{t("Create a task")}</button></> : null}
            </EmptyState>
          )}
        </section>
      )}

      {tasks && completedSprints.length > 0 && (
        <section className="past-sprints">
          <h2 className="section-title">{t("Completed sprints")}</h2>
          <ul>
            {completedSprints.slice(0, 8).map((s) => (
              <li key={s.id}>
                <Link to={`/p/${key}/sprints/${s.id}`}>{s.name}</Link>
                {s.completedAt && <span className="muted small"> · {t('completed {date}', { date: formatDay(s.completedAt.slice(0, 10)) })}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {selected.length > 0 && (
        <BulkBar selected={selected} tasks={tasks ?? []} members={project.members} sprints={sprints} epics={epics}
          onClear={() => setSelected([])} onDone={() => {
            load();
          }} onHide={setHidden} />
      )}
      {savingFilter && (
        <SaveFilterModal projectKey={key} query={params.toString()} onClose={() => setSavingFilter(false)}
          onSaved={(name) => {
            setSavingFilter(false);
            toast(t('Filter “{name}” saved', { name }));
            window.dispatchEvent(new Event(FILTERS_CHANGED));
          }} />
      )}
      {dialog && (
        <SprintDialogModal dialog={dialog} projectKey={key} averageVelocity={averageVelocity}
          plannedPoints={dialog.kind === 'create' ? 0 : (tasks ?? []).filter((t) => t.sprint?.id === dialog.sprint.id)
            .reduce((sum, t) => sum + (t.storyPoints ?? 0), 0)}
          onClose={() => setDialog(null)} onDone={(message) => {
          setDialog(null);
          toast(message);
          load();
        }} />
      )}
      {confirm && (
        <ConfirmDialog
          title={confirm.kind === 'complete' ? t('Complete {name}?', { name: confirm.sprint.name }) : t('Delete {name}?', { name: confirm.sprint.name })}
          message={confirm.kind === 'complete'
            ? t('Unfinished tasks will move back to the backlog. The sprint stays in reports.')
            : t('Its tasks will move back to the backlog.')}
          confirmLabel={confirm.kind === 'complete' ? t('Complete sprint') : t('Delete sprint')}
          danger={confirm.kind === 'delete'}
          onClose={() => setConfirm(null)}
          onConfirm={() => {
            const { kind, sprint } = confirm;
            setConfirm(null);
            runSprint(kind === 'complete' ? () => api.completeSprint(sprint.id) : () => api.deleteSprint(sprint.id),
              kind === 'complete' ? `${sprint.name} completed` : `${sprint.name} deleted`);
          }}
        />
      )}
      {navPicker}
    </div>
  );
}

function SprintMenu({ onEdit, onDelete, onAddTask }: { onEdit: () => void; onDelete?: () => void; onAddTask: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const choose = (action: () => void) => () => {
    setOpen(false);
    action();
  };
  return (
    <div className="menu" ref={ref}>
      <button className="icon-button" aria-label={t("Sprint actions")} aria-expanded={open} onClick={() => setOpen(!open)}>
        <MoreHorizontal size={18} />
      </button>
      {open && (
        <div className="menu-list" role="menu">
          <button role="menuitem" onClick={choose(onAddTask)}>{t("Add task to sprint")}</button>
          <button role="menuitem" onClick={choose(onEdit)}>{t("Edit sprint")}</button>
          {onDelete && <button role="menuitem" className="danger" onClick={choose(onDelete)}>{t("Delete sprint")}</button>}
        </div>
      )}
    </div>
  );
}

function addDays(day: string, days: number) {
  const [y, m, d] = day.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function SprintDialogModal({ dialog, projectKey, averageVelocity, plannedPoints, onClose, onDone }: {
  dialog: NonNullable<SprintDialog>;
  projectKey: string;
  averageVelocity: number | null;
  plannedPoints: number;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const sprint = dialog.kind === 'create' ? null : dialog.sprint;
  const start = sprint?.startDate ?? (dialog.kind === 'start' ? todayIso() : '');
  const [name, setName] = useState(sprint?.name ?? '');
  const [goal, setGoal] = useState(sprint?.goal ?? '');
  const [startDate, setStartDate] = useState(start);
  const [endDate, setEndDate] = useState(sprint?.endDate ?? (start ? addDays(start, 14) : ''));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    const dates = { startDate: startDate || null, endDate: endDate || null };
    try {
      if (dialog.kind === 'create') {
        const created = await api.createSprint(projectKey, { name: name.trim() || undefined, goal, ...dates });
        onDone(`${created.name} created`);
      } else if (dialog.kind === 'edit') {
        await api.updateSprint(dialog.sprint.id, { name, goal, ...dates });
        onDone(t('Sprint updated'));
      } else {
        await api.updateSprint(dialog.sprint.id, { name, goal, ...dates });
        await api.startSprint(dialog.sprint.id, dates);
        onDone(`${name || dialog.sprint.name} started`);
      }
    } catch (e) {
      setError((e as ApiError).message);
      setBusy(false);
    }
  };

  const title = dialog.kind === 'create' ? t('Create sprint') : dialog.kind === 'start' ? t('Start {name}', { name: dialog.sprint.name }) : t('Edit sprint');
  return (
    <Modal title={title} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="sprint-form" disabled={busy}>
          {dialog.kind === 'start' ? t('Start sprint') : dialog.kind === 'create' ? t('Create sprint') : t('Save')}
        </button>
      </>
    }>
      <form id="sprint-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        {dialog.kind === 'start' && (
          <p className={`capacity ${averageVelocity !== null && plannedPoints > averageVelocity * 1.1 ? 'over' : ''}`}>
            <strong>{t('{n} pts planned.', { n: plannedPoints })}</strong>{' '}
            {averageVelocity !== null
              ? t('The team finished {n} pts per sprint on average recently.', { n: averageVelocity })
              : t('No velocity yet: this is the first sprint.')}
          </p>
        )}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={name} maxLength={80} placeholder={dialog.kind === 'create' ? t('Leave empty for an automatic name') : ''}
            onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>{t("Goal")} <span className="muted">{t("(optional)")}</span></span>
          <textarea rows={2} maxLength={500} value={goal} onChange={(e) => setGoal(e.target.value)} />
        </label>
        <div className="form-grid two">
          <label className="field">
            <span>{t("Start date")}</span>
            <input type="date" value={startDate} required={dialog.kind === 'start'} onChange={(e) => {
              setStartDate(e.target.value);
              if (e.target.value && !endDate) setEndDate(addDays(e.target.value, 14));
            }} />
          </label>
          <label className="field">
            <span>{t("End date")}</span>
            <input type="date" value={endDate} min={startDate || undefined} required={dialog.kind === 'start'}
              onChange={(e) => setEndDate(e.target.value)} />
          </label>
        </div>
      </form>
    </Modal>
  );
}

function SaveFilterModal({ projectKey, query, onClose, onSaved }: {
  projectKey: string; query: string; onClose: () => void; onSaved: (name: string) => void;
}) {
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await api.saveFilter(projectKey, name.trim(), query, shared);
      onSaved(name.trim());
    } catch (e) {
      setError((e as ApiError).message);
    }
  };
  return (
    <Modal title={t("Save filter")} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="filter-form" disabled={!name.trim()}>{t("Save")}</button>
      </>
    }>
      <form id="filter-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={name} maxLength={60} placeholder={t("My open bugs")} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="toggle">
          <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
          {t('Share with everyone in the project')}
        </label>
        <p className="muted small">{t("It appears in the sidebar and in the command palette (Ctrl+K).")}</p>
      </form>
    </Modal>
  );
}
