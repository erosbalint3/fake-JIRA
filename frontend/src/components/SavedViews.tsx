import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Bookmark, Trash2 } from 'lucide-react';
import { usePreference } from '../prefs';
import { useToast } from '../toast';
import { t } from '../i18n';

export interface SavedView<S> {
  name: string;
  state: S;
}

/**
 * Personal saved views for a page (board, table…): the current filters and display options under a name,
 * one click to switch back. Stored in the account, so they follow the person to every device.
 */
export function SavedViews<S>({ scope, current, onApply }: { scope: string; current: S; onApply: (state: S) => void }) {
  const [views, setViews] = usePreference<SavedView<S>[]>(`views:${scope.toLowerCase()}`, []);
  const [open, setOpen] = useState(false);
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const toast = useToast();

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

  const save = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    const next = [...views.filter((v) => v.name.toLowerCase() !== trimmed.toLowerCase()), { name: trimmed, state: current }].slice(-20);
    setViews(next);
    toast(t('View “{name}” saved', { name: trimmed }));
    setName('');
    setNaming(false);
    setOpen(false);
  };

  return (
    <div className="popover-anchor" ref={ref}>
      <button className="btn btn-ghost btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Bookmark size={15} aria-hidden /> {t('Views')}
      </button>
      {open && (
        <div className="popover saved-views" role="group" aria-label={t('Saved views')}>
          {views.length === 0 && !naming && <p className="muted small">{t('Save the current filters and layout to come back to them.')}</p>}
          {views.map((v) => (
            <div key={v.name} className="saved-view">
              <button className="link" onClick={() => {
                onApply(v.state);
                setOpen(false);
              }}>{v.name}</button>
              <button className="icon-button sm" aria-label={t('Delete view {name}', { name: v.name })}
                onClick={() => setViews(views.filter((x) => x.name !== v.name))}><Trash2 size={13} /></button>
            </div>
          ))}
          {naming ? (
            <form className="inline-form" onSubmit={save}>
              <input value={name} maxLength={40} autoFocus placeholder={t('View name')} aria-label={t('View name')}
                onChange={(e) => setName(e.target.value)} />
              <button className="btn btn-primary btn-sm" disabled={!name.trim()}>{t('Save')}</button>
            </form>
          ) : (
            <button className="btn btn-soft btn-sm" onClick={() => setNaming(true)}>{t('Save current view')}</button>
          )}
        </div>
      )}
    </div>
  );
}
