import { useCallback, useEffect, useMemo, useState, type DragEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3, ChevronDown, ChevronLeft, ChevronRight, Eye, KanbanSquare, Users, Zap } from 'lucide-react';
import { api, ApiError } from '../api';
import { useTransitionGuard } from '../components/TransitionGuard';
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
import {
  PRIORITIES, PRIORITY_LABEL, PRIORITY_ORDER, TASK_TYPES, TASK_TYPE_LABEL, type BoardColumn, type Sprint, type Task,
  type User,
} from '../types';
import { t } from '../i18n';

/** The column a task shows in: its pinned column, or the first column of its status. */
function columnOf(task: Task, columns: BoardColumn[]) {
  return columns.find((c) => c.id === task.columnId && c.status === task.status)
    ?? columns.find((c) => c.status === task.status);
}

type Grouping = 'none' | 'assignee' | 'epic' | 'priority' | 'type';

const GROUPINGS: { value: Grouping; label: string }[] = [
  { value: 'none', label: 'No swimlanes' },
  { value: 'assignee', label: 'Assignee' },
  { value: 'epic', label: 'Epic' },
  { value: 'priority', label: 'Priority' },
  { value: 'type', label: 'Type' },
];

/** Finished tasks older than this drop off a Kanban board. */
const KANBAN_DONE_DAYS = 14;

interface Lane {
  id: string;
  title: ReactNode;
  tasks: Task[];
  sort: string;
  /** For assignee lanes: dropping a card here assigns it to this user (null = unassign). */
  assigneeId?: number | null;
}

function stored(key: string, fallback: string) {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

function store(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* per-viewer preference only */
  }
}

function lanesFor(tasks: Task[], grouping: Grouping): Lane[] {
  if (grouping === 'none') return [{ id: 'all', title: null, sort: '', tasks }];
  const lanes = new Map<string, Lane>();
  const add = (id: string, title: ReactNode, sort: string, task: Task, extra: Partial<Lane> = {}) => {
    const lane = lanes.get(id) ?? { id, title, sort, tasks: [], ...extra };
    lane.tasks.push(task);
    lanes.set(id, lane);
  };
  for (const task of tasks) {
    if (grouping === 'assignee') {
      const user = task.assignee;
      add(user ? `u${user.id}` : 'u-none', user
        ? <><Avatar user={user} size={20} /> {user.displayName}</>
        : <><span className="avatar-empty sm" /> Unassigned</>, user ? user.displayName.toLowerCase() : '\uffff', task, { assigneeId: user?.id ?? null });
    } else if (grouping === 'epic') {
      add(task.epic ? `e${task.epic.id}` : 'e-none', task.epic ? <EpicChip epic={task.epic} /> : 'No epic',
        task.epic ? task.epic.name.toLowerCase() : '\uffff', task);
    } else if (grouping === 'priority') {
      add(task.priority, <><PriorityBadge priority={task.priority} compact /> {PRIORITY_LABEL[task.priority]}</>,
        String(PRIORITIES.indexOf(task.priority)), task);
    } else {
      add(task.type, <><TypeIcon type={task.type} /> {TASK_TYPE_LABEL[task.type]}</>,
        String(TASK_TYPES.indexOf(task.type)), task);
    }
  }
  return [...lanes.values()].sort((a, b) => a.sort.localeCompare(b.sort));
}

