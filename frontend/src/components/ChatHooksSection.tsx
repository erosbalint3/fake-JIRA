import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { MessageSquare, Plus, Send, Trash2 } from 'lucide-react';
import { api, ApiError, type ChatEvent, type ChatHook } from '../api';
import { useToast } from '../toast';
import { timeAgo } from '../format';
import { t } from '../i18n';

const EVENTS: { id: ChatEvent; label: string }[] = [
  { id: 'TASK_CREATED', label: 'Task created' },
  { id: 'TASK_DONE', label: 'Task done' },
  { id: 'STATUS_CHANGED', label: 'Any status change' },
  { id: 'COMMENT_ADDED', label: 'Comments' },
  { id: 'SPRINT', label: 'Sprint started / completed' },
];

/** Post project updates to Slack or Discord channels through incoming webhooks. */

const CHAT_APP: Record<ChatHook['kind'], string> = { SLACK: 'Slack', DISCORD: 'Discord', TEAMS: 'Microsoft Teams', MATTERMOST: 'Mattermost' };
const PLACEHOLDER: Record<ChatHook['kind'], string> = {
  SLACK: 'https://hooks.slack.com/services/…', DISCORD: 'https://discord.com/api/webhooks/…',
  TEAMS: 'https://…webhook.office.com/…', MATTERMOST: 'https://mattermost.example.com/hooks/…',
};

export function ChatHooksSection({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [hooks, setHooks] = useState<ChatHook[] | null>(null);
  const [kind, setKind] = useState<ChatHook['kind']>('SLACK');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.chatHooks(projectKey).then(setHooks).catch(() => setHooks([]));
  }, [projectKey]);
  useEffect(load, [load]);

  const add = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await api.addChatHook(projectKey, kind, url.trim(), ['TASK_CREATED', 'TASK_DONE', 'SPRINT']);
      setUrl('');
      toast(t('Webhook added — send a test message to check it'));
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (hook: ChatHook, event: ChatEvent) => {
    const events = hook.events.includes(event) ? hook.events.filter((e) => e !== event) : [...hook.events, event];
    try {
      const saved = await api.updateChatHook(hook.id, events);
      setHooks((list) => list?.map((h) => (h.id === hook.id ? saved : h)) ?? null);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><MessageSquare size={16} /> {t('Chat notifications')}</h2>
      <p className="muted small hint">
        {t('Create an incoming webhook in Slack (Apps → Incoming Webhooks), Discord (channel settings → Integrations → Webhooks), Microsoft Teams (channel → Workflows or Connectors → Incoming Webhook) or Mattermost (Integrations → Incoming Webhooks) and paste its URL here.')}
      </p>
      {hooks && hooks.length > 0 && (
        <ul className="hook-list">
          {hooks.map((hook) => (
            <li key={hook.id}>
              <div className="hook-head">
                <strong>{CHAT_APP[hook.kind]}</strong>
                <span className="muted small mono">{hook.url}</span>
                <span className="spacer" />
                <button className="btn btn-ghost btn-sm" onClick={async () => {
                  const result = await api.testChatHook(hook.id);
                  toast(result.delivered ? t('Test message sent') : t('Not delivered: {error}', { error: result.error ?? '' }), result.delivered ? 'success' : 'error');
                  load();
                }}><Send size={14} /> {t('Test')}</button>
                <button className="icon-button sm" aria-label={t('Remove webhook')} onClick={async () => {
                  await api.deleteChatHook(hook.id);
                  load();
                }}><Trash2 size={15} /></button>
              </div>
              <div className="chip-row">
                {EVENTS.map((event) => (
                  <label key={event.id} className={`chip chip-toggle ${hook.events.includes(event.id) ? 'on' : ''}`}>
                    <input type="checkbox" checked={hook.events.includes(event.id)} onChange={() => toggle(hook, event.id)} />
                    {t(event.label)}
                  </label>
                ))}
              </div>
              {hook.lastDeliveryAt && (
                <p className={`small ${hook.lastError ? 'field-error' : 'muted'}`}>
                  {hook.lastError ? t('Last delivery {when} failed: {error}', { when: timeAgo(hook.lastDeliveryAt), error: hook.lastError }) : t('Last delivery {when} succeeded', { when: timeAgo(hook.lastDeliveryAt) })}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
      {(hooks?.length ?? 0) < 5 && (
        <form className="inline-form hook-form" onSubmit={add}>
          <select value={kind} onChange={(e) => setKind(e.target.value as ChatHook['kind'])} aria-label={t('Chat app')}>
            <option value="SLACK">Slack</option>
            <option value="DISCORD">Discord</option>
            <option value="TEAMS">Microsoft Teams</option>
            <option value="MATTERMOST">Mattermost</option>
          </select>
          <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} aria-label={t('Webhook URL')}
            placeholder={PLACEHOLDER[kind]} />
          <button className="btn btn-soft" disabled={busy || !url.trim()}><Plus size={16} /> {t('Add')}</button>
        </form>
      )}
    </section>
  );
}
