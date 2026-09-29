import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import {
  BarChart3, Bell, Check, ChevronsUpDown, Filter, FolderKanban, Keyboard, KanbanSquare, ListTodo, LogOut, Map, Menu,
  Moon, Plus, Search, Settings, Shield, Sun, UserRound, UserSquare2, X,
} from 'lucide-react';
import { api } from '../api';
import { useAuth } from '../auth';
import { useLive, useLiveRefresh } from '../live';
import { useProjects } from '../projects';
import { SHORTCUTS, useShortcuts } from '../shortcuts';
import { useTheme } from '../theme';
import { useToast } from '../toast';
import { Avatar } from './Avatar';
import { Logo } from './Logo';
import { CommandPalette } from './CommandPalette';
import { Modal } from './Modal';
import { TaskFormModal } from './TaskFormModal';
import { useProjectAccess } from '../useProject';
import type { SavedFilter } from '../types';

export const NOTIFICATIONS_CHANGED = 'fakejira:notifications-changed';
export const FILTERS_CHANGED = 'fakejira:filters-changed';

interface CreateDefaults {
  projectKey?: string;
  sprintId?: number | null;
  parent?: { id: number; key: string };
}

const CreateTaskContext = createContext<(defaults?: CreateDefaults) => void>(() => {});
export const useCreateTask = () => useContext(CreateTaskContext);

