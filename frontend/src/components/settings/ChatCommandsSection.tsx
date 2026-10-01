import { useEffect, useState, type FormEvent } from 'react';
import { Copy, Terminal } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { ChatCommandSettings } from '../../types';
import { t } from '../../i18n';

type Field = 'slackSigningSecret' | 'slackBotToken' | 'mattermostToken' | 'discordPublicKey';

/** Slash commands (/fakejira WEB-12, search, create) in Slack, Mattermost and Discord, and Slack link previews. */
export function ChatCommandsSection({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [settings, setSettings] = useState<ChatCommandSettings | null>(null);
  const [values, setValues] = useState<Record<Field, string>>({ slackSigningSecret: '', slackBotToken: '', mattermostToken: '', discordPublicKey: '' });
  const [error, setError] = useState('');

  useEffect(() => {
    api.chatCommands(projectKey).then(setSettings).catch(() => {});
  }, [projectKey]);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    const changed = Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== '')) as Partial<Record<Field, string>>;
    try {
      setSettings(await api.saveChatCommands(projectKey, changed));
      setValues({ slackSigningSecret: '', slackBotToken: '', mattermostToken: '', discordPublicKey: '' });
      toast(t('Chat commands saved'));
    } catch (e) {
      const err = e as ApiError;
      setError(Object.values(err.fieldErrors ?? {})[0] ?? err.message);
    }
  };

  const remove = async (field: Field) => {
    try {
      setSettings(await api.saveChatCommands(projectKey, { [field]: '' }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast(t('Copied'));
    } catch {
      toast(t('Could not copy; select the text instead.'), 'error');
    }
  };

  if (!settings) return null;
  const row = (field: Field, label: string, placeholder: string) => (
    <div className="field">
      <label htmlFor={`cc-${field}`}>{label} {settings[field] && <span className="muted small">({t('set')})</span>}</label>
      <div className="copy-field">
        <input id={`cc-${field}`} type="password" autoComplete="off" value={values[field]} placeholder={settings[field] ? '••••••••' : placeholder}
          onChange={(e) => setValues({ ...values, [field]: e.target.value })} />
        {settings[field] && <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(field)}>{t('Remove')}</button>}
      </div>
    </div>
  );
  const url = (label: string, value: string) => (
    <div className="field">
      <span className="small">{label}</span>
      <div className="copy-field">
        <input readOnly value={value} aria-label={label} onFocus={(e) => e.target.select()} />
        <button type="button" className="icon-button" aria-label={t('Copy {what}', { what: label })} onClick={() => copy(value)}><Copy size={15} /></button>
      </div>
    </div>
  );

  return (
    <section className="panel" id="chat-commands">
      <h2 className="panel-title"><Terminal size={16} aria-hidden /> {t('Chat commands')}</h2>
      <p className="muted small hint">
        {t('Let people look up (KEY-12), search and create tasks from chat with a slash command, and show previews of task links in Slack. Tasks created from chat are created in your name.')}
      </p>
      <form className="form" onSubmit={save}>
        {error && <div className="alert" role="alert">{error}</div>}
        <h3 className="subheading">Slack</h3>
        {url(t('Slash command request URL'), settings.slackCommandUrl)}
        {url(t('Event subscriptions request URL (link_shared)'), settings.slackEventsUrl)}
        <div className="form-grid two">
          {row('slackSigningSecret', t('Signing secret'), '8f7…')}
          {row('slackBotToken', t('Bot token (for link previews)'), 'xoxb-…')}
        </div>
        <h3 className="subheading">Mattermost</h3>
        {url(t('Slash command request URL'), settings.mattermostCommandUrl)}
        {row('mattermostToken', t('Slash command token'), 'abc123…')}
        <h3 className="subheading">Discord</h3>
        {url(t('Interactions endpoint URL'), settings.discordInteractionsUrl)}
        {row('discordPublicKey', t('Application public key'), '64 hex characters')}
        <div><button className="btn btn-soft">{t('Save')}</button></div>
      </form>
    </section>
  );
}
