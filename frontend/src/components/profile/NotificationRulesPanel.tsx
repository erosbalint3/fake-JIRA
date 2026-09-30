import { useEffect, useState, type FormEvent } from 'react';
import { BellDot, Moon, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useProjects } from '../../projects';
import { useToast } from '../../toast';
import type { NotificationLevel, NotificationRule, NotificationSettings } from '../../types';
import { t } from '../../i18n';

export const LEVEL_LABEL: Record<NotificationLevel, string> = {
  ALL: 'Everything',
  DIRECT: 'Only things for me',
  MUTED: 'Nothing (muted)',
};

const LEVEL_HINT: Record<NotificationLevel, string> = {
  ALL: 'Every update on tasks you report, are assigned to, help with or watch.',
  DIRECT: 'Mentions, assignments, replies to you, approvals and reminders.',
  MUTED: 'No notifications at all, except your own reminders.',
};

type Rule = { level: NotificationLevel; email: boolean | null; push: boolean | null };

function triState(value: boolean | null) {
  return value === null ? '' : value ? 'on' : 'off';
}

function fromTriState(value: string) {
  return value === '' ? null : value === 'on';
}

function zones(): string[] {
  const intl = Intl as unknown as { supportedValuesOf?: (key: string) => string[] };
  try {
    return intl.supportedValuesOf ? intl.supportedValuesOf('timeZone') : [];
  } catch {
    return [];
  }
}

function RuleControls({ rule, onChange, label }: { rule: Rule; onChange: (rule: Rule) => void; label: string }) {
  return (
    <div className="rule-controls">
      <select value={rule.level} aria-label={`${label}: ${t('What to hear about')}`}
        onChange={(e) => onChange({ ...rule, level: e.target.value as NotificationLevel })}>
        {(Object.keys(LEVEL_LABEL) as NotificationLevel[]).map((l) => <option key={l} value={l}>{t(LEVEL_LABEL[l])}</option>)}
      </select>
      <select value={triState(rule.email)} aria-label={`${label}: ${t('Email')}`} disabled={rule.level === 'MUTED'}
        onChange={(e) => onChange({ ...rule, email: fromTriState(e.target.value) })}>
        <option value="">{t('Email: as in my email setting')}</option>
        <option value="on">{t('Email: right away')}</option>
        <option value="off">{t('Email: never')}</option>
      </select>
      <select value={triState(rule.push)} aria-label={`${label}: ${t('Push')}`} disabled={rule.level === 'MUTED'}
        onChange={(e) => onChange({ ...rule, push: fromTriState(e.target.value) })}>
        <option value="">{t('Push: default (on)')}</option>
        <option value="on">{t('Push: on')}</option>
        <option value="off">{t('Push: off')}</option>
      </select>
    </div>
  );
}

