import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Target, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { SprintGoal } from '../../types';
import { t } from '../../i18n';

/** The sprint's goals as a checklist; the review reports how many were met. */
export function SprintGoals({ sprintId, canEdit, compact }: { sprintId: number; canEdit: boolean; compact?: boolean }) {
  const toast = useToast();
  const [goals, setGoals] = useState<SprintGoal[] | null>(null);
  const [text, setText] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(() => {
    api.sprintGoals(sprintId).then(setGoals).catch(() => setGoals([]));
  }, [sprintId]);
  useEffect(load, [load]);

  const add = async (event: FormEvent) => {
    event.preventDefault();
    if (!text.trim()) return;
    try {
      const goal = await api.addSprintGoal(sprintId, text.trim());
      setGoals([...(goals ?? []), goal]);
      setText('');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!goals) return null;
  if (compact && goals.length === 0 && !canEdit) return null;
  const met = goals.filter((g) => g.done).length;

  return (
    <div className={`sprint-goals ${compact ? 'compact' : 'panel'}`}>
      <div className="sprint-goals-head">
        <Target size={14} aria-hidden />
        <strong>{t('Sprint goals')}</strong>
        {goals.length > 0 && <span className="muted small">{t('{met} of {total} met', { met, total: goals.length })}</span>}
        {canEdit && !adding && goals.length < 10 && (
          <button className="link small" onClick={() => setAdding(true)}>+ {t('Add goal')}</button>
        )}
      </div>
      {goals.length > 0 && (
        <ul className="goal-list">
          {goals.map((g) => (
            <li key={g.id} className={g.done ? 'done' : ''}>
              <label>
                <input type="checkbox" checked={g.done} disabled={!canEdit} onChange={async (e) => {
                  const done = e.target.checked;
                  setGoals(goals.map((x) => (x.id === g.id ? { ...x, done } : x)));
                  await api.updateSprintGoal(g.id, { done }).catch((err: ApiError) => {
                    toast(err.message, 'error');
                    load();
                  });
                }} />
                <span>{g.text}</span>
              </label>
              {canEdit && (
                <button className="icon-button sm" aria-label={t('Remove goal')} onClick={async () => {
                  await api.deleteSprintGoal(g.id).catch(() => {});
                  load();
                }}><X size={13} /></button>
              )}
            </li>
          ))}
        </ul>
      )}
      {adding && (
        <form className="inline-form" onSubmit={add}>
          <input autoFocus value={text} maxLength={200} placeholder={t('e.g. Checkout works on mobile')}
            aria-label={t('New sprint goal')} onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => e.key === 'Escape' && setAdding(false)} />
          <button className="btn btn-soft btn-sm" disabled={!text.trim()}>{t('Add')}</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAdding(false)}>{t('Done')}</button>
        </form>
      )}
    </div>
  );
}
