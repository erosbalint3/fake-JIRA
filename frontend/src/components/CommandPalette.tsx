import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity, BarChart3, Bell, CalendarDays, Clock, CornerDownLeft, FileText, Filter, FolderKanban, KanbanSquare,
  LayoutDashboard, ListTodo, Map, MessageSquare, Moon, Package, Paperclip, Plus, Search, Settings, Shield, UserRound,
  UserSquare2, Users,
} from 'lucide-react';
import { filterPath } from '../filters';
import { api } from '../api';
import { useAuth } from '../auth';
import { useProjects } from '../projects';
import { useTheme } from '../theme';
import { StatusBadge } from './Badges';
import type { RecentTask, SavedFilter, TextHit } from '../types';

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
  const [hits, setHits] = useState<TextHit[]>([]);
  const [recent, setRecent] = useState<RecentTask[]>([]);
  const [filters, setFilters] = useState<SavedFilter[]>([]);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    api.recent().then(setRecent).catch(() => setRecent([]));
    if (projectKey) api.filters(projectKey).then(setFilters).catch(() => setFilters([]));
  }, [projectKey]);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setHits([]);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      try {
        const found = await api.textSearch(q, 12);
        if (!cancelled) setHits(found);
      } catch {
        if (!cancelled) setHits([]);
      }
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
        { id: 'releases', group: 'Go to', label: `${key} releases`, icon: <Package size={16} />, run: go(`/p/${key}/releases`) },
        { id: 'reports', group: 'Go to', label: `${key} reports`, icon: <BarChart3 size={16} />, run: go(`/p/${key}/reports`) },
        { id: 'settings', group: 'Go to', label: `${key} settings`, icon: <Settings size={16} />, run: go(`/p/${key}/settings`) },
      ] : []),
      { id: 'dashboard', group: 'Go to', label: 'Dashboard', icon: <LayoutDashboard size={16} />, run: go('/dashboard') },
      { id: 'search', group: 'Go to', label: 'Advanced search', icon: <Search size={16} />, run: go('/search') },
      { id: 'mywork', group: 'Go to', label: 'My work', icon: <UserSquare2 size={16} />, run: go('/my-work') },
      { id: 'calendar', group: 'Go to', label: 'Calendar', icon: <CalendarDays size={16} />, run: go('/calendar') },
      { id: 'activity', group: 'Go to', label: 'Activity', icon: <Activity size={16} />, run: go('/activity') },
      { id: 'teams', group: 'Go to', label: 'Teams', icon: <Users size={16} />, run: go('/teams') },
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
        icon: <Filter size={16} />, run: go(filterPath(key ?? '', f.query)),
      })),
    ];
    const q = query.trim().toLowerCase();
    const matching = q ? all.filter((item) => item.label.toLowerCase().includes(q)) : all;
    const hitIcon = { TASK: <FileText size={16} />, COMMENT: <MessageSquare size={16} />, ATTACHMENT: <Paperclip size={16} />,
      EPIC: <Map size={16} />, RELEASE: <Package size={16} /> };
    const hitGroup = { TASK: 'Tasks', COMMENT: 'Comments', ATTACHMENT: 'Files', EPIC: 'Epics', RELEASE: 'Releases' };
    const hitItems: Item[] = hits.map((hit) => ({
      id: `hit-${hit.kind}-${hit.id}`, group: hitGroup[hit.kind],
      label: hit.kind === 'COMMENT' || hit.kind === 'ATTACHMENT' ? (hit.snippet ?? hit.title) : hit.title,
      hint: hit.key ? <span className="task-key">{hit.key}</span> : <span className="muted">{hit.projectKey}</span>,
      icon: hitIcon[hit.kind],
      run: go(hit.taskId ? `/tasks/${hit.taskId}` : hit.kind === 'EPIC' ? `/p/${hit.projectKey}/backlog?epic=${hit.id}`
        : `/p/${hit.projectKey}/releases`),
    }));
    const recentItems: Item[] = q ? [] : recent.slice(0, 6).map((task) => ({
      id: `recent-${task.id}`, group: 'Recently viewed', label: task.title,
      hint: <><span className="task-key">{task.key}</span><StatusBadge status={task.status} /></>,
      icon: <Clock size={16} />, run: go(`/tasks/${task.id}`),
    }));
    const searchAll: Item[] = q.length >= 2 ? [{
      id: 'search-all', group: 'Search', label: `Search everything for “${query.trim()}”`, icon: <Search size={16} />,
      run: go(`/search?q=${encodeURIComponent(`text ~ "${query.trim().replace(/"/g, '')}" ORDER BY updated DESC`)}`),
    }] : [];
    return [...recentItems, ...hitItems, ...matching, ...searchAll].slice(0, 50);
  }, [query, hits, recent, filters, projects, projectKey, admin, navigate, onClose, onCreate, toggle]);

  useEffect(() => setActive(0), [query, hits.length]);

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