/** What to be notified about, per project, and when to stay quiet. */
export function NotificationRulesPanel() {
  const toast = useToast();
  const { projects } = useProjects();
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [adding, setAdding] = useState('');
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const [zone, setZone] = useState(browserZone);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [quietError, setQuietError] = useState('');

  const apply = (data: NotificationSettings) => {
    setSettings(data);
    setZone(data.quietHours.timeZone ?? browserZone);
    setFrom(data.quietHours.from?.slice(0, 5) ?? '');
    setTo(data.quietHours.to?.slice(0, 5) ?? '');
  };

  useEffect(() => {
    api.notificationSettings().then(apply).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const save = async (promise: Promise<NotificationSettings>, message = t('Notification settings saved')) => {
    try {
      apply(await promise);
      toast(message);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const saveQuiet = async (event: FormEvent, clear = false) => {
    event.preventDefault();
    setQuietError('');
    try {
      apply(await api.saveQuietHours({ timeZone: zone || null, from: clear ? null : from || null, to: clear ? null : to || null }));
      toast(clear ? t('Quiet hours turned off') : t('Quiet hours saved'));
    } catch (e) {
      const err = e as ApiError;
      setQuietError(err.fieldErrors?.to ?? err.fieldErrors?.timeZone ?? err.message);
    }
  };

  useEffect(() => {
    if (settings && window.location.hash === '#notifications') {
      document.getElementById('notifications')?.scrollIntoView({ block: 'start' });
    }
  }, [settings]);

  if (!settings) return null;
  const defaults: Rule = { level: settings.defaults.level ?? 'ALL', email: settings.defaults.email, push: settings.defaults.push };
  const withRules = new Set(settings.projects.map((r) => r.projectKey));
  const available = (projects ?? []).filter((p) => !withRules.has(p.key));
  const zoneList = zones();
  const quietOn = !!settings.quietHours.from;

  return (
    <section className="panel" id="notifications">
      <h2 className="panel-title"><BellDot size={16} /> {t('Notification rules')}</h2>
      <p className="muted small hint">{t(LEVEL_HINT[defaults.level])}</p>
      <RuleControls label={t('All projects')} rule={defaults} onChange={(rule) => save(api.saveNotificationDefaults(rule))} />

      <h3 className="subheading">{t('Per project')}</h3>
      {settings.projects.length === 0 && <p className="muted small">{t('Every project follows the rule above.')}</p>}
      <ul className="rule-list">
        {settings.projects.map((rule: NotificationRule) => (
          <li key={rule.projectKey}>
            <span className="rule-project"><strong>{rule.projectKey}</strong> <span className="muted">{rule.projectName}</span></span>
            <RuleControls label={rule.projectKey ?? ''}
              rule={{ level: rule.level ?? 'ALL', email: rule.email, push: rule.push }}
              onChange={(next) => save(api.saveProjectNotificationRule(rule.projectKey!, next))} />
            <button className="icon-button" aria-label={t('Remove the rule for {key}', { key: rule.projectKey ?? '' })}
              onClick={() => save(api.deleteProjectNotificationRule(rule.projectKey!), t('Rule removed'))}><Trash2 size={15} /></button>
          </li>
        ))}
      </ul>
      {available.length > 0 && (
        <div className="inline-form">
          <select value={adding} onChange={(e) => setAdding(e.target.value)} aria-label={t('Project')}>
            <option value="">{t('Choose a project…')}</option>
            {available.map((p) => <option key={p.key} value={p.key}>{p.key} · {p.name}</option>)}
          </select>
          <button className="btn btn-soft" disabled={!adding} onClick={() => {
            save(api.saveProjectNotificationRule(adding, { level: 'DIRECT', email: null, push: null }), t('Rule added'));
            setAdding('');
          }}>{t('Add a rule')}</button>
        </div>
      )}

      <h3 className="subheading"><Moon size={15} aria-hidden /> {t('Quiet hours')}</h3>
      <p className="muted small hint">
        {quietOn
          ? t('No email or push between {from} and {to} ({zone}). Notifications still wait in your inbox.',
            { from: settings.quietHours.from!.slice(0, 5), to: settings.quietHours.to!.slice(0, 5), zone: settings.quietHours.timeZone ?? browserZone })
          : t('Pause email and push notifications at night or while you focus; they still wait in your inbox.')}
      </p>
      <form className="quiet-form" onSubmit={saveQuiet}>
        <label className="field">
          <span>{t('From')}</span>
          <input type="time" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('To')}</span>
          <input type="time" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('Time zone')}</span>
          {zoneList.length ? (
            <select value={zone} onChange={(e) => setZone(e.target.value)}>
              {zoneList.map((z) => <option key={z} value={z}>{z}</option>)}
            </select>
          ) : (
            <input value={zone} onChange={(e) => setZone(e.target.value)} />
          )}
        </label>
        <div className="quiet-actions">
          <button className="btn btn-soft" type="submit">{t('Save')}</button>
          {quietOn && <button className="btn btn-ghost" type="button" onClick={(e) => saveQuiet(e, true)}>{t('Turn off')}</button>}
        </div>
        {quietError && <small className="field-error" role="alert">{quietError}</small>}
      </form>
    </section>
  );
}
