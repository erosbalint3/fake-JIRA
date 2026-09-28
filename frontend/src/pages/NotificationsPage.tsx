import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BellOff, CheckCheck } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { NOTIFICATIONS_CHANGED } from '../components/Layout';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { timeAgo } from '../format';
import type { Notification } from '../types';

export function NotificationsPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Notification[] | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setError('');
    api.notifications().then((data) => setItems(data.items)).catch((e: ApiError) => setError(e.message));
  }, []);

  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'notification', load);

  const changed = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));

  const open = async (item: Notification) => {
    if (!item.read) {
      setItems((current) => current?.map((n) => (n.id === item.id ? { ...n, read: true } : n)) ?? null);
      await api.markRead(item.id).catch(() => {});
      changed();
    }
    if (item.taskId) navigate(`/tasks/${item.taskId}`);
  };

  const markAll = async () => {
    await api.markAllRead();
    setItems((current) => current?.map((n) => ({ ...n, read: true })) ?? null);
    changed();
  };

  const unread = items?.filter((n) => !n.read).length ?? 0;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Notifications</h1>
          <p className="muted">{unread ? `${unread} unread` : 'You are all caught up.'}</p>
        </div>
        <button className="btn btn-soft" onClick={markAll} disabled={!unread}>
          <CheckCheck size={18} /> Mark all as read
        </button>
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!items && !error && <Spinner />}
      {items && items.length === 0 && (
        <EmptyState icon={<BellOff size={28} />} title="No notifications yet">
          You will hear about it here when someone accepts, updates or comments on your tasks.
        </EmptyState>
      )}
      {items && items.length > 0 && (
        <ul className="notifications">
          {items.map((item) => (
            <li key={item.id}>
              <button className={`notification ${item.read ? '' : 'unread'}`} onClick={() => open(item)}>
                <span className="notification-dot" aria-hidden />
                <span className="notification-text">{item.message}</span>
                <span className="muted small">{timeAgo(item.createdAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
