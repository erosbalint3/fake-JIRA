import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from 'react';
import { CalendarRange, Inbox, MoreHorizontal, Plus, Search, Zap } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useFocusSearch } from '../shortcuts';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { useCreateTask } from '../components/Layout';
import { ConfirmDialog, Modal } from '../components/Modal';
import { TaskRow } from '../components/TaskRow';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import { PRIORITIES, PRIORITY_LABEL, PRIORITY_ORDER, type Priority, type Sprint, type Task } from '../types';

type SprintDialog = { kind: 'edit' | 'start'; sprint: Sprint } | { kind: 'create' } | null;

export function BacklogPage() {
  const { key, project, loading } = useRouteProject();
  const { user } = useAuth();
  const toast = useToast();
  const openCreate = useCreateTask();
  const searchRef = useRef<HTMLInputElement>(null);
  useFocusSearch(searchRef);

  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [assignee, setAssignee] = useState('');
  const [priority, setPriority] = useState<Priority | ''>('');
  const [label, setLabel] = useState('');
  const [showDone, setShowDone] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const [dialog, setDialog] = useState<SprintDialog>(null);
  const [confirm, setConfirm] = useState<{ kind: 'complete' | 'delete'; sprint: Sprint } | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    setError('');
    Promise.all([api.tasks({ project: key }), api.sprints(key)])
      .then(([t, s]) => {
        setTasks(t);
        setSprints(s);
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
      .filter((t) => !q || t.title.toLowerCase().includes(q) || t.key.toLowerCase().includes(q)
        || t.description.toLowerCase().includes(q))
      .filter((t) => !priority || t.priority === priority)
      .filter((t) => !label || t.labels.includes(label))
      .filter((t) => !assignee
        || (assignee === 'none' ? !t.assignee : t.assignee?.id === Number(assignee === 'me' ? user?.id : assignee)))
      .sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || a.id - b.id);
  }, [tasks, query, priority, label, assignee, user]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const openSprints = sprints.filter((s) => s.state !== 'COMPLETED')
    .sort((a, b) => (a.state === 'ACTIVE' ? -1 : b.state === 'ACTIVE' ? 1 : a.id - b.id));
  const hasActive = openSprints.some((s) => s.state === 'ACTIVE');
  const backlog = visible.filter((t) => !t.sprint && (showDone || t.status !== 'DONE'));
  const filtered = !!(query || priority || label || assignee);

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

  const dropProps = (id: string, sprintId: number | null) => ({
    onDragOver: (e: DragEvent) => {
      e.preventDefault();
      setDropTarget(id);
    },
    onDragLeave: (e: DragEvent<HTMLElement>) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node)) setDropTarget(null);
    },
    onDrop: (e: DragEvent) => onDrop(e, sprintId),
  });

  const row = (task: Task) => (
    <TaskRow key={task.id} task={task} className={dragging === task.id ? 'dragging' : ''}
      rowProps={{
        draggable: true,
        onDragStart: (e) => {
          e.dataTransfer.setData('text/plain', String(task.id));
          e.dataTransfer.effectAllowed = 'move';
          setDragging(task.id);
        },
        onDragEnd: () => setDragging(null),
      }}
      actions={
        <select className="move-select" value={task.sprint?.id ?? ''} aria-label={`Move ${task.key}`}
          onChange={(e) => move(task, e.target.value ? Number(e.target.value) : null)}>
          <option value="">Backlog</option>
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
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Backlog</h1>
          <p className="muted">Plan sprints by dragging tasks between sections.</p>
        </div>
        <div className="header-actions">
          <button className="btn btn-ghost" onClick={() => setDialog({ kind: 'create' })}>
            <CalendarRange size={17} /> Create sprint
          </button>
          <button className="btn btn-primary" onClick={() => openCreate({ projectKey: key })}>
            <Plus size={18} /> Create task
          </button>
        </div>
      </header>

      <div className="toolbar">
        <label className="search">
          <Search size={16} />
          <input ref={searchRef} placeholder="Search tasks  ( / )" value={query}
            onChange={(e) => setQuery(e.target.value)} aria-label="Search tasks" />
        </label>
        <select value={assignee} onChange={(e) => setAssignee(e.target.value)} aria-label="Assignee">
          <option value="">Anyone</option>
          <option value="me">Assigned to me</option>
          <option value="none">Unassigned</option>
          {project.members.map((m) => <option key={m.id} value={m.id}>{m.username}</option>)}
        </select>
        <select value={priority} onChange={(e) => setPriority(e.target.value as Priority | '')} aria-label="Priority">
          <option value="">Any priority</option>
          {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
        </select>
        <select value={label} onChange={(e) => setLabel(e.target.value)} aria-label="Label">
          <option value="">Any label</option>
          {labels.map((l) => <option key={l} value={l}>{l}</option>)}
        </select>
      </div>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}

      {tasks && openSprints.map((sprint) => {
        const items = visible.filter((t) => t.sprint?.id === sprint.id);
        const done = items.filter((t) => t.status === 'DONE').length;
        return (
          <section key={sprint.id} className={`sprint-section ${dropTarget === `s${sprint.id}` ? 'drop-target' : ''}`}
            {...dropProps(`s${sprint.id}`, sprint.id)}>
            <header className="sprint-header">
              <div className="sprint-title">
                {sprint.state === 'ACTIVE' && <Zap size={16} className="sprint-active-icon" />}
                <h2>{sprint.name}</h2>
                <span className={`sprint-state state-${sprint.state.toLowerCase()}`}>{sprint.state === 'ACTIVE' ? 'Active' : 'Planned'}</span>
                {sprint.startDate && sprint.endDate && (
                  <span className="muted small">{formatDay(sprint.startDate)} – {formatDay(sprint.endDate)}</span>
                )}
                <span className="muted small">{items.length} task{items.length === 1 ? '' : 's'}{items.length ? ` · ${done} done` : ''}</span>
              </div>
              <div className="sprint-actions">
                {sprint.state === 'PLANNED' && (
                  <button className="btn btn-soft btn-sm" disabled={hasActive}
                    title={hasActive ? 'Complete the active sprint first' : undefined}
                    onClick={() => setDialog({ kind: 'start', sprint })}>Start sprint</button>
                )}
                {sprint.state === 'ACTIVE' && (
                  <button className="btn btn-soft btn-sm" onClick={() => setConfirm({ kind: 'complete', sprint })}>Complete sprint</button>
                )}
                <SprintMenu
                  onEdit={() => setDialog({ kind: 'edit', sprint })}
                  onDelete={sprint.state === 'PLANNED' ? () => setConfirm({ kind: 'delete', sprint }) : undefined}
                  onAddTask={() => openCreate({ projectKey: key, sprintId: sprint.id })}
                />
              </div>
            </header>
            {sprint.goal && <p className="sprint-goal muted">{sprint.goal}</p>}
            {items.length ? <ul className="task-list">{items.map(row)}</ul>
              : <div className="column-empty">{filtered ? 'No matching tasks.' : 'Drag tasks here to plan this sprint.'}</div>}
          </section>
        );
      })}

      {tasks && (
        <section className={`sprint-section backlog-section ${dropTarget === 'backlog' ? 'drop-target' : ''}`}
          {...dropProps('backlog', null)}>
          <header className="sprint-header">
            <div className="sprint-title">
              <h2>Backlog</h2>
              <span className="muted small">{backlog.length} task{backlog.length === 1 ? '' : 's'}</span>
            </div>
            <label className="toggle small">
              <input type="checkbox" checked={showDone} onChange={(e) => setShowDone(e.target.checked)} /> Show done
            </label>
          </header>
          {backlog.length ? <ul className="task-list">{backlog.map(row)}</ul> : (
            <EmptyState icon={<Inbox size={28} />} title={filtered ? 'No matching tasks' : 'The backlog is empty'}>
              {filtered ? 'Try a different filter.' : <>Everything is planned. <button className="link" onClick={() => openCreate({ projectKey: key })}>Create a task</button></>}
            </EmptyState>
          )}
        </section>
      )}

      {dialog && (
        <SprintDialogModal dialog={dialog} projectKey={key} onClose={() => setDialog(null)} onDone={(message) => {
          setDialog(null);
          toast(message);
          load();
        }} />
      )}
      {confirm && (
        <ConfirmDialog
          title={confirm.kind === 'complete' ? `Complete ${confirm.sprint.name}?` : `Delete ${confirm.sprint.name}?`}
          message={confirm.kind === 'complete'
            ? 'Unfinished tasks will move back to the backlog. The sprint stays in reports.'
            : 'Its tasks will move back to the backlog.'}
          confirmLabel={confirm.kind === 'complete' ? 'Complete sprint' : 'Delete sprint'}
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
      <button className="icon-button" aria-label="Sprint actions" aria-expanded={open} onClick={() => setOpen(!open)}>
        <MoreHorizontal size={18} />
      </button>
      {open && (
        <div className="menu-list" role="menu">
          <button role="menuitem" onClick={choose(onAddTask)}>Add task to sprint</button>
          <button role="menuitem" onClick={choose(onEdit)}>Edit sprint</button>
          {onDelete && <button role="menuitem" className="danger" onClick={choose(onDelete)}>Delete sprint</button>}
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

function SprintDialogModal({ dialog, projectKey, onClose, onDone }: {
  dialog: NonNullable<SprintDialog>;
  projectKey: string;
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
        onDone('Sprint updated');
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

  const title = dialog.kind === 'create' ? 'Create sprint' : dialog.kind === 'start' ? `Start ${dialog.sprint.name}` : 'Edit sprint';
  return (
    <Modal title={title} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" form="sprint-form" disabled={busy}>
          {dialog.kind === 'start' ? 'Start sprint' : dialog.kind === 'create' ? 'Create sprint' : 'Save'}
        </button>
      </>
    }>
      <form id="sprint-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>Name</span>
          <input value={name} maxLength={80} placeholder={dialog.kind === 'create' ? 'Leave empty for an automatic name' : ''}
            onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>Goal <span className="muted">(optional)</span></span>
          <textarea rows={2} maxLength={500} value={goal} onChange={(e) => setGoal(e.target.value)} />
        </label>
        <div className="form-grid two">
          <label className="field">
            <span>Start date</span>
            <input type="date" value={startDate} required={dialog.kind === 'start'} onChange={(e) => {
              setStartDate(e.target.value);
              if (e.target.value && !endDate) setEndDate(addDays(e.target.value, 14));
            }} />
          </label>
          <label className="field">
            <span>End date</span>
            <input type="date" value={endDate} min={startDate || undefined} required={dialog.kind === 'start'}
              onChange={(e) => setEndDate(e.target.value)} />
          </label>
        </div>
      </form>
    </Modal>
  );
}
