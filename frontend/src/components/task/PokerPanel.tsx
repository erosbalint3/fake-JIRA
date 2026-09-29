import { useCallback, useEffect, useState } from 'react';
import { Check, Dices, RotateCcw, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { Avatar } from '../Avatar';
import type { PokerState, Task } from '../../types';

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
        <button className="btn btn-ghost btn-sm btn-block" onClick={() => run(() => api.startPoker(task.id), 'Planning poker started')}>
          <Dices size={15} /> Estimate with planning poker
        </button>
      </div>
    );
  }

  const numeric = state.deck.filter((c) => /^\d+$/.test(c));
  return (
    <section className="poker" aria-label="Planning poker">
      <header>
        <h3><Dices size={15} /> Planning poker</h3>
        {canEdit && (
          <button className="icon-button sm" aria-label="Cancel planning poker" title="Cancel"
            onClick={() => run(() => api.cancelPoker(task.id), 'Planning poker cancelled')}><X size={15} /></button>
        )}
      </header>
      {!state.revealed ? (
        <>
          <p className="muted small">{state.votes.length ? `${state.votes.length} voted` : 'Nobody has voted yet'} · votes stay hidden until revealed</p>
          {canEdit && (
            <div className="poker-deck" role="radiogroup" aria-label="Your estimate">
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
          {state.average !== null ? <>Average <strong>{state.average}</strong> · suggested <strong>{state.suggestion}</strong></> : 'No numeric votes.'}
          {state.consensus && <span className="chip success">Consensus!</span>}
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
              onClick={() => run(() => api.revealPoker(task.id))}>Reveal votes</button>
          ) : (
            <>
              <select value={choice} onChange={(e) => setChoice(e.target.value)} aria-label="Estimate to accept">
                {numeric.map((c) => <option key={c} value={c}>{c} pts</option>)}
              </select>
              <button className="btn btn-primary btn-sm" disabled={!choice}
                onClick={() => run(async () => {
                  const next = await api.acceptPoker(task.id, Number(choice));
                  onAccepted();
                  return next;
                }, `Estimated at ${choice} points`)}><Check size={15} /> Accept estimate</button>
            </>
          )}
          <button className="btn btn-ghost btn-sm" onClick={() => run(() => api.startPoker(task.id), 'New round')}>
            <RotateCcw size={14} /> Restart
          </button>
        </div>
      )}
    </section>
  );
}
