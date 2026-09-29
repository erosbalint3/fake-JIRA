import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BarChart3, Bell, CornerDownLeft, FileText, Filter, FolderKanban, KanbanSquare, ListTodo, Map, Moon, Plus, Search,
  Settings, Shield, UserRound, UserSquare2,
} from 'lucide-react';
import { api } from '../api';
import { useAuth } from '../auth';
import { useProjects } from '../projects';
import { useTheme } from '../theme';
import { StatusBadge } from './Badges';
import type { SavedFilter, Task } from '../types';

interface Item {
  id: string;
  group: string;
  label: string;
  hint?: ReactNode;
  icon: ReactNode;
  run: () => void;
}

interface Props {
  projectKey?: string;
  onClose: () => void;
  onCreate: () => void;
}

/** Ctrl/Cmd+K: jump to any task, project, saved filter or page, or run an action. */
export function CommandPalette({ projectKey, onClose, onCreate }: Props) {
  const navigate = useNavigate();
  const { admin } = useAuth();
  const { projects } = useProjects();
  const { toggle } = useTheme();
  const [query, setQuery] = useState('');
  const [tasks, setTasks] = useState<Task[]>([]);
  const [filters, setFilters] = useState<SavedFilter[]>([]);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    if (projectKey) api.filters(projectKey).then(setFilters).catch(() => setFilters([]));
  }, [projectKey]);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setTasks([]);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      const found: Task[] = [];
      if (/^[A-Za-z][A-Za-z0-9]*-\d+$/.test(q)) {
        try {
          found.push(await api.taskByKey(q.toUpperCase()));
        } catch {
          /* no exact match */
        }
      }
      try {
        const matches = await api.tasks({ q });
        matches.forEach((task) => !found.some((t) => t.id === task.id) && found.push(task));
      } catch {
        /* ignore */
      }
      if (!cancelled) setTasks(found.slice(0, 8));
    }, 150);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [query]);

  const items = useMemo<Item[]>(() => {
    const go = (path: string) => () => {
      onClose();
      navigate(path);
    };
    const key = projectKey;
    const all: Item[] = [
      { id: 'create', group: 'Actions', label: 'Create task', icon: <Plus size={16} />, hint: <kbd>C</kbd>,
        run: () => { onClose(); onCreate(); } },
      { id: 'theme', group: 'Actions', label: 'Toggle dark mode', icon: <Moon size={16} />, run: () => { toggle(); onClose(); } },
      ...(key ? [
        { id: 'board', group: 'Go to', label: `${key} board`, icon: <KanbanSquare size={16} />, run: go(`/p/${key}/board`) },
        { id: 'backlog', group: 'Go to', label: `${key} backlog`, icon: <ListTodo size={16} />, run: go(`/p/${key}/backlog`) },
        { id: 'roadmap', group: 'Go to', label: `${key} roadmap`, icon: <Map size={16} />, run: go(`/p/${key}/roadmap`) },
        { id: 'reports', group: 'Go to', label: `${key} reports`, icon: <BarChart3 size={16} />, run: go(`/p/${key}/reports`) },
        { id: 'settings', group: 'Go to', label: `${key} settings`, icon: <Settings size={16} />, run: go(`/p/${key}/settings`) },
      ] : []),
      { id: 'mywork', group: 'Go to', label: 'My work', icon: <UserSquare2 size={16} />, run: go('/my-work') },
      { id: 'notifications', group: 'Go to', label: 'Notifications', icon: <Bell size={16} />, run: go('/notifications') },
      { id: 'projects', group: 'Go to', label: 'All projects', icon: <FolderKanban size={16} />, run: go('/projects') },
      { id: 'profile', group: 'Go to', label: 'Profile', icon: <UserRound size={16} />, run: go('/profile') },
      ...(admin ? [{ id: 'admin', group: 'Go to', label: 'Admin', icon: <Shield size={16} />, run: go('/admin') }] : []),
      ...(projects ?? []).map((p) => ({
        id: `project-${p.key}`, group: 'Projects', label: p.name, hint: <span className="muted">{p.key}</span>,
        icon: <span className="project-icon sm">{p.key.slice(0, 2)}</span>, run: go(`/p/${p.key}/board`),
      })),
      ...filters.map((f) => ({
        id: `filter-${f.id}`, group: 'Saved filters', label: f.name, hint: f.shared ? <span className="muted">shared</span> : undefined,
        icon: <Filter size={16} />, run: go(`/p/${key}/backlog?${f.query}`),
      })),
    ];
    const q = query.trim().toLowerCase();
    const matching = q ? all.filter((item) => item.label.toLowerCase().includes(q)) : all;
    const taskItems: Item[] = tasks.map((task) => ({
      id: `task-${task.id}`, group: 'Tasks', label: task.title,
      hint: <><span className="task-key">{task.key}</span><StatusBadge status={task.status} /></>,
      icon: <FileText size={16} />, run: go(`/tasks/${task.id}`),
    }));
    return [...taskItems, ...matching].slice(0, 40);
  }, [query, tasks, filters, projects, projectKey, admin, navigate, onClose, onCreate, toggle]);

  useEffect(() => setActive(0), [query, tasks.length]);

  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((i) => Math.min(items.length - 1, i + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      items[active]?.run();
    } else if (event.key === 'Escape') {
      onClose();
    }
  };

  let lastGroup = '';
  return (
    <div className="modal-backdrop palette-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal palette" role="dialog" aria-modal="true" aria-label="Command palette" onKeyDown={onKeyDown}>
        <label className="palette-search">
          <Search size={18} />
          <input ref={inputRef} value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder="Search tasks, projects, filters and pages…" aria-label="Command palette search"
            role="combobox" aria-expanded="true" aria-controls="palette-list"
            aria-activedescendant={items[active] ? `palette-${items[active].id}` : undefined} />
          <kbd>Esc</kbd>
        </label>
        <ul className="palette-list" id="palette-list" role="listbox" ref={listRef}>
          {items.length === 0 && <li className="palette-empty muted">No results for “{query}”.</li>}
          {items.map((item, index) => {
            const heading = item.group !== lastGroup ? item.group : null;
            lastGroup = item.group;
            return (
              <li key={item.id} role="presentation">
                {heading && <div className="palette-group">{heading}</div>}
                <button id={`palette-${item.id}`} role="option" aria-selected={index === active} data-index={index}
                  className={`palette-item ${index === active ? 'active' : ''}`}
                  onMouseMove={() => setActive(index)} onClick={item.run}>
                  <span className="palette-icon">{item.icon}</span>
                  <span className="palette-label">{item.label}</span>
                  <span className="palette-hint">{item.hint}</span>
                  {index === active && <CornerDownLeft size={14} className="muted" />}
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
