import { useCallback, useEffect, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDown, ArrowUp, Table2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { SavedViews } from '../components/SavedViews';
import { useTaskMenu } from '../components/ContextMenu';
import type { Epic, Priority, Sprint, Status, Task, TaskInput, TaskType } from '../types';
import { isReadOnlyRole } from '../types';
import { t } from '../i18n';

type ColumnId = 'key' | 'title' | 'type' | 'status' | 'priority' | 'assignee' | 'points' | 'due' | 'labels' | 'epic' | 'sprint';
type Kind = 'readonly' | 'text' | 'number' | 'date' | 'select';

interface Column {
  id: ColumnId;
  label: string;
  kind: Kind;
  width: number;
}

const COLUMNS: Column[] = [
  { id: 'key', label: 'Key', kind: 'readonly', width: 90 },
  { id: 'title', label: 'Title', kind: 'text', width: 320 },
  { id: 'type', label: 'Type', kind: 'select', width: 100 },
  { id: 'status', label: 'Status', kind: 'select', width: 120 },
  { id: 'priority', label: 'Priority', kind: 'select', width: 100 },
  { id: 'assignee', label: 'Assignee', kind: 'select', width: 150 },
  { id: 'points', label: 'Points', kind: 'number', width: 80 },
  { id: 'due', label: 'Due date', kind: 'date', width: 130 },
  { id: 'labels', label: 'Labels', kind: 'text', width: 170 },
  { id: 'epic', label: 'Epic', kind: 'select', width: 150 },
  { id: 'sprint', label: 'Sprint', kind: 'select', width: 140 },
];

const STATUS_LABEL: Record<Status, string> = { TODO: 'To do', IN_PROGRESS: 'In progress', IN_REVIEW: 'In review', DONE: 'Done' };
const TYPES: TaskType[] = ['TASK', 'BUG', 'STORY', 'SPIKE'];
const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const titleCase = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace('_', ' ');

interface ViewState {
  filter: string;
  sort: { column: ColumnId; desc: boolean } | null;
  hidden: ColumnId[];
}

function inputOf(task: Task): TaskInput {
  return {
    title: task.title, description: task.description, priority: task.priority, dueDate: task.dueDate, labels: task.labels,
    storyPoints: task.storyPoints, epicId: task.epic?.id ?? null, type: task.type,
  };
}

/** Every task of the project as a spreadsheet: arrow keys to move, Enter or typing to edit, paste to fill or add rows. */
export function TablePage() {
  const { project, loading, canEdit } = useRouteProject();
  const toast = useToast();
  const openTaskMenu = useTaskMenu();
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [epics, setEpics] = useState<Epic[]>([]);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [error, setError] = useState('');
  const [view, setView] = useState<ViewState>({ filter: '', sort: null, hidden: [] });
  const [cell, setCell] = useState<{ row: number; col: number }>({ row: 0, col: 1 });
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [newTitle, setNewTitle] = useState('');
  const grid = useRef<HTMLTableElement>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.tasks({ project: project.key }).then(setTasks).catch((e: ApiError) => setError(e.message));
    api.epics(project.key).then(setEpics).catch(() => {});
    if (!project.kanban) api.sprints(project.key).then((s) => setSprints(s.filter((x) => x.state !== 'COMPLETED'))).catch(() => {});
  }, [project]);
  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id, load, 600);

  const members = (project?.members ?? []).filter((m) => !isReadOnlyRole(m.role));
  const columns = COLUMNS.filter((c) => !view.hidden.includes(c.id) && (c.id !== 'sprint' || !project?.kanban));

  const text = useCallback((task: Task, id: ColumnId): string => {
    switch (id) {
      case 'key': return task.key;
      case 'title': return task.title;
      case 'type': return t(titleCase(task.type));
      case 'status': return t(STATUS_LABEL[task.status]);
      case 'priority': return t(titleCase(task.priority));
      case 'assignee': return task.assignee?.displayName ?? '';
      case 'points': return task.storyPoints == null ? '' : String(task.storyPoints);
      case 'due': return task.dueDate ?? '';
      case 'labels': return task.labels.join(', ');
      case 'epic': return task.epic?.name ?? '';
      case 'sprint': return task.sprint?.name ?? '';
    }
  }, []);

  const rows = useMemo(() => {
    if (!tasks) return [];
    const words = view.filter.toLowerCase().split(/\s+/).filter(Boolean);
    const list = tasks.filter((task) => words.every((w) => COLUMNS.some((c) => text(task, c.id).toLowerCase().includes(w))));
    if (view.sort) {
      const { column, desc } = view.sort;
      const value = (task: Task): string | number => column === 'points' ? task.storyPoints ?? -1
        : column === 'priority' ? PRIORITIES.indexOf(task.priority) : column === 'key' ? Number(task.key.split('-')[1]) : text(task, column).toLowerCase();
      list.sort((a, b) => {
        const x = value(a);
        const y = value(b);
        return (x < y ? -1 : x > y ? 1 : 0) * (desc ? -1 : 1);
      });
    }
    return list;
  }, [tasks, view, text]);

  /** Saves one cell; returns false when the value was not accepted. */
  const commit = async (task: Task, id: ColumnId, value: string): Promise<boolean> => {
    const v = value.trim();
    try {
      let updated: Task | null = null;
      switch (id) {
        case 'title':
          if (!v) return false;
          updated = await api.updateTask(task.id, { ...inputOf(task), title: v });
          break;
        case 'points': {
          const n = v === '' ? null : Number(v);
          if (n !== null && (!Number.isInteger(n) || n < 0 || n > 100)) throw new ApiError(400, t('Points are a whole number from 0 to 100'));
          updated = await api.updateTask(task.id, { ...inputOf(task), storyPoints: n });
          break;
        }
        case 'due':
          if (v && !/^\d{4}-\d{2}-\d{2}$/.test(v)) throw new ApiError(400, t('Use a date like 2026-10-31'));
          updated = await api.updateTask(task.id, { ...inputOf(task), dueDate: v || null });
          break;
        case 'labels':
          updated = await api.updateTask(task.id, { ...inputOf(task), labels: v.split(',').map((l) => l.trim()).filter(Boolean).slice(0, 10) });
          break;
        case 'type':
          updated = await api.updateTask(task.id, { ...inputOf(task), type: v as TaskType });
          break;
        case 'priority':
          updated = await api.updateTask(task.id, { ...inputOf(task), priority: v as Priority });
          break;
        case 'epic':
          updated = await api.updateTask(task.id, { ...inputOf(task), epicId: v ? Number(v) : null });
          break;
        case 'status':
          updated = await api.setStatus(task.id, v as Status);
          break;
        case 'assignee':
          updated = await api.assign(task.id, v ? Number(v) : null);
          break;
        case 'sprint':
          await api.bulk([task.id], v ? { sprintId: Number(v) } : { clearSprint: true });
          load();
          return true;
        default:
          return false;
      }
      if (updated) setTasks((list) => list?.map((x) => (x.id === updated!.id ? updated! : x)) ?? null);
      return true;
    } catch (e) {
      toast((e as ApiError).message, 'error');
      return false;
    }
  };

  const cellId = (row: number, col: number) => `cell-${row}-${col}`;
  const focusCell = (row: number, col: number) => {
    const r = Math.max(0, Math.min(rows.length - 1, row));
    const c = Math.max(0, Math.min(columns.length - 1, col));
    setCell({ row: r, col: c });
    requestAnimationFrame(() => document.getElementById(cellId(r, c))?.focus());
  };

  const startEdit = (row: number, col: number, initial?: string) => {
    const task = rows[row];
    const column = columns[col];
    if (!canEdit || !task || column.kind === 'readonly') return;
    setEditing(`${row}:${col}`);
    const current = column.id === 'assignee' ? String(task.assignee?.id ?? '')
      : column.id === 'epic' ? String(task.epic?.id ?? '') : column.id === 'sprint' ? String(task.sprint?.id ?? '')
      : column.id === 'type' ? task.type : column.id === 'priority' ? task.priority : column.id === 'status' ? task.status
      : text(task, column.id);
    setDraft(initial ?? current);
  };

  const finishEdit = async (move: 'down' | 'right' | 'none', value = draft) => {
    if (!editing) return;
    const [row, col] = editing.split(':').map(Number);
    setEditing(null);
    const ok = await commit(rows[row], columns[col].id, value);
    if (ok && move === 'down') focusCell(row + 1, col);
    else if (ok && move === 'right') focusCell(row, col + 1);
    else focusCell(row, col);
  };

  const onGridKey = (event: KeyboardEvent) => {
    if (editing) return;
    const { row, col } = cell;
    const moves: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
    if (moves[event.key]) {
      event.preventDefault();
      focusCell(row + moves[event.key][0], col + moves[event.key][1]);
    } else if (event.key === 'Tab') {
      event.preventDefault();
      focusCell(row, col + (event.shiftKey ? -1 : 1));
    } else if (event.key === 'Enter' || event.key === 'F2') {
      event.preventDefault();
      startEdit(row, col);
    } else if ((event.key === 'Delete' || event.key === 'Backspace') && ['points', 'due', 'labels', 'epic', 'assignee', 'sprint'].includes(columns[col]?.id)) {
      event.preventDefault();
      commit(rows[row], columns[col].id, '');
    } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey
      && ['text', 'number', 'date'].includes(columns[col]?.kind)) {
      event.preventDefault();
      startEdit(row, col, event.key);
    } else if ((event.ctrlKey || event.metaKey) && event.key === 'c') {
      const value = rows[row] ? text(rows[row], columns[col].id) : '';
      navigator.clipboard?.writeText(value).catch(() => {});
    }
  };

  const onGridPaste = (event: ClipboardEvent) => {
    if (editing || !canEdit) return;
    const value = event.clipboardData.getData('text/plain').replace(/\r?\n$/, '');
    const column = columns[cell.col];
    const task = rows[cell.row];
    if (!task || !['text', 'number', 'date'].includes(column.kind) || value.includes('\n')) return;
    event.preventDefault();
    commit(task, column.id, value);
  };

  /** Adds tasks: one per line when several lines are pasted. */
  const addTasks = async (titles: string[]) => {
    if (!project) return;
    let added = 0;
    for (const title of titles.map((s) => s.trim()).filter(Boolean).slice(0, 100)) {
      try {
        await api.createTask({ projectKey: project.key, title: title.slice(0, 120), description: '', priority: 'MEDIUM', dueDate: null,
          labels: [], storyPoints: null, epicId: null, type: 'TASK', assigneeId: null, sprintId: null });
        added++;
      } catch (e) {
        toast((e as ApiError).message, 'error');
        break;
      }
    }
    if (added) {
      toast(added === 1 ? t('Task added') : t('{n} tasks added', { n: added }));
      setNewTitle('');
      load();
    }
  };

  if (loading) return <div className="page"><Spinner /></div>;
  if (!project) return <NotFoundPage />;

  const editor = (task: Task, column: Column) => {
    const done = { onBlur: () => finishEdit('none'), autoFocus: true, 'aria-label': `${t(column.label)} ${task.key}` };
    const keys = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        setEditing(null);
        focusCell(cell.row, cell.col);
      } else if (e.key === 'Enter') {
        e.preventDefault();
        finishEdit('down');
      } else if (e.key === 'Tab') {
        e.preventDefault();
        finishEdit('right');
      }
    };
    if (column.kind === 'select') {
      const options: [string, string][] = column.id === 'type' ? TYPES.map((x) => [x, t(titleCase(x))])
        : column.id === 'priority' ? PRIORITIES.map((x) => [x, t(titleCase(x))])
        : column.id === 'status' ? (Object.keys(STATUS_LABEL) as Status[]).map((x) => [x, t(STATUS_LABEL[x])])
        : column.id === 'assignee' ? [['', t('Unassigned')], ...members.map((m): [string, string] => [String(m.id), m.displayName])]
        : column.id === 'epic' ? [['', t('No epic')], ...epics.map((e): [string, string] => [String(e.id), e.name])]
        : [['', t('Backlog')], ...sprints.map((s): [string, string] => [String(s.id), s.name])];
      return (
        <select className="cell-editor" value={draft} {...done} onKeyDown={keys}
          onChange={(e) => {
            setDraft(e.target.value);
            finishEdit('none', e.target.value);
          }}>
          {options.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
        </select>
      );
    }
    return (
      <input className="cell-editor" type={column.kind === 'number' ? 'number' : column.kind === 'date' ? 'date' : 'text'}
        value={draft} maxLength={column.id === 'title' ? 120 : 300} {...done} onKeyDown={keys}
        onChange={(e) => setDraft(e.target.value)} />
    );
  };

  return (
    <div className="page page-wide table-page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1><Table2 size={22} aria-hidden /> {t('Table')}</h1>
          <p className="muted small">{t('Arrow keys move, Enter or typing edits, Tab moves right, Esc cancels, paste fills a cell. Right-click a row for more.')}</p>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      <div className="table-toolbar">
        <input type="search" value={view.filter} placeholder={t('Filter rows…')} aria-label={t('Filter rows')}
          onChange={(e) => setView({ ...view, filter: e.target.value })} />
        <SavedViews scope={`table:${project.key}`} current={view} onApply={setView} />
        <details className="column-chooser">
          <summary className="btn btn-ghost btn-sm">{t('Columns')}</summary>
          <div className="popover">
            {COLUMNS.filter((c) => c.id !== 'key' && c.id !== 'title').map((c) => (
              <label key={c.id} className="toggle">
                <input type="checkbox" checked={!view.hidden.includes(c.id)}
                  onChange={(e) => setView({ ...view, hidden: e.target.checked ? view.hidden.filter((h) => h !== c.id) : [...view.hidden, c.id] })} />
                {t(c.label)}
              </label>
            ))}
          </div>
        </details>
        <span className="muted small">{t('{n} tasks', { n: rows.length })}</span>
      </div>
      {!tasks ? <Spinner /> : (
        <div className="sheet-wrap">
          <table className="sheet" role="grid" aria-label={t('Tasks of {project}', { project: project.name })} ref={grid}
            aria-rowcount={rows.length + 1} onKeyDown={onGridKey} onPaste={onGridPaste}>
            <thead>
              <tr>
                {columns.map((c) => {
                  const sorted = view.sort?.column === c.id ? view.sort : null;
                  return (
                    <th key={c.id} style={{ width: c.width }} scope="col"
                      aria-sort={sorted ? (sorted.desc ? 'descending' : 'ascending') : 'none'}>
                      <button className="sheet-sort" onClick={() => setView({ ...view,
                        sort: !sorted ? { column: c.id, desc: false } : !sorted.desc ? { column: c.id, desc: true } : null })}>
                        {t(c.label)} {sorted && (sorted.desc ? <ArrowDown size={12} aria-hidden /> : <ArrowUp size={12} aria-hidden />)}
                      </button>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {rows.map((task, r) => (
                <tr key={task.id} onContextMenu={(e) => openTaskMenu(e, task, canEdit)}>
                  {columns.map((c, ci) => {
                    const active = cell.row === r && cell.col === ci;
                    const isEditing = editing === `${r}:${ci}`;
                    return (
                      <td key={c.id} id={cellId(r, ci)} role="gridcell" tabIndex={active ? 0 : -1}
                        className={`${active ? 'active' : ''} ${c.kind === 'readonly' ? 'readonly' : ''} cell-${c.id}`}
                        aria-readonly={!canEdit || c.kind === 'readonly'}
                        onClick={() => setCell({ row: r, col: ci })}
                        onDoubleClick={() => startEdit(r, ci)}>
                        {isEditing ? editor(task, c)
                          : c.id === 'key' ? <Link to={`/tasks/${task.id}`} tabIndex={-1}>{task.key}</Link>
                          : c.id === 'status' ? <span className={`status status-${task.status.toLowerCase()}`}>{text(task, c.id)}</span>
                          : text(task, c.id)}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
          {canEdit && (
            <div className="sheet-new">
              <input value={newTitle} placeholder={t('+ New task — paste several lines to add many')} aria-label={t('New task title')}
                maxLength={5000}
                onChange={(e) => setNewTitle(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && newTitle.trim()) addTasks([newTitle]);
                }}
                onPaste={(e) => {
                  const lines = e.clipboardData.getData('text/plain').split(/\r?\n/).filter((l) => l.trim());
                  if (lines.length > 1) {
                    e.preventDefault();
                    addTasks(lines);
                  }
                }} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
