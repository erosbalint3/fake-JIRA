import { useEffect, useRef, useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { usePreference } from '../prefs';
import { t } from '../i18n';

export interface CardFields {
  key: boolean; type: boolean; priority: boolean; points: boolean; assignee: boolean; epic: boolean; labels: boolean;
  due: boolean; checklist: boolean; subtasks: boolean; build: boolean; parent: boolean;
}

export const DEFAULT_CARD_FIELDS: CardFields = {
  key: true, type: true, priority: true, points: true, assignee: true, epic: true, labels: true,
  due: true, checklist: true, subtasks: true, build: true, parent: true,
};

const LABELS: [keyof CardFields, string][] = [
  ['key', 'Key'], ['type', 'Type'], ['priority', 'Priority'], ['points', 'Story points'], ['assignee', 'Assignee'],
  ['epic', 'Epic'], ['labels', 'Labels'], ['due', 'Due date'], ['checklist', 'Checklist'], ['subtasks', 'Subtasks'],
  ['build', 'Build status'], ['parent', 'Parent task'],
];

/** Which details the board cards show; personal, per project, synced across devices. */
export function useCardFields(projectKey: string): [CardFields, (f: CardFields | null) => void] {
  const [stored, set] = usePreference<Partial<CardFields>>(`cards:${projectKey.toLowerCase()}`, {});
  return [{ ...DEFAULT_CARD_FIELDS, ...stored }, set as (f: CardFields | null) => void];
}

export function CardFieldsButton({ projectKey }: { projectKey: string }) {
  const [fields, setFields] = useCardFields(projectKey);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);
  return (
    <div className="popover-anchor" ref={ref}>
      <button className="btn btn-ghost btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>
        <SlidersHorizontal size={15} aria-hidden /> {t('Card fields')}
      </button>
      {open && (
        <div className="popover card-fields" role="group" aria-label={t('Card fields')}>
          {LABELS.map(([id, label]) => (
            <label key={id} className="toggle">
              <input type="checkbox" checked={fields[id]} onChange={(e) => setFields({ ...fields, [id]: e.target.checked })} />
              {t(label)}
            </label>
          ))}
          <button className="link small" onClick={() => setFields(null)}>{t('Show everything')}</button>
        </div>
      )}
    </div>
  );
}
