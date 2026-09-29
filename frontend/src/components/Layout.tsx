import { createContext, Suspense, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import {
  BarChart3, Bell, Check, ChevronsUpDown, Filter, FolderKanban, Keyboard, KanbanSquare, ListTodo, LogOut, Map, Menu,
  Moon, Plus, Search, Settings, Shield, Sun, UserRound, UserSquare2, X,
  Package, Bot, LayoutDashboard, SearchCode, CalendarDays, Activity, Users,
} from 'lucide-react';
import { filterPath } from '../filters';
import { api, isOffline, OFFLINE_CHANGED } from '../api';
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
import { Spinner } from './States';
import { TaskFormModal } from './TaskFormModal';
import { PasswordForm } from './profile/PasswordForm';
import { OnboardingTour, START_TOUR } from './OnboardingTour';
import { useProjectAccess } from '../useProject';
import type { SavedFilter } from '../types';
import { t } from '../i18n';

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
  const { user, admin, logout, mustChangePassword } = useAuth();
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
  const [offline, setOffline] = useState(() => isOffline() || !navigator.onLine);
  useEffect(() => {
    const update = () => setOffline(isOffline() || !navigator.onLine);
    ['online', 'offline', OFFLINE_CHANGED].forEach((e) => window.addEventListener(e, update));
    return () => ['online', 'offline', OFFLINE_CHANGED].forEach((e) => window.removeEventListener(e, update));
  }, []);

  const currentKey = currentProject?.key;
  // Admins hear about new releases once (per version, per browser).
  const [update, setUpdate] = useState<{ latest: string; url: string | null } | null>(null);
  useEffect(() => {
    if (!admin) return;
    api.system().then((info) => {
      let dismissed: string | null = null;
      try {
        dismissed = localStorage.getItem('fakejira.update.dismissed');
      } catch {
        /* storage unavailable */
      }
      if (info.update.available && info.update.latest && info.update.latest !== dismissed) {
        setUpdate({ latest: info.update.latest, url: info.update.url });
      }
    }).catch(() => {});
  }, [admin]);
  // The project's accent colour applies while working inside it.
  const accent = match ? byKey(match.params.key)?.color ?? null : null;
  useEffect(() => {
    const root = document.documentElement.style;
    const vars = ['--accent', '--accent-hover', '--accent-soft', '--accent-text'];
    if (!accent) {
      vars.forEach((v) => root.removeProperty(v));
      return;
    }
    root.setProperty('--accent', accent);
    root.setProperty('--accent-hover', `color-mix(in srgb, ${accent} 82%, black)`);
    root.setProperty('--accent-soft', `color-mix(in srgb, ${accent} 16%, var(--surface))`);
    root.setProperty('--accent-text', `color-mix(in srgb, ${accent} 80%, var(--text))`);
    return () => vars.forEach((v) => root.removeProperty(v));
  }, [accent]);
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
      toast(t("Create a project first"), 'error');
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
        d: '/dashboard',
        s: '/search',
        c: '/calendar',
      };
      navigate(paths[target]);
    },
  });

  if (!user) return null;

  return (
    <CreateTaskContext.Provider value={openCreate}>
      <div className={`shell ${menuOpen ? 'menu-open' : ''}`}>
        <a href="#main" className="skip-link" onClick={(e) => {
          e.preventDefault();
          document.getElementById('main')?.focus();
        }}>{t('Skip to content')}</a>
        <header className="mobile-bar">
          <button className="icon-button" onClick={() => setMenuOpen(!menuOpen)} aria-label={t("Toggle menu")}>
            {menuOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
          <Logo />
          <NavLink to="/notifications" className="icon-button bell" aria-label={t("Notifications")}>
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
                <span className="switcher-text"><strong>{t("No project")}</strong><span className="muted">{t("Create one to start")}</span></span>
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
                <Link to="/projects?new=1" className="switcher-item create"><Plus size={15} /> {t("New project")}</Link>
              </div>
            )}
          </div>

          <button className="search-button" onClick={() => setPaletteOpen(true)}>
            <Search size={16} /> <span className="search-button-text">{t("Search…")}</span> <kbd>{navigator.platform.includes('Mac') ? '⌘' : 'Ctrl'} K</kbd>
          </button>
          {(!currentProject || canEdit) && (
            <button className="btn btn-primary btn-block create-button" onClick={() => openCreate({ projectKey: currentProject?.key })}>
              <Plus size={17} /> {t("Create task")} <kbd>C</kbd>
            </button>
          )}

          <nav className="nav">
            {currentProject && (
              <>
                <span className="nav-heading">{currentProject.key}</span>
                <NavLink to={`/p/${currentProject.key}/board`} className="nav-link"><KanbanSquare size={18} /> {t("Board")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/backlog`} className="nav-link"><ListTodo size={18} /> {t("Backlog")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/roadmap`} className="nav-link"><Map size={18} /> {t("Roadmap")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/releases`} className="nav-link"><Package size={18} /> {t("Releases")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/reports`} className="nav-link"><BarChart3 size={18} /> {t("Reports")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/automation`} className="nav-link"><Bot size={18} /> {t("Automation")}</NavLink>
                <NavLink to={`/p/${currentProject.key}/settings`} className="nav-link"><Settings size={18} /> {t("Settings")}</NavLink>
                {filters.length > 0 && (
                  <>
                    <span className="nav-heading">{t("Saved filters")}</span>
                    {filters.map((f) => (
                      <Link key={f.id} to={filterPath(currentProject.key, f.query)} className="nav-link nav-filter"
                        title={f.shared ? `Shared by ${f.owner}` : 'Only visible to you'}>
                        <Filter size={15} /> <span className="nav-filter-name">{f.name}</span>
                      </Link>
                    ))}
                  </>
                )}
              </>
            )}
            <span className="nav-heading">{t("Workspace")}</span>
            <NavLink to="/dashboard" className="nav-link"><LayoutDashboard size={18} /> {t("Dashboard")}</NavLink>
            <NavLink to="/my-work" className="nav-link"><UserSquare2 size={18} /> {t("My work")}</NavLink>
            <NavLink to="/search" className="nav-link"><SearchCode size={18} /> {t("Search")}</NavLink>
            <NavLink to="/calendar" className="nav-link"><CalendarDays size={18} /> {t("Calendar")}</NavLink>
            <NavLink to="/activity" className="nav-link"><Activity size={18} /> {t("Activity")}</NavLink>
            <NavLink to="/notifications" className="nav-link">
              <Bell size={18} /> {t('Notifications')}
              {unread > 0 && <span className="count">{unread > 99 ? '99+' : unread}</span>}
            </NavLink>
            <NavLink to="/projects" end className="nav-link"><FolderKanban size={18} /> {t("Projects")}</NavLink>
            <NavLink to="/teams" className="nav-link"><Users size={18} /> {t("Teams")}</NavLink>
            <NavLink to="/profile" className="nav-link"><UserRound size={18} /> {t("Profile")}</NavLink>
            {admin && <NavLink to="/admin" className="nav-link"><Shield size={18} /> {t("Admin")}</NavLink>}
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
              <button className="icon-button" onClick={toggle} aria-label={t("Toggle theme")} title={t("Toggle theme")}>
                {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
              </button>
              <button className="icon-button" onClick={() => setShowHelp(true)} aria-label={t("Keyboard shortcuts")} title={t("Keyboard shortcuts (?)")}>
                <Keyboard size={18} />
              </button>
              <button className="icon-button" onClick={logout} aria-label={t("Log out")} title={t("Log out")}>
                <LogOut size={18} />
              </button>
              <span className={`live-dot ${connected ? 'on' : ''}`} title={connected ? 'Live updates on' : 'Live updates reconnecting…'} />
            </div>
          </div>
        </aside>
        <div className="scrim" onClick={() => setMenuOpen(false)} />

        <main className="main" id="main" tabIndex={-1}>
          {offline && (
            <div className="offline-strip" role="status">
              {t("You're offline — showing the last saved copy. Changes can't be saved until you reconnect.")}
            </div>
          )}
          {update && (
            <div className="update-strip" role="status">
              FakeJIRA {update.latest} is available.{' '}
              {update.url && <a href={update.url} target="_blank" rel="noreferrer noopener">{t("Release notes")}</a>}{' '}
              <Link to="/admin">{t("Admin → System")}</Link>
              <button className="icon-button sm" aria-label={t("Dismiss update notice")} onClick={() => {
                try {
                  localStorage.setItem('fakejira.update.dismissed', update.latest);
                } catch {
                  /* storage unavailable */
                }
                setUpdate(null);
              }}><X size={14} /></button>
            </div>
          )}
          <Suspense fallback={<div className="page"><Spinner /></div>}>
            <Outlet />
          </Suspense>
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
        {mustChangePassword && (
          <Modal title={t("Choose a new password")} onClose={() => {}} dismissible={false}>
            <p className="muted small hint">{t("An administrator asked you to change your password before continuing.")}</p>
            <PasswordForm hasPassword />
            <button className="link small" onClick={logout}>{t("Sign out instead")}</button>
          </Modal>
        )}
        {paletteOpen && (
          <CommandPalette projectKey={currentProject?.key} onClose={() => setPaletteOpen(false)}
            onCreate={() => openCreate({ projectKey: currentProject?.key })} />
        )}
        <OnboardingTour />
        {showHelp && (
          <Modal title={t("Keyboard shortcuts")} onClose={() => setShowHelp(false)}>
            <dl className="shortcuts">
              {SHORTCUTS.map(([keys, action]) => (
                <div key={keys}>
                  <dt>{keys.split(' then ').map((k, i) => (
                    <span key={k}>{i > 0 && <span className="muted"> {t("then")} </span>}<kbd>{k}</kbd></span>
                  ))}</dt>
                  <dd>{action}</dd>
                </div>
              ))}
            </dl>
            <button className="btn btn-soft btn-sm" onClick={() => {
              setShowHelp(false);
              window.dispatchEvent(new Event(START_TOUR));
            }}>{t('Take the tour')}</button>
          </Modal>
        )}
      </div>
    </CreateTaskContext.Provider>
  );
}
