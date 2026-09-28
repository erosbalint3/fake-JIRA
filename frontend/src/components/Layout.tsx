import { useCallback, useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { Bell, KanbanSquare, ListTodo, LogOut, Menu, Moon, Sun, UserRound, X } from 'lucide-react';
import { api } from '../api';
import { useAuth } from '../auth';
import { Avatar } from './Avatar';
import { Logo } from './Logo';
import { useTheme } from '../theme';

export const NOTIFICATIONS_CHANGED = 'fakejira:notifications-changed';

export function Layout() {
  const { user, logout } = useAuth();
  const { theme, toggle } = useTheme();
  const location = useLocation();
  const [unread, setUnread] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);

  const refreshUnread = useCallback(() => {
    api.unreadCount().then(setUnread).catch(() => {});
  }, []);

  useEffect(() => {
    refreshUnread();
    setMenuOpen(false);
  }, [location.pathname, refreshUnread]);

  useEffect(() => {
    const interval = window.setInterval(refreshUnread, 30000);
    window.addEventListener(NOTIFICATIONS_CHANGED, refreshUnread);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener(NOTIFICATIONS_CHANGED, refreshUnread);
    };
  }, [refreshUnread]);

  if (!user) return null;

  return (
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
        <nav className="nav">
          <span className="nav-heading">Workspace</span>
          <NavLink to="/backlog" className="nav-link">
            <ListTodo size={18} /> Backlog
          </NavLink>
          <NavLink to="/board" className="nav-link">
            <KanbanSquare size={18} /> My board
          </NavLink>
          <NavLink to="/notifications" className="nav-link">
            <Bell size={18} /> Notifications
            {unread > 0 && <span className="count">{unread > 99 ? '99+' : unread}</span>}
          </NavLink>
          <span className="nav-heading">Account</span>
          <NavLink to="/profile" className="nav-link">
            <UserRound size={18} /> Profile
          </NavLink>
        </nav>

        <div className="sidebar-footer">
          <div className="me">
            <Avatar name={user.username} size={34} />
            <div className="me-text">
              <strong>{user.username}</strong>
              <span className="muted">{user.email}</span>
            </div>
          </div>
          <div className="sidebar-actions">
            <button className="icon-button" onClick={toggle} aria-label="Toggle theme" title="Toggle theme">
              {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <button className="icon-button" onClick={logout} aria-label="Log out" title="Log out">
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      <div className="scrim" onClick={() => setMenuOpen(false)} />

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
