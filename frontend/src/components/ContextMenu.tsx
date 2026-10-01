import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import type { Priority, Status, Task } from '../types';
import { t } from '../i18n';

export interface MenuItem {
  label: string;
  onSelect?: () => void | Promise<void>;
  danger?: boolean;
  disabled?: boolean;
  /** Draws a divider above the item. */
  separator?: boolean;
  checked?: boolean;
}

interface State {
  x: number;
  y: number;
  items: MenuItem[];
  label: string;
}

const Ctx = createContext<(event: MouseEvent | { clientX: number; clientY: number; preventDefault?: () => void }, label: string, items: MenuItem[]) => void>(() => {});

/** Right-click menus: {@code useContextMenu()(event, label, items)} opens one at the pointer. */
export function useContextMenu() {
  return useContext(Ctx);
}

export function ContextMenuProvider({ children }: { children: ReactNode }) {
  const [menu, setMenu] = useState<State | null>(null);
  const [active, setActive] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const restore = useRef<HTMLElement | null>(null);
  const toast = useToast();

  const open = useCallback((event: { clientX: number; clientY: number; preventDefault?: () => void }, label: string, items: MenuItem[]) => {
    event.preventDefault?.();
    restore.current = document.activeElement as HTMLElement | null;
    setMenu({ x: event.clientX, y: event.clientY, items, label });
    setActive(items.findIndex((i) => !i.disabled));
  }, []);

  const close = useCallback(() => {
    setMenu(null);
    restore.current?.focus?.();
  }, []);

  // Keep the menu inside the window.
  useLayoutEffect(() => {
    if (!menu || !ref.current) return;
    const box = ref.current.getBoundingClientRect();
    const x = Math.min(menu.x, window.innerWidth - box.width - 8);
    const y = Math.min(menu.y, window.innerHeight - box.height - 8);
    ref.current.style.left = `${Math.max(8, x)}px`;
    ref.current.style.top = `${Math.max(8, y)}px`;
    ref.current.focus();
  }, [menu]);

  useEffect(() => {
    if (!menu) return;
    const onDown = (e: globalThis.MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    const onScroll = () => close();
    document.addEventListener('mousedown', onDown);
    window.addEventListener('resize', onScroll);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('resize', onScroll);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [menu, close]);

  const choose = async (item: MenuItem) => {
    if (item.disabled) return;
    close();
    try {
      await item.onSelect?.();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : t('Something went wrong'), 'error');
    }
  };

  return (
    <Ctx.Provider value={open}>
      {children}
      {menu && (
        <div ref={ref} className="context-menu" role="menu" aria-label={menu.label} tabIndex={-1}
          style={{ left: menu.x, top: menu.y }}
          onKeyDown={(e) => {
            const enabled = menu.items.map((item, i) => (item.disabled ? -1 : i)).filter((i) => i >= 0);
            const at = enabled.indexOf(active);
            if (e.key === 'Escape') {
              e.preventDefault();
              close();
            } else if (e.key === 'ArrowDown') {
              e.preventDefault();
              setActive(enabled[(at + 1) % enabled.length]);
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              setActive(enabled[(at - 1 + enabled.length) % enabled.length]);
            } else if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              if (menu.items[active]) choose(menu.items[active]);
            } else if (e.key === 'Tab') {
              close();
            }
          }}
          onContextMenu={(e) => e.preventDefault()}>
          {menu.items.map((item, i) => (
            <div key={i}>
              {item.separator && <div className="context-separator" role="separator" />}
              <button type="button" role={item.checked === undefined ? 'menuitem' : 'menuitemcheckbox'}
                aria-checked={item.checked} tabIndex={-1} disabled={item.disabled}
                className={`context-item ${i === active ? 'active' : ''} ${item.danger ? 'danger' : ''}`}
                onMouseEnter={() => !item.disabled && setActive(i)} onClick={() => choose(item)}>
                <span className="context-check" aria-hidden>{item.checked ? '✓' : ''}</span>
                {item.label}
              </button>
            </div>
          ))}
        </div>
      )}
    </Ctx.Provider>
  );
}

const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const STATUSES: [Status, string][] = [['TODO', 'To do'], ['IN_PROGRESS', 'In progress'], ['IN_REVIEW', 'In review'], ['DONE', 'Done']];

/** The usual things to do with a task, for right-click menus on cards and rows. */
export function useTaskMenu(onChanged?: () => void) {
  const openMenu = useContextMenu();
  const { user } = useAuth();
  const toast = useToast();
  return (event: MouseEvent, task: Task, canEdit: boolean) => {
    const link = `${window.location.origin}/tasks/${task.id}`;
    const change = async (work: () => Promise<unknown>, message: string) => {
      await work();
      toast(message);
      onChanged?.();
    };
    const copy = async (text: string, message: string) => {
      try {
        await navigator.clipboard.writeText(text);
        toast(message);
      } catch {
        window.prompt(message, text);
      }
    };
    const items: MenuItem[] = [
      { label: t('Open'), onSelect: () => { window.location.assign(`/tasks/${task.id}`); } },
      { label: t('Open in new tab'), onSelect: () => { window.open(`/tasks/${task.id}`, '_blank', 'noopener'); } },
      { label: t('Copy link'), onSelect: () => copy(link, t('Link copied')) },
      { label: t('Copy key'), onSelect: () => copy(task.key, t('{key} copied', { key: task.key })) },
    ];
    if (canEdit) {
      items.push(
        task.assignee?.id === user?.id
          ? { label: t('Unassign me'), separator: true, onSelect: () => change(() => api.bulk([task.id], { unassign: true }), t('Unassigned')) }
          : { label: t('Assign to me'), separator: true, onSelect: () => change(() => api.bulk([task.id], { assigneeId: user!.id }), t('Assigned to you')) },
        ...STATUSES.map(([status, label], i): MenuItem => ({
          label: t('Move to {status}', { status: t(label) }), separator: i === 0, checked: task.status === status,
          disabled: task.status === status,
          onSelect: () => change(() => api.bulk([task.id], { status }), t('Moved to {status}', { status: t(label) })),
        })),
        ...PRIORITIES.map((priority, i): MenuItem => ({
          label: t('Priority: {p}', { p: t(priority.charAt(0) + priority.slice(1).toLowerCase()) }), separator: i === 0,
          checked: task.priority === priority, disabled: task.priority === priority,
          onSelect: () => change(() => api.bulk([task.id], { priority }), t('Priority changed')),
        })),
        { label: t('Archive'), separator: true, onSelect: () => change(() => api.archiveTask(task.id), t('{key} archived', { key: task.key })) },
        { label: t('Delete…'), danger: true, onSelect: async () => {
          if (window.confirm(t('Delete {key}? This cannot be undone.', { key: task.key }))) {
            await change(() => api.deleteTask(task.id), t('{key} deleted', { key: task.key }));
          }
        } },
      );
    }
    openMenu(event, t('Actions for {key}', { key: task.key }), items);
  };
}
