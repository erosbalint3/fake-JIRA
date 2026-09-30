import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Heart } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { Avatar } from '../Avatar';
import { timeAgo } from '../../format';
import type { KudosEntry, Task, User } from '../../types';
import { t } from '../../i18n';

const EMOJI = ['🎉', '🙏', '🚀', '💪', '⭐', '❤️'];

/** On finished tasks: thank the people who did the work. */
export function KudosPanel({ task, me, canThank }: { task: Task; me: User; canThank: boolean }) {
  const toast = useToast();
  const [kudos, setKudos] = useState<KudosEntry[]>([]);
  const [to, setTo] = useState<number | null>(null);
  const [message, setMessage] = useState('');
  const [emoji, setEmoji] = useState('🎉');

  const load = useCallback(() => {
    api.taskKudos(task.id).then(setKudos).catch(() => {});
  }, [task.id]);
  useEffect(load, [load]);

  if (task.status !== 'DONE') return null;
  const people = [task.assignee, ...task.helpers].filter((u): u is User => !!u && u.id !== me.id);
  if (!kudos.length && (!canThank || !people.length)) return null;

  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (to === null) return;
    try {
      const k = await api.giveKudos(task.id, to, message.trim(), emoji);
      setKudos([...kudos, k]);
      setTo(null);
      setMessage('');
      toast(t('Kudos sent {emoji}', { emoji }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="side-section kudos-panel">
      <h3 className="side-title"><Heart size={15} /> {t('Kudos')}</h3>
      {kudos.map((k) => (
        <p key={k.id} className="kudos-line small">
          <span className="kudos-emoji" aria-hidden>{k.emoji}</span> <strong>{k.from.displayName}</strong> → <strong>{k.to.displayName}</strong>
          {' '}“{k.message}” <span className="muted">{timeAgo(k.createdAt)}</span>
        </p>
      ))}
      {canThank && people.length > 0 && to === null && (
        <div className="chip-row">
          {people.map((p) => (
            <button key={p.id} className="btn btn-soft btn-sm" onClick={() => setTo(p.id)}>
              <Avatar user={p} size={18} /> {t('Thank {name}', { name: p.displayName })}
            </button>
          ))}
        </div>
      )}
      {to !== null && (
        <form className="approval-form" onSubmit={send}>
          <div className="emoji-picks" role="radiogroup" aria-label={t('Emoji')}>
            {EMOJI.map((e) => (
              <button key={e} type="button" role="radio" aria-checked={emoji === e} className={`emoji-pick ${emoji === e ? 'active' : ''}`}
                onClick={() => setEmoji(e)}>{e}</button>
            ))}
          </div>
          <input value={message} maxLength={280} autoFocus placeholder={t('What made it great? (optional)')}
            aria-label={t('Message')} onChange={(e) => setMessage(e.target.value)} />
          <div className="button-row">
            <button className="btn btn-primary btn-sm">{t('Send kudos')}</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setTo(null)}>{t('Cancel')}</button>
          </div>
        </form>
      )}
    </section>
  );
}