export function BoardPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const { user } = useAuth();
  const toast = useToast();
  const guard = useTransitionGuard();
  const openCreate = useCreateTask();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [columns, setColumns] = useState<BoardColumn[]>([]);
  const [active, setActive] = useState<Sprint | null>(null);
  const [error, setError] = useState('');
  const [onlyMine, setOnlyMine] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [grouping, setGrouping] = useState<Grouping>(() => stored('fakejira.board.lanes', 'none') as Grouping);
  const [showWorkload, setShowWorkload] = useState(() => stored('fakejira.board.workload', '') === '1');
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const kanban = project?.kanban ?? false;

  const load = useCallback(async () => {
    if (!project) return;
    setError('');
    try {
      const [sprints, cols] = await Promise.all([project.kanban ? Promise.resolve([]) : api.sprints(key), api.columns(key)]);
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

  const shown = useMemo(() => {
    const cutoff = Date.now() - KANBAN_DONE_DAYS * 86400000;
    return (tasks ?? [])
      .filter((t) => !onlyMine || t.assignee?.id === user?.id)
      .filter((t) => !kanban || t.status !== 'DONE' || !t.completedAt || new Date(t.completedAt).getTime() >= cutoff);
  }, [tasks, onlyMine, user, kanban]);

  const lanes = useMemo(() => lanesFor(shown, grouping), [shown, grouping]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const move = async (task: Task, column: BoardColumn, isUndo = false) => {
    const from = columnOf(task, columns);
    if (from?.id === column.id) return;
    const inTarget = shown.filter((t) => columnOf(t, columns)?.id === column.id).length;
    const previous = tasks;
    setTasks((current) => current?.map((t) => (t.id === task.id ? { ...t, status: column.status, columnId: column.id } : t)) ?? null);
    try {
      const moved = await guard(task, (resolution) => api.moveToColumn(task.id, column.id, resolution));
      if (!moved) {
        setTasks(previous);
        return;
      }
      setTasks((current) => current?.map((t) => (t.id === moved.id ? moved : t)) ?? null);
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

  const reassign = async (task: Task, lane: Lane) => {
    const target = lane.assigneeId ?? null;
    if ((task.assignee?.id ?? null) === target) return;
    const member = project.members.find((m) => m.id === target) ?? null;
    if (member?.role === 'VIEWER') {
      toast(`${member.displayName} is a viewer and cannot be assigned`, 'error');
      return;
    }
    setTasks((current) => current?.map((t) => (t.id === task.id ? { ...t, assignee: member as User | null } : t)) ?? null);
    try {
      await api.assign(task.id, target);
      toast(target && member ? `${task.key} assigned to ${member.displayName}` : `${task.key} unassigned`, 'success');
    } catch (e) {
      toast((e as ApiError).message, 'error');
      load();
    }
  };

  const onDrop = async (event: DragEvent, column: BoardColumn, lane: Lane) => {
    event.preventDefault();
    setOver(null);
    // The card remounts in its new column, so its own dragend never reaches React.
    setDragging(null);
    const task = tasks?.find((t) => t.id === Number(event.dataTransfer.getData('text/plain')));
    if (!task) return;
    // One after the other: both save the whole task.
    if (grouping === 'assignee') await reassign(task, lane);
    await move(task, column);
  };

  const daysLeft = active?.endDate
    ? Math.round((new Date(`${active.endDate}T00:00:00`).getTime() - new Date(`${todayIso()}T00:00:00`).getTime()) / 86400000)
    : null;

  const changeGrouping = (value: Grouping) => {
    setGrouping(value);
    setCollapsed(new Set());
    store('fakejira.board.lanes', value);
  };

  const toggleWorkload = () => {
    setShowWorkload((v) => {
      store('fakejira.board.workload', v ? '' : '1');
      return !v;
    });
  };

  const renderCard = (task: Task, index: number) => (
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
        {grouping !== 'epic' && <EpicChip epic={task.epic} />}
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
        <span className="avatar-stack">
          {task.assignee
            ? <Avatar user={task.assignee} size={24} />
            : <span className="avatar-empty sm" title={t("Unassigned")} />}
          {task.helpers.map((h) => <Avatar key={h.id} user={h} size={20} />)}
        </span>
      </footer>
    </article>
  );

  const sortCards = (list: Task[]) =>
    [...list].sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || a.id - b.id);
  const columnTotals = new Map(columns.map((c) => [c.id, shown.filter((t) => columnOf(t, columns)?.id === c.id).length]));

  const header = (column: BoardColumn) => {
    const count = columnTotals.get(column.id) ?? 0;
    const overLimit = column.wipLimit !== null && count > column.wipLimit;
    return (
      <header className="column-header">
        <span className="column-dot" />
        <h2>{column.name}</h2>
        <span className={`count ${overLimit ? 'danger' : ''}`} title={column.wipLimit ? `Limit: ${column.wipLimit}` : undefined}>
          {count}{column.wipLimit ? ` / ${column.wipLimit}` : ''}
        </span>
      </header>
    );
  };

  const cell = (column: BoardColumn, index: number, lane: Lane, withHeader: boolean) => {
    const cards = sortCards(lane.tasks.filter((t) => columnOf(t, columns)?.id === column.id));
    const overLimit = column.wipLimit !== null && (columnTotals.get(column.id) ?? 0) > column.wipLimit;
    const dropId = `${lane.id}:${column.id}`;
    return (
      <section
        key={column.id}
        className={`column column-${column.status.toLowerCase()} ${withHeader ? '' : 'lane-cell'} ${over === dropId ? 'drop-target' : ''} ${overLimit ? 'over-limit' : ''}`}
        aria-label={withHeader ? undefined : `${column.name}`}
        onDragOver={(e) => {
          if (!canEdit) return;
          e.preventDefault();
          setOver(dropId);
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(null);
        }}
        onDrop={(e) => canEdit && onDrop(e, column, lane)}
      >
        {withHeader && header(column)}
        <div className="column-body">
          {cards.map((task) => renderCard(task, index))}
          {cards.length === 0 && withHeader && <div className="column-empty">{canEdit ? 'Drop tasks here' : 'Empty'}</div>}
        </div>
      </section>
    );
  };

  const gridStyle = { gridTemplateColumns: `repeat(${columns.length}, minmax(230px, 1fr))` };

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t("Board")}</h1>
          {kanban ? (
            <p className="sprint-banner">
              <KanbanSquare size={15} /> <strong>{t("Kanban")}</strong>
              <span className="muted">Continuous flow · finished tasks leave the board after {KANBAN_DONE_DAYS} days</span>
              <Link to={`/p/${key}/reports`} className="small">{t("Flow reports")}</Link>
            </p>
          ) : active ? (
            <p className="sprint-banner">
              <Zap size={15} /> <strong>{active.name}</strong>
              {active.startDate && active.endDate && <span className="muted">{formatDay(active.startDate)} – {formatDay(active.endDate)}</span>}
              {daysLeft !== null && (
                <span className={daysLeft < 0 ? 'overdue-text' : 'muted'}>
                  {daysLeft < 0 ? `${-daysLeft} day${daysLeft === -1 ? '' : 's'} over` : `${daysLeft} day${daysLeft === 1 ? '' : 's'} left`}
                </span>
              )}
              <Link to={`/p/${key}/reports`} className="small">{t("Burndown")}</Link>
            </p>
          ) : (
            <p className="muted">{t("No active sprint — showing every task.")} <Link to={`/p/${key}/backlog`}>{t("Plan a sprint")}</Link></p>
          )}
        </div>
        <div className="header-actions">
          {!canEdit && <span className="readonly-badge"><Eye size={13} /> {t("Read-only")}</span>}
          <select value={grouping} onChange={(e) => changeGrouping(e.target.value as Grouping)} aria-label={t("Swimlanes")}>
              {GROUPINGS.map((g) => <option key={g.value} value={g.value}>{g.value === 'none' ? t(g.label) : t('Lanes: {x}', { x: t(g.label) })}</option>)}
          </select>
          <button className={`btn btn-ghost btn-sm ${showWorkload ? 'is-on' : ''}`} onClick={toggleWorkload} aria-pressed={showWorkload}>
            <Users size={15} /> {t('Workload')}
          </button>
          <label className="toggle">
            <input type="checkbox" checked={onlyMine} onChange={(e) => setOnlyMine(e.target.checked)} /> {t('Only my tasks')}
          </label>
        </div>
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!tasks && !error && <Spinner />}
      {tasks && showWorkload && <Workload tasks={shown} />}
      {tasks && tasks.length === 0 && (
        <EmptyState icon={<KanbanSquare size={28} />} title={active ? 'This sprint has no tasks' : 'No tasks yet'}>
          {active
            ? <>Move tasks into {active.name} from the <Link to={`/p/${key}/backlog`}>{t("backlog")}</Link>.</>
            : canEdit && <button className="link" onClick={() => openCreate({ projectKey: key })}>{t("Create the first task")}</button>}
        </EmptyState>
      )}
      {tasks && tasks.length > 0 && grouping === 'none' && (
        <div className="board" style={gridStyle}>
          {columns.map((column, index) => cell(column, index, lanes[0], true))}
        </div>
      )}
      {tasks && tasks.length > 0 && grouping !== 'none' && (
        <div className="board-lanes">
          <div className="board board-head" style={gridStyle}>
            {columns.map((column) => (
              <div key={column.id} className={`column-head column-${column.status.toLowerCase()}`}>{header(column)}</div>
            ))}
          </div>
          {lanes.length === 0 && <p className="muted">{t("Nothing to show.")}</p>}
          {lanes.map((lane) => {
            const isCollapsed = collapsed.has(lane.id);
            const points = lane.tasks.reduce((sum, t) => sum + (t.storyPoints ?? 0), 0);
            return (
              <section key={lane.id} className="lane">
                <button
                  className="lane-header"
                  aria-expanded={!isCollapsed}
                  onClick={() => setCollapsed((current) => {
                    const next = new Set(current);
                    if (next.has(lane.id)) next.delete(lane.id); else next.add(lane.id);
                    return next;
                  })}
                >
                  <ChevronDown size={15} className={isCollapsed ? 'rot' : ''} />
                  <span className="lane-title">{lane.title}</span>
                  <span className="muted small">{lane.tasks.length} task{lane.tasks.length === 1 ? '' : 's'}{points ? ` · ${points} pts` : ''}</span>
                </button>
                {!isCollapsed && (
                  <div className="board lane-row" style={gridStyle}>
                    {columns.map((column, index) => cell(column, index, lane, false))}
                  </div>
                )}
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** Open tasks and points per person, to spot who is overloaded. */
function Workload({ tasks }: { tasks: Task[] }) {
  const rows = useMemo(() => {
    const byPerson = new Map<string, { user: User | null; tasks: number; points: number; inProgress: number }>();
    for (const task of tasks) {
      if (task.status === 'DONE') continue;
      const id = task.assignee ? String(task.assignee.id) : 'none';
      const row = byPerson.get(id) ?? { user: task.assignee, tasks: 0, points: 0, inProgress: 0 };
      row.tasks++;
      row.points += task.storyPoints ?? 0;
      if (task.status !== 'TODO') row.inProgress++;
      byPerson.set(id, row);
    }
    return [...byPerson.values()].sort((a, b) => (b.points - a.points) || (b.tasks - a.tasks));
  }, [tasks]);
  const assigned = rows.filter((r) => r.user);
  const average = assigned.length ? assigned.reduce((s, r) => s + r.points, 0) / assigned.length : 0;
  const max = Math.max(1, ...rows.map((r) => r.points || r.tasks));
  if (rows.length === 0) return <p className="muted workload-empty">{t("No open work.")}</p>;
  return (
    <section className="workload" aria-label={t("Workload")}>
      <h2 className="section-title"><BarChart3 size={15} /> {t("Open work per person")}</h2>
      <ul>
        {rows.map((row) => {
          const heavy = row.user && assigned.length > 1 && row.points > average * 1.5 && row.points > 0;
          return (
            <li key={row.user?.id ?? 'none'} className={heavy ? 'heavy' : ''}>
              <span className="workload-name">
                {row.user ? <><Avatar user={row.user} size={20} /> {row.user.displayName}</> : <><span className="avatar-empty sm" /> Unassigned</>}
              </span>
              <span className="workload-bar"><span style={{ width: `${((row.points || row.tasks) / max) * 100}%` }} /></span>
              <span className="workload-numbers">
                {row.points} pts · {row.tasks} task{row.tasks === 1 ? '' : 's'}{row.inProgress ? ` · ${row.inProgress} started` : ''}
                {heavy && <strong className="overdue-text"> {t("· heavy")}</strong>}
              </span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
