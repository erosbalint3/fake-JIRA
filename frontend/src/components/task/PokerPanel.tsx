import { useCallback, useEffect, useState } from 'react';
import { Check, Dices, RotateCcw, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { Avatar } from '../Avatar';
import type { PokerState, Task } from '../../types';
import { t } from '../../i18n';

/** Planning poker: everyone picks a card in secret, then the votes are revealed and an estimate accepted. */
export function PokerPanel({ task, canEdit, onAccepted }: { task: Task; canEdit: boolean; onAccepted: () => void }) {
  const toast = useToast();
  const [state, setState] = useState<PokerState | null>(null);
  const [choice, setChoice] = useState('');

  const load = useCallback(() => {
    api.poker(task.id).then(setState).catch(() => setState(null));
  }, [task.id]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === task.id, load, 200);

  useEffect(() => {
    if (state?.revealed && state.suggestion) setChoice(state.suggestion);
  }, [state?.revealed, state?.suggestion]);

  const run = async (action: () => Promise<PokerState | void>, message?: string) => {
    try {
      const next = await action();
      if (next) setState(next);
      else load();
      if (message) toast(message);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!state) return null;
  if (!state.active) {
    if (!canEdit) return null;
    return (
      <div className="poker poker-idle">
        <button className="btn btn-ghost btn-sm btn-block" onClick={() => run(() => api.startPoker(task.id), t('Planning poker started'))}>
          <Dices size={15} /> {t('Estimate with planning poker')}
        </button>
      </div>
    );
  }

  const numeric = state.deck.filter((c) => /^\d+$/.test(c));
  return (
    <section className="poker" aria-label={t('Planning poker')}>
      <header>
        <h3><Dices size={15} /> {t('Planning poker')}</h3>
        {canEdit && (
          <button className="icon-button sm" aria-label={t('Cancel planning poker')} title={t('Cancel')}
            onClick={() => run(() => api.cancelPoker(task.id), t('Planning poker cancelled'))}><X size={15} /></button>
        )}
      </header>
      {!state.revealed ? (
        <>
          <p className="muted small">{state.votes.length ? t('{n} voted', { n: state.votes.length }) : t('Nobody has voted yet')} · {t('votes stay hidden until revealed')}</p>
          {canEdit && (
            <div className="poker-deck" role="radiogroup" aria-label={t('Your estimate')}>
              {state.deck.map((card) => (
                <button key={card} role="radio" aria-checked={state.myVote === card}
                  className={`poker-card ${state.myVote === card ? 'picked' : ''}`}
                  onClick={() => run(() => api.pokerVote(task.id, state.myVote === card ? null : card))}>
                  {card}
                </button>
              ))}
            </div>
          )}
        </>
      ) : (
        <p className="poker-result">
          {state.average !== null ? <>{t('Average')} <strong>{state.average}</strong> · {t('suggested')} <strong>{state.suggestion}</strong></> : t('No numeric votes.')}
          {state.consensus && <span className="chip success">{t('Consensus!')}</span>}
        </p>
      )}
      <ul className="poker-votes">
        {state.votes.map((v) => (
          <li key={v.user.id}>
            <Avatar user={v.user} size={20} />
            <span>{v.user.displayName}</span>
            <span className={`poker-vote ${v.value ? '' : 'hidden'}`}>{v.value ?? '✓'}</span>
          </li>
        ))}
      </ul>
      {canEdit && (
        <div className="poker-actions">
          {!state.revealed ? (
            <button className="btn btn-soft btn-sm" disabled={state.votes.length === 0}
              onClick={() => run(() => api.revealPoker(task.id))}>{t('Reveal votes')}</button>
          ) : (
            <>
              <select value={choice} onChange={(e) => setChoice(e.target.value)} aria-label={t('Estimate to accept')}>
                {numeric.map((c) => <option key={c} value={c}>{t('{n} pts', { n: c })}</option>)}
              </select>
              <button className="btn btn-primary btn-sm" disabled={!choice}
                onClick={() => run(async () => {
                  const next = await api.acceptPoker(task.id, Number(choice));
                  onAccepted();
                  return next;
                }, t('Estimated at {n} points', { n: choice }))}><Check size={15} /> {t('Accept estimate')}</button>
            </>
          )}
          <button className="btn btn-ghost btn-sm" onClick={() => run(() => api.startPoker(task.id), t('New round'))}>
            <RotateCcw size={14} /> {t('Restart')}
          </button>
        </div>
      )}
    </section>
  );
}
