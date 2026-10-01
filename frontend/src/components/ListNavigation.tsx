import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { isReadOnlyRole, STATUSES, STATUS_LABEL, type Task } from '../types';
import { Modal } from './Modal';
import { useTransitionGuard } from './TransitionGuard';
import { t } from '../i18n';


function isTyping(target: EventTarget | null) {
  const element = target as HTMLElement | null;
  if (!element) return false;
  return element.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(element.tagName);
}

export interface PickerOption {
  value: string;
  label: string;
}

/** A small keyboard-driven chooser: type to filter, arrows to move, Enter to pick. */
export function QuickPicker({ title, options, onPick, onClose }: {
  title: string;
  options: PickerOption[];
  onPick: (value: string) => void;
  onClose: () => void;
}) {
  const [filter, setFilter] = useState('');
  const [index, setIndex] = useState(0);
  const shown = useMemo(
    () => options.filter((o) => o.label.toLowerCase().includes(filter.trim().toLowerCase())),
    [options, filter]);
  const current = Math.min(index, Math.max(0, shown.length - 1));

  return (
    <Modal title={title} onClose={onClose}>
      <input value={filter} aria-label={t('Filter')} placeholder={t('Type to filter…')}
        aria-controls="quick-picker-list" aria-activedescendant={shown[current] ? `quick-pick-${current}` : undefined}
        onChange={(e) => {
          setFilter(e.target.value);
          setIndex(0);
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setIndex(Math.min(shown.length - 1, current + 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setIndex(Math.max(0, current - 1));
          } else if (e.key === 'Enter' && shown[current]) {
            e.preventDefault();
            onPick(shown[current].value);
          }
        }} />
      <ul className="quick-picker" id="quick-picker-list" role="listbox" aria-label={title}>
        {shown.map((option, i) => (
          <li key={option.value} id={`quick-pick-${i}`} role="option" aria-selected={i === current}
            className={i === current ? 'active' : ''} onMouseEnter={() => setIndex(i)}
            onMouseDown={(e) => {
              e.preventDefault();
              onPick(option.value);
            }}>
            {option.label}
          </li>
        ))}
        {shown.length === 0 && <li className="muted">{t('No matches')}</li>}
      </ul>
    </Modal>
  );
}

/**
 * Keyboard navigation for a task list: rows are the elements with a data-task-id inside
 * {@code container}. j/k move, Enter/o open, e edit, a assign, s status, x select.
 * Returns the picker dialog to render (or null).
 */
export function useListNavigation(container: RefObject<HTMLElement | null>, onChanged?: (task: Task) => void) {
  const navigate = useNavigate();
  const { byKey } = useProjects();
  const guard = useTransitionGuard();
  const toast = useToast();
  const [picker, setPicker] = useState<{ kind: 'assign' | 'status'; task: Task } | null>(null);
  const active = useRef<string | null>(null);
  const latest = useRef({ navigate, onChanged });
  latest.current = { navigate, onChanged };

  useEffect(() => {
    let lastG = 0;
    const rows = () => Array.from(container.current?.querySelectorAll<HTMLElement>('[data-task-id]') ?? []);
    const highlight = (row: HTMLElement) => {
      rows().forEach((r) => r.removeAttribute('data-nav-active'));
      row.setAttribute('data-nav-active', '');
      row.scrollIntoView({ block: 'nearest' });
      active.current = row.dataset.taskId ?? null;
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return;
      if (document.querySelector('.modal') || !container.current) return;
      const key = event.key;
      // "g then …" belongs to the global go-to shortcuts.
      if (key === 'g') {
        lastG = Date.now();
        return;
      }
      if (Date.now() - lastG < 1200) {
        lastG = 0;
        return;
      }
      const list = rows();
      if (list.length === 0) return;
      const index = list.findIndex((r) => r.dataset.taskId === active.current);
      const row = index >= 0 ? list[index] : null;
      const id = row?.dataset.taskId;
      if (key === 'j' || key === 'k') {
        event.preventDefault();
        const next = index < 0 ? 0 : key === 'j' ? Math.min(list.length - 1, index + 1) : Math.max(0, index - 1);
        highlight(list[next]);
      } else if (!id) {
        return;
      } else if (key === 'Enter' || key === 'o') {
        if (key === 'Enter' && document.activeElement && document.activeElement !== document.body) return;
        event.preventDefault();
        latest.current.navigate(`/tasks/${id}`);
      } else if (key === 'e') {
        event.preventDefault();
        latest.current.navigate(`/tasks/${id}?edit=1`);
      } else if (key === 'a' || key === 's') {
        event.preventDefault();
        api.task(Number(id)).then((task) => setPicker({ kind: key === 'a' ? 'assign' : 'status', task }))
          .catch((e: ApiError) => toast(e.message, 'error'));
      } else if (key === 'x') {
        const check = row?.querySelector<HTMLInputElement>('.row-check');
        if (check) {
          event.preventDefault();
          check.click();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [container, toast]);

  if (!picker) return null;
  const { task, kind } = picker;
  const close = () => setPicker(null);
  const done = (updated: Task | null) => {
    close();
    if (updated) latest.current.onChanged?.(updated);
  };

  if (kind === 'assign') {
    const members = (byKey(task.projectKey)?.members ?? []).filter((m) => !isReadOnlyRole(m.role));
    const options: PickerOption[] = [
      { value: '', label: t('Unassigned') },
      ...members.map((m) => ({ value: String(m.id), label: m.displayName ? `${m.displayName} (${m.username})` : m.username })),
    ];
    return (
      <QuickPicker title={`${t('Assign')} ${task.key}`} options={options} onClose={close}
        onPick={(value) => api.assign(task.id, value ? Number(value) : null).then(done)
          .catch((e: ApiError) => { close(); toast(e.message, 'error'); })} />
    );
  }
  return (
    <QuickPicker title={`${t('Status of')} ${task.key}`} onClose={close}
      options={STATUSES.map((s) => ({ value: s, label: t(STATUS_LABEL[s]) }))}
      onPick={(value) => {
        close();
        guard(task, (resolution) => api.setStatus(task.id, value as Task['status'], resolution))
          .then((updated) => done(updated))
          .catch((e: ApiError) => toast(e.message, 'error'));
      }} />
  );
}
