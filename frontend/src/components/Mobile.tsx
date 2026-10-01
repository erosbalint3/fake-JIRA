import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Bell, Home, KanbanSquare, Menu, RefreshCw, Search } from 'lucide-react';
import { t } from '../i18n';

/** Thumb-reachable navigation on phones: My work, Board, Search, Notifications and the full menu. */
export function BottomNav({ projectKey, unread, onMenu }: { projectKey?: string; unread: number; onMenu: () => void }) {
  return (
    <nav className="bottom-nav" aria-label={t('Quick navigation')}>
      <NavLink to="/my-work"><Home size={20} aria-hidden /><span>{t('My work')}</span></NavLink>
      <NavLink to={projectKey ? `/p/${projectKey}/board` : '/projects'}><KanbanSquare size={20} aria-hidden /><span>{t('Board')}</span></NavLink>
      <NavLink to="/search"><Search size={20} aria-hidden /><span>{t('Search')}</span></NavLink>
      <NavLink to="/notifications" className="bottom-bell">
        <Bell size={20} aria-hidden /><span>{t('Inbox')}</span>
        {unread > 0 && <span className="bottom-count" aria-label={t('{n} unread', { n: unread })}>{unread > 99 ? '99+' : unread}</span>}
      </NavLink>
      <button type="button" onClick={onMenu}><Menu size={20} aria-hidden /><span>{t('Menu')}</span></button>
    </nav>
  );
}

const PULL = 70;

/** Pull down at the top of the page to reload it (touch screens). */
export function PullToRefresh() {
  const [distance, setDistance] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const start = useRef<number | null>(null);
  const current = useRef(0);

  useEffect(() => {
    const onStart = (e: TouchEvent) => {
      const target = e.target as HTMLElement;
      // Only from the top of the page, and not inside something that scrolls on its own.
      start.current = window.scrollY <= 0 && !target.closest('.modal, .board-columns, .sheet-wrap, .gantt-scroll, textarea')
        ? e.touches[0].clientY : null;
    };
    const onMove = (e: TouchEvent) => {
      if (start.current === null) return;
      const d = e.touches[0].clientY - start.current;
      current.current = d > 0 ? Math.min(120, d * 0.6) : 0;
      setDistance(current.current);
    };
    const onEnd = () => {
      if (start.current !== null && current.current >= PULL) {
        setRefreshing(true);
        window.location.reload();
      }
      start.current = null;
      current.current = 0;
      setDistance(0);
    };
    window.addEventListener('touchstart', onStart, { passive: true });
    window.addEventListener('touchmove', onMove, { passive: true });
    window.addEventListener('touchend', onEnd);
    return () => {
      window.removeEventListener('touchstart', onStart);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onEnd);
    };
  }, []);

  if (distance === 0 && !refreshing) return null;
  return (
    <div className="pull-refresh" style={{ height: refreshing ? 48 : distance }} role="status">
      <RefreshCw size={18} className={refreshing ? 'spin' : ''} style={{ transform: `rotate(${distance * 3}deg)` }} aria-hidden />
      <span className="small">{refreshing ? t('Refreshing…') : distance >= PULL ? t('Release to refresh') : t('Pull to refresh')}</span>
    </div>
  );
}

/** Horizontal swipe detection for touch: calls back with -1 (left) or 1 (right). */
export function swipeHandlers(onSwipe: (direction: -1 | 1) => void) {
  let x = 0;
  let y = 0;
  return {
    onTouchStart: (e: React.TouchEvent) => {
      x = e.touches[0].clientX;
      y = e.touches[0].clientY;
    },
    onTouchEnd: (e: React.TouchEvent) => {
      const dx = e.changedTouches[0].clientX - x;
      const dy = e.changedTouches[0].clientY - y;
      if (Math.abs(dx) > 80 && Math.abs(dy) < 40) onSwipe(dx > 0 ? 1 : -1);
    },
  };
}
