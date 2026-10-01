import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { BarChart2, Lock, Plus, Trash2, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { useLiveRefresh } from '../../live';
import type { Poll } from '../../types';
import { t } from '../../i18n';

/** Quick votes on a task; closing one can put the winner into the project's decision log. */
export function PollsPanel({ taskId, canCreate, canVote, startSignal }: {
  taskId: number;
  canCreate: boolean;
  canVote: boolean;
  /** Changes whenever "Start a poll" is chosen elsewhere (e.g. the task's action menu). */
  startSignal: number;
}) {
  const toast = useToast();
  const [polls, setPolls] = useState<Poll[]>([]);
  const [creating, setCreating] = useState(false);
  const [question, setQuestion] = useState('');
  const [options, setOptions] = useState(['', '']);
  const [multiple, setMultiple] = useState(false);

  useEffect(() => {
    if (startSignal > 0 && canCreate) setCreating(true);
  }, [startSignal, canCreate]);

  const load = useCallback(() => {
    api.polls(taskId).then(setPolls).catch(() => {});
  }, [taskId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, load, 400);

  const replace = (poll: Poll) => setPolls((list) => list.map((p) => (p.id === poll.id ? poll : p)));

  const create = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const poll = await api.createPoll(taskId, question.trim(), options.map((o) => o.trim()).filter(Boolean), multiple);
      setPolls([...polls, poll]);
      setCreating(false);
      setQuestion('');
      setOptions(['', '']);
      setMultiple(false);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const vote = async (poll: Poll, index: number) => {
    const mine = poll.options.map((o, i) => (o.mine ? i : -1)).filter((i) => i >= 0);
    const next = poll.multiple
      ? (mine.includes(index) ? mine.filter((i) => i !== index) : [...mine, index])
      : (mine.includes(index) ? [] : [index]);
    try {
      replace(await api.vote(poll.id, next));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!polls.length && !creating) return null;

  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title"><BarChart2 size={16} /> {t('Polls')}</h2>
        {canCreate && !creating && (
          <button className="btn btn-ghost btn-sm" onClick={() => setCreating(true)}><Plus size={14} /> {t('Start a poll')}</button>
        )}
      </div>
      {polls.map((poll) => {
        const top = Math.max(0, ...poll.options.map((o) => o.votes));
        const closed = !!poll.closedAt;
        return (
          <div key={poll.id} className={`poll ${closed ? 'closed' : ''}`}>
            <div className="poll-head">
              <strong>{poll.question}</strong>
              <span className="muted small">
                {closed ? <><Lock size={12} /> {t('closed')}</> : poll.multiple ? t('pick any') : t('pick one')}
                {' · '}{t('{n} voted', { n: poll.voters })}
              </span>
              {poll.canClose && !closed && (
                <span className="poll-actions">
                  <button className="link small" onClick={async () => replace(await api.closePoll(poll.id, true))}>
                    {t('Close and record decision')}</button>
                  <button className="link small" onClick={async () => replace(await api.closePoll(poll.id, false))}>{t('Close')}</button>
                </span>
              )}
              {poll.canClose && (
                <button className="icon-button sm" aria-label={t('Delete poll')} onClick={async () => {
                  await api.deletePoll(poll.id).catch((e: ApiError) => toast(e.message, 'error'));
                  load();
                }}><Trash2 size={13} /></button>
              )}
            </div>
            <ul className="poll-options">
              {poll.options.map((option, index) => {
                const share = poll.voters ? Math.round((option.votes / poll.voters) * 100) : 0;
                return (
                  <li key={option.text}>
                    <button className={`poll-option ${option.mine ? 'mine' : ''} ${closed && option.votes === top && top > 0 ? 'winner' : ''}`}
                      disabled={closed || !canVote} aria-pressed={option.mine}
                      title={option.voters.join(', ') || undefined}
                      onClick={() => vote(poll, index)}>
                      <span className="poll-fill" style={{ width: `${share}%` }} />
                      <span className="poll-text">{option.text}</span>
                      <span className="poll-count">{option.votes}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
      {creating && (
        <form className="form poll-form" onSubmit={create}>
          <label className="field"><span>{t('Question')}</span>
            <input value={question} maxLength={200} autoFocus placeholder={t('e.g. Which layout should we ship?')}
              onChange={(e) => setQuestion(e.target.value)} /></label>
          {options.map((option, i) => (
            <div key={i} className="poll-option-input">
              <input value={option} maxLength={100} aria-label={t('Option {n}', { n: i + 1 })} placeholder={t('Option {n}', { n: i + 1 })}
                onChange={(e) => setOptions(options.map((o, j) => (j === i ? e.target.value : o)))} />
              {options.length > 2 && (
                <button type="button" className="icon-button sm" aria-label={t('Remove option {n}', { n: i + 1 })}
                  onClick={() => setOptions(options.filter((_, j) => j !== i))}><X size={13} /></button>
              )}
            </div>
          ))}
          {options.length < 10 && <button type="button" className="link small" onClick={() => setOptions([...options, ''])}>+ {t('Add option')}</button>}
          <label className="toggle"><input type="checkbox" checked={multiple} onChange={(e) => setMultiple(e.target.checked)} /> {t('Allow several choices')}</label>
          <div className="button-row">
            <button className="btn btn-primary btn-sm" disabled={!question.trim() || options.filter((o) => o.trim()).length < 2}>{t('Start poll')}</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setCreating(false)}>{t('Cancel')}</button>
          </div>
        </form>
      )}
    </section>
  );
}
