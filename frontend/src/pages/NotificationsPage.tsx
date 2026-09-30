import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Archive, BellOff, CheckCheck, Clock, Inbox, RotateCcw, Settings2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { NOTIFICATIONS_CHANGED } from '../components/Layout';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { Modal } from '../components/Modal';
import { formatWhen, laterPresets, localInput } from '../components/Reminders';
import { timeAgo } from '../format';
import type { Notification } from '../types';
import { t } from '../i18n';

type View = 'inbox' | 'snoozed' | 'done';

function isTyping(target: EventTarget | null) {
  const element = target as HTMLElement | null;
  return !!element && (element.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName));
}

/**
 * The inbox: open, mark done, or snooze notifications. Keyboard: j/k move, Enter/o open,
 * d (or e) done, s snooze, x select, u back to the inbox.
 */
export function NotificationsPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [view, setView] = useState<View>('inbox');
  const [items, setItems] = useState<Notification[] | null>(null);
  const [unread, setUnread] = useState(0);
  const [error, setError] = useState('');
  const [active, setActive] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [snoozing, setSnoozing] = useState<number[] | null>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // A reload started for another tab must not overwrite the one now showing.
  const currentView = useRef(view);
  currentView.current = view;
  const load = useCallback(() => {
    setError('');
    api.notifications(view).then((data) => {
      if (currentView.current !== view) return;
      setItems(data.items);
      setUnread(data.unread);
    }).catch((e: ApiError) => currentView.current === view && setError(e.message));
  }, [view]);

  useEffect(() => {
    setItems(null);
    setSelected(new Set());
    setActive(0);
    load();
  }, [load]);
  useLiveRefresh((m) => m.type === 'notification', load);

  const changed = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED));

  const triage = useCallback(async (ids: number[], action: 'done' | 'undone' | 'snooze', until?: string) => {
    if (!ids.length) return;
    // Optimistic: the items leave this view right away.
    setItems((current) => current?.filter((n) => !ids.includes(n.id)) ?? null);
    setSelected(new Set());
    try {
      await api.triage(ids, action, until);
      changed();
      const count = ids.length;
      if (action === 'done') {
        toast(count === 1 ? t('Marked done') : t('{n} marked done', { n: count }), 'success', {
          action: { label: t('Undo'), onClick: () => api.triage(ids, 'undone').then(() => { load(); changed(); }) },
        });
      } else if (action === 'snooze' && until) {
        toast(t('Snoozed until {when}', { when: formatWhen(until) }));
      }
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
    load();
  }, [load, toast]);

  const open = useCallback(async (item: Notification) => {
    if (!item.read) {
      setItems((current) => current?.map((n) => (n.id === item.id ? { ...n, read: true } : n)) ?? null);
      await api.markRead(item.id).catch(() => {});
      changed();
    }
    if (item.taskId) navigate(`/tasks/${item.taskId}`);
  }, [navigate]);

  const markAll = async () => {
    await api.markAllRead();
    setItems((current) => current?.map((n) => ({ ...n, read: true })) ?? null);
    changed();
  };

  const doneRead = async () => {
    const { done } = await api.doneRead();
    toast(t('{n} moved to Done', { n: done }));
    load();
    changed();
  };

  // Keyboard triage.
  const latest = useRef({ items, active, selected, view, open, triage });
  latest.current = { items, active, selected, view, open, triage };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target) || document.querySelector('.modal')) return;
      const { items: list, active: index, selected: chosen, view: current } = latest.current;
      if (!list || list.length === 0) return;
      const item = list[Math.min(index, list.length - 1)];
      const targets = chosen.size ? [...chosen] : [item.id];
      const key = event.key;
      if (key === 'j' || key === 'k') {
        event.preventDefault();
        setActive(Math.max(0, Math.min(list.length - 1, index + (key === 'j' ? 1 : -1))));
      } else if (key === 'o' || (key === 'Enter' && document.activeElement === document.body)) {
        event.preventDefault();
        latest.current.open(item);
      } else if ((key === 'd' || key === 'e') && current !== 'done') {
        event.preventDefault();
        latest.current.triage(targets, 'done');
      } else if (key === 's' && current !== 'done') {
        event.preventDefault();
        setSnoozing(targets);
      } else if (key === 'u' && current !== 'inbox') {
        event.preventDefault();
        latest.current.triage(targets, 'undone');
      } else if (key === 'x') {
        event.preventDefault();
        setSelected((set) => {
          const next = new Set(set);
          if (next.has(item.id)) next.delete(item.id);
          else next.add(item.id);
          return next;
        });
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[data-nav-active]')?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const toggle = (id: number) => setSelected((set) => {
    const next = new Set(set);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });

  const empty = {
    inbox: [t('Inbox zero'), t('You will hear about it here when someone assigns, mentions or updates your tasks.')],
    snoozed: [t('Nothing snoozed'), t('Snooze a notification to have it come back later.')],
    done: [t('Nothing done yet'), t('Notifications you mark done are kept here.')],
  }[view];

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t('Inbox')}</h1>
          <p className="muted">{unread ? t('{n} unread', { n: unread }) : t('You are all caught up.')}</p>
        </div>
        <div className="row-actions">
          {view === 'inbox' && (
            <>
              <button className="btn btn-soft" onClick={markAll} disabled={!unread}><CheckCheck size={18} /> {t('Mark all as read')}</button>
              <button className="btn btn-soft" onClick={doneRead}><Archive size={18} /> {t('Done with all read')}</button>
            </>
          )}
          <Link className="icon-button" to="/profile#notifications" aria-label={t('Notification settings')} title={t('Notification settings')}>
            <Settings2 size={18} />
          </Link>
        </div>
      </header>

      <nav className="tabs" role="tablist" aria-label={t('Inbox views')}>
        {([['inbox', t('Inbox'), Inbox], ['snoozed', t('Snoozed'), Clock], ['done', t('Done'), Archive]] as const).map(([id, label, Icon]) => (
          <button key={id} role="tab" aria-selected={view === id} className={`tab ${view === id ? 'active' : ''}`} onClick={() => setView(id)}>
            <Icon size={15} aria-hidden /> {label}
          </button>
        ))}
      </nav>

      {selected.size > 0 && (
        <div className="selection-bar" role="toolbar" aria-label={t('Selected notifications')}>
          <strong>{t('{n} selected', { n: selected.size })}</strong>
          {view !== 'done' && <button className="btn btn-soft btn-sm" onClick={() => triage([...selected], 'done')}>{t('Done')}</button>}
          {view !== 'done' && <button className="btn btn-soft btn-sm" onClick={() => setSnoozing([...selected])}>{t('Snooze')}</button>}
          {view !== 'inbox' && <button className="btn btn-soft btn-sm" onClick={() => triage([...selected], 'undone')}>{t('Back to inbox')}</button>}
          <button className="btn btn-ghost btn-sm" onClick={() => setSelected(new Set())}>{t('Clear')}</button>
        </div>
      )}

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!items && !error && <Spinner />}
      {items && items.length === 0 && (
        <EmptyState icon={<BellOff size={28} />} title={empty[0]}>{empty[1]}</EmptyState>
      )}
      {items && items.length > 0 && (
        <ul className="notifications" ref={listRef}>
          {items.map((item, index) => (
            <li key={item.id} className={`notification-item ${selected.has(item.id) ? 'selected' : ''}`}
              {...(index === Math.min(active, items.length - 1) ? { 'data-nav-active': '' } : {})}>
              <input type="checkbox" className="row-check" checked={selected.has(item.id)} onChange={() => toggle(item.id)}
                aria-label={t('Select notification')} />
              <button className={`notification ${item.read ? '' : 'unread'}`} onClick={() => open(item)}>
                <span className="notification-dot" aria-hidden />
                <span className="notification-text">{item.message}</span>
                <span className="muted small">
                  {view === 'snoozed' && item.snoozedUntil ? t('until {when}', { when: formatWhen(item.snoozedUntil) }) : timeAgo(item.createdAt)}
                </span>
              </button>
              <span className="notification-actions">
                {view !== 'done' && (
                  <button className="icon-button" onClick={() => triage([item.id], 'done')} aria-label={t('Mark done')} title={t('Done (d)')}>
                    <Archive size={16} />
                  </button>
                )}
                {view !== 'done' && (
                  <button className="icon-button" onClick={() => setSnoozing([item.id])} aria-label={t('Snooze')} title={t('Snooze (s)')}>
                    <Clock size={16} />
                  </button>
                )}
                {view !== 'inbox' && (
                  <button className="icon-button" onClick={() => triage([item.id], 'undone')} aria-label={t('Back to inbox')} title={t('Back to inbox (u)')}>
                    <RotateCcw size={16} />
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {items && items.length > 0 && (
        <p className="muted small keyboard-hint">{t('Tip: j/k move, Enter opens, d marks done, s snoozes, x selects.')}</p>
      )}
      {snoozing && <SnoozeModal onClose={() => setSnoozing(null)} onPick={(until) => {
        triage(snoozing, 'snooze', until);
        setSnoozing(null);
      }} />}
    </div>
  );
}

function SnoozeModal({ onPick, onClose }: { onPick: (until: string) => void; onClose: () => void }) {
  const [custom, setCustom] = useState(localInput(laterPresets()[2]?.at ?? new Date(Date.now() + 86400_000)));
  return (
    <Modal title={t('Snooze until…')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" onClick={() => onPick(new Date(custom).toISOString())}>{t('Snooze')}</button>
      </>
    }>
      <div className="preset-list">
        {laterPresets().map((preset) => (
          <button key={preset.label} className="btn btn-soft" onClick={() => onPick(preset.at.toISOString())}>
            {preset.label} <span className="muted small">{formatWhen(preset.at.toISOString())}</span>
          </button>
        ))}
      </div>
      <label className="field">
        <span>{t('Pick a time')}</span>
        <input type="datetime-local" value={custom} onChange={(e) => setCustom(e.target.value)} />
      </label>
    </Modal>
  );
}
