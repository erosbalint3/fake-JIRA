import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, LifeBuoy, Send } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';
import type { PortalConversation, SimilarTask, Task } from '../../types';
import { Markdown } from '../Markdown';
import { t } from '../../i18n';

/**
 * For tasks that came from the portal or widget: who asked, their answers, and the conversation with them.
 * Replies here are emailed to the requester and shown on their tracking page; comments stay internal.
 */
export function RequesterPanel({ task, canEdit }: { task: Task; canEdit: boolean }) {
  const toast = useToast();
  const [data, setData] = useState<PortalConversation | null>(null);
  const [similar, setSimilar] = useState<SimilarTask[]>([]);
  const [reply, setReply] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.portalConversation(task.id).then((c) => setData(c ?? null)).catch(() => setData(null));
  }, [task.id]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === task.id, load, 500);
  useEffect(() => {
    if (!data) return;
    api.similarTasks(task.projectKey, `${task.title} ${task.description.slice(0, 400)}`, task.id)
      .then(setSimilar).catch(() => setSimilar([]));
  }, [data, task.id, task.projectKey, task.title, task.description]);

  if (!data) return null;

  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (!reply.trim()) return;
    setBusy(true);
    try {
      setData(await api.replyToRequester(task.id, reply.trim()));
      setReply('');
      toast(data.mailEnabled ? t('Reply sent to {email}', { email: data.requesterEmail }) : t('Reply posted on the request page'));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel requester-panel" aria-labelledby="requester-title">
      <h2 className="panel-title" id="requester-title"><LifeBuoy size={16} aria-hidden /> {t('Requester')}</h2>
      <p>
        <strong>{data.requesterName}</strong> <a href={`mailto:${data.requesterEmail}`}>{data.requesterEmail}</a>
        <span className="muted small"> · {data.channel === 'widget' ? t('via the feedback widget') : t('via the portal')}
          {data.requestType && ` · ${data.requestType}`} · {timeAgo(data.createdAt)}</span>
      </p>
      {data.answers.length > 0 && (
        <dl className="answers">
          {data.answers.map((a) => <div key={a.label}><dt>{a.label}</dt><dd>{a.value}</dd></div>)}
        </dl>
      )}
      <p className="small"><a href={data.trackingUrl} target="_blank" rel="noreferrer"><ExternalLink size={13} aria-hidden /> {t('Their request page')}</a></p>
      {similar.length > 0 && (
        <div className="alert info">
          <strong>{t('Possible duplicates:')}</strong>
          <ul className="plain-list">
            {similar.map((s) => (
              <li key={s.task.id}><Link to={`/tasks/${s.task.id}`}>{s.task.key}</Link> {s.task.title}
                {s.done && <span className="muted small"> · {t('done')}</span>}</li>
            ))}
          </ul>
        </div>
      )}
      <h3 className="subheading">{t('Conversation with the requester')}</h3>
      {data.messages.length === 0 && <p className="muted small">{t('No messages yet. Replies here are visible to the requester; comments below are not.')}</p>}
      <ol className="conversation">
        {data.messages.map((m) => (
          <li key={m.id} className={m.fromRequester ? 'theirs' : 'mine'}>
            <span className="small muted">{m.fromRequester ? data.requesterName : m.author?.displayName} · {timeAgo(m.createdAt)}</span>
            <div className="bubble"><Markdown>{m.body}</Markdown></div>
          </li>
        ))}
      </ol>
      {canEdit && (
        <form className="form" onSubmit={send}>
          <label className="field">
            <span>{t('Reply to {name}', { name: data.requesterName })}</span>
            <textarea rows={3} maxLength={5000} value={reply} onChange={(e) => setReply(e.target.value)} />
          </label>
          <div><button className="btn btn-soft" disabled={busy || !reply.trim()}><Send size={16} /> {t('Send reply')}</button></div>
        </form>
      )}
    </section>
  );
}