export function Layout() {
  const { user, admin, logout } = useAuth();
  const { theme, toggle } = useTheme();
  const { connected } = useLive();
  const { projects, byKey, lastKey, remember } = useProjects();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const match = useMatch('/p/:key/*');
  const currentProject = byKey(match?.params.key) ?? byKey(lastKey() ?? undefined);
  const [unread, setUnread] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [creating, setCreating] = useState<CreateDefaults | null>(null);
  const [showHelp, setShowHelp] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [filters, setFilters] = useState<SavedFilter[]>([]);
  const switcherRef = useRef<HTMLDivElement>(null);
  const { canEdit } = useProjectAccess(currentProject);

  const currentKey = currentProject?.key;
  const refreshFilters = useCallback(() => {
    if (!currentKey) return setFilters([]);
    api.filters(currentKey).then(setFilters).catch(() => setFilters([]));
  }, [currentKey]);

  useEffect(() => {
    refreshFilters();
    window.addEventListener(FILTERS_CHANGED, refreshFilters);
    return () => window.removeEventListener(FILTERS_CHANGED, refreshFilters);
  }, [refreshFilters]);

  // Ctrl/Cmd+K opens the command palette from anywhere, even while typing.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  const refreshUnread = useCallback(() => {
    api.unreadCount().then(setUnread).catch(() => {});
  }, []);

  useEffect(() => {
    refreshUnread();
    setMenuOpen(false);
    setSwitcherOpen(false);
  }, [location.pathname, refreshUnread]);

  useEffect(() => {
    if (match?.params.key && byKey(match.params.key)) remember(match.params.key.toUpperCase());
  }, [match?.params.key, byKey, remember]);

  useEffect(() => {
    window.addEventListener(NOTIFICATIONS_CHANGED, refreshUnread);
    // Fallback polling in case the live stream is unavailable (e.g. blocked by a proxy).
    const interval = window.setInterval(() => !connected && refreshUnread(), 60000);
    return () => {
      window.removeEventListener(NOTIFICATIONS_CHANGED, refreshUnread);
      window.clearInterval(interval);
    };
  }, [refreshUnread, connected]);

  useLiveRefresh((message) => message.type === 'notification', refreshUnread, 100);

  useEffect(() => {
    if (!switcherOpen) return;
    const close = (event: MouseEvent) => {
      if (!switcherRef.current?.contains(event.target as Node)) setSwitcherOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [switcherOpen]);

  const openCreate = useCallback((defaults: CreateDefaults = {}) => {
    if (projects && projects.length === 0) {
      toast('Create a project first', 'error');
      navigate('/projects');
      return;
    }
    setCreating(defaults);
  }, [projects, toast, navigate]);

  useShortcuts({
    create: () => openCreate({ projectKey: currentProject?.key }),
    help: () => setShowHelp(true),
    go: (target) => {
      const key = currentProject?.key;
      const paths = {
        b: key ? `/p/${key}/board` : '/projects',
        k: key ? `/p/${key}/backlog` : '/projects',
        r: key ? `/p/${key}/reports` : '/projects',
        o: key ? `/p/${key}/roadmap` : '/projects',
        m: '/my-work',
        n: '/notifications',
        p: '/projects',
      };
      navigate(paths[target]);
    },
  });

  if (!user) return null;

  return (
    <CreateTaskContext.Provider value={openCreate}>
      <div className={`shell ${menuOpen ? 'menu-open' : ''}`}>
        <header className="mobile-bar">
          <button className="icon-button" onClick={() => setMenuOpen(!menuOpen)} aria-label="Toggle menu">
            {menuOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
          <Logo />
          <NavLink to="/notifications" className="icon-button bell" aria-label="Notifications">
            <Bell size={20} />
            {unread > 0 && <span className="dot" />}
          </NavLink>
        </header>

        <aside className="sidebar">
          <div className="sidebar-brand"><Logo /></div>

          <div className="switcher" ref={switcherRef}>
            <button className="switcher-button" onClick={() => setSwitcherOpen(!switcherOpen)}
              aria-haspopup="listbox" aria-expanded={switcherOpen}>
              {currentProject ? (
                <>
                  <span className="project-icon">{currentProject.key.slice(0, 2)}</span>
                  <span className="switcher-text">
                    <strong>{currentProject.name}</strong>
                    <span className="muted">{currentProject.key}</span>
                  </span>
                </>
              ) : (
                <span className="switcher-text"><strong>No project</strong><span className="muted">Create one to start</span></span>
              )}
              <ChevronsUpDown size={16} className="muted" />
            </button>
            {switcherOpen && (
              <div className="switcher-menu" role="listbox">
                {projects?.map((project) => (
                  <Link key={project.key} to={`/p/${project.key}/board`} className="switcher-item" role="option"
                    aria-selected={project.key === currentProject?.key}>
                    <span className="project-icon sm">{project.key.slice(0, 2)}</span>
                    <span className="switcher-item-name">{project.name}</span>
                    {project.key === currentProject?.key && <Check size={15} />}
                  </Link>
                ))}
                <Link to="/projects?new=1" className="switcher-item create"><Plus size={15} /> New project</Link>
              </div>
            )}
          </div>

          <button className="search-button" onClick={() => setPaletteOpen(true)}>
            <Search size={16} /> <span className="search-button-text">Search…</span> <kbd>{navigator.platform.includes('Mac') ? '⌘' : 'Ctrl'} K</kbd>
          </button>
          {(!currentProject || canEdit) && (
            <button className="btn btn-primary btn-block create-button" onClick={() => openCreate({ projectKey: currentProject?.key })}>
              <Plus size={17} /> Create task <kbd>C</kbd>
            </button>
          )}

          <nav className="nav">
            {currentProject && (
              <>
                <span className="nav-heading">{currentProject.key}</span>
                <NavLink to={`/p/${currentProject.key}/board`} className="nav-link"><KanbanSquare size={18} /> Board</NavLink>
                <NavLink to={`/p/${currentProject.key}/backlog`} className="nav-link"><ListTodo size={18} /> Backlog</NavLink>
                <NavLink to={`/p/${currentProject.key}/roadmap`} className="nav-link"><Map size={18} /> Roadmap</NavLink>
                <NavLink to={`/p/${currentProject.key}/reports`} className="nav-link"><BarChart3 size={18} /> Reports</NavLink>
                <NavLink to={`/p/${currentProject.key}/settings`} className="nav-link"><Settings size={18} /> Settings</NavLink>
                {filters.length > 0 && (
                  <>
                    <span className="nav-heading">Saved filters</span>
                    {filters.map((f) => (
                      <Link key={f.id} to={`/p/${currentProject.key}/backlog?${f.query}`} className="nav-link nav-filter"
                        title={f.shared ? `Shared by ${f.owner}` : 'Only visible to you'}>
                        <Filter size={15} /> <span className="nav-filter-name">{f.name}</span>
                      </Link>
                    ))}
                  </>
                )}
              </>
            )}
            <span className="nav-heading">Workspace</span>
            <NavLink to="/my-work" className="nav-link"><UserSquare2 size={18} /> My work</NavLink>
            <NavLink to="/notifications" className="nav-link">
              <Bell size={18} /> Notifications
              {unread > 0 && <span className="count">{unread > 99 ? '99+' : unread}</span>}
            </NavLink>
            <NavLink to="/projects" end className="nav-link"><FolderKanban size={18} /> Projects</NavLink>
            <NavLink to="/profile" className="nav-link"><UserRound size={18} /> Profile</NavLink>
            {admin && <NavLink to="/admin" className="nav-link"><Shield size={18} /> Admin</NavLink>}
          </nav>

          <div className="sidebar-footer">
            <div className="me">
              <Avatar user={user} size={34} />
              <div className="me-text">
                <strong>{user.displayName}</strong>
                <span className="muted">{user.email}</span>
              </div>
            </div>
            <div className="sidebar-actions">
              <button className="icon-button" onClick={toggle} aria-label="Toggle theme" title="Toggle theme">
                {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
              </button>
              <button className="icon-button" onClick={() => setShowHelp(true)} aria-label="Keyboard shortcuts" title="Keyboard shortcuts (?)">
                <Keyboard size={18} />
              </button>
              <button className="icon-button" onClick={logout} aria-label="Log out" title="Log out">
                <LogOut size={18} />
              </button>
              <span className={`live-dot ${connected ? 'on' : ''}`} title={connected ? 'Live updates on' : 'Live updates reconnecting…'} />
            </div>
          </div>
        </aside>
        <div className="scrim" onClick={() => setMenuOpen(false)} />

        <main className="main">
          <Outlet />
        </main>

        {creating && (
          <TaskFormModal
            title={creating.parent ? `Add subtask to ${creating.parent.key}` : 'Create task'}
            submitLabel="Create task"
            mode={{
              kind: 'create',
              projectKey: creating.projectKey,
              sprintId: creating.sprintId,
              parent: creating.parent,
              onSubmit: async (input) => {
                const task = await api.createTask(input);
                setCreating(null);
                toast(`${task.key} created`);
                navigate(`/tasks/${task.id}`);
              },
            }}
            onClose={() => setCreating(null)}
          />
        )}
        {paletteOpen && (
          <CommandPalette projectKey={currentProject?.key} onClose={() => setPaletteOpen(false)}
            onCreate={() => openCreate({ projectKey: currentProject?.key })} />
        )}
        {showHelp && (
          <Modal title="Keyboard shortcuts" onClose={() => setShowHelp(false)}>
            <dl className="shortcuts">
              {SHORTCUTS.map(([keys, action]) => (
                <div key={keys}>
                  <dt>{keys.split(' then ').map((k, i) => (
                    <span key={k}>{i > 0 && <span className="muted"> then </span>}<kbd>{k}</kbd></span>
                  ))}</dt>
                  <dd>{action}</dd>
                </div>
              ))}
            </dl>
          </Modal>
        )}
      </div>
    </CreateTaskContext.Provider>
  );
}
