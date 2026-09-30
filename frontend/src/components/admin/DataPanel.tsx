import { useEffect, useState, type FormEvent } from 'react';
import { Activity, Clock, Lock } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { EncryptionStatus, HealthCheck, HealthThresholds, RetentionCounts, RetentionPolicy } from '../../types';
import { timeAgo } from '../../format';
import { t } from '../../i18n';

const RULES: [keyof RetentionPolicy, string][] = [
  ['archiveDoneAfterDays', 'Archive tasks this many days after they are done'],
  ['deleteArchivedAfterDays', 'Delete archived tasks after this many days (with comments, files and history)'],
  ['deleteNotificationsAfterDays', 'Delete notifications older than'],
  ['deleteAuditAfterDays', 'Delete audit log entries older than'],
];

/** Retention rules and attachment encryption. */
export function DataPanel() {
  const toast = useToast();
  const [policy, setPolicy] = useState<RetentionPolicy | null>(null);
  const [preview, setPreview] = useState<RetentionCounts | null>(null);
  const [encryption, setEncryption] = useState<EncryptionStatus | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.retention().then((r) => {
      setPolicy(r.policy);
      setPreview(r.preview);
    }).catch(() => {});
    api.encryptionStatus().then(setEncryption).catch(() => {});
  }, []);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!policy) return;
    setBusy(true);
    setErrors({});
    try {
      const r = await api.saveRetention(policy);
      setPolicy(r.policy);
      setPreview(r.preview);
      toast(t('Retention rules saved'));
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors(e.fieldErrors);
        toast(e.message, 'error');
      }
    } finally {
      setBusy(false);
    }
  };

  const runNow = async () => {
    setBusy(true);
    try {
      const done = await api.runRetention();
      toast(t('Archived {a} and deleted {d} tasks, {n} notifications and {l} audit entries', {
        a: done.tasksArchived, d: done.tasksDeleted, n: done.notificationsDeleted, l: done.auditEntriesDeleted }));
      const r = await api.retention();
      setPreview(r.preview);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {policy && (
        <section className="panel">
          <h2 className="panel-title"><Clock size={16} /> {t('Data retention')}</h2>
          <p className="muted small hint">{t('Days; 0 keeps things forever. Rules run every night.')}</p>
          <form className="form" onSubmit={save}>
            {RULES.map(([key, label]) => (
              <label key={key} className="inline-field retention-rule">
                <span>{t(label)}</span>
                <input type="number" min={0} className="wip-input" value={policy[key]} aria-invalid={!!errors[key]}
                  onChange={(e) => setPolicy({ ...policy, [key]: Number(e.target.value) })} />
                {errors[key] && <small className="field-error">{errors[key]}</small>}
              </label>
            ))}
            {preview && (
              <p className="muted small">{t('Right now this would archive {a} tasks, delete {d} tasks, {n} notifications and {l} audit entries.', {
                a: preview.tasksArchived, d: preview.tasksDeleted, n: preview.notificationsDeleted, l: preview.auditEntriesDeleted })}</p>
            )}
            <div className="row-actions">
              <button className="btn btn-primary" disabled={busy}>{t('Save rules')}</button>
              <button type="button" className="btn btn-ghost" disabled={busy} onClick={runNow}>{t('Run now')}</button>
            </div>
          </form>
        </section>
      )}
      {encryption && (
        <section className="panel">
          <h2 className="panel-title"><Lock size={16} /> {t('Attachment encryption')}</h2>
          {!encryption.enabled ? (
            <p className="muted small hint">{t('Off. Set APP_STORAGE_ENCRYPTION_KEY (32 random bytes, base64 — e.g. openssl rand -base64 32) and restart to encrypt files at rest.')}</p>
          ) : (
            <>
              <p className="small">{t('{e} files encrypted, {o} with an old key, {p} not encrypted.', { e: encryption.encrypted, o: encryption.oldKey, p: encryption.plain })}</p>
              {(encryption.plain > 0 || encryption.oldKey > 0) && (
                <button className="btn btn-soft sm" disabled={busy} onClick={async () => {
                  setBusy(true);
                  try {
                    const r = await api.encryptAll();
                    setEncryption(r.status);
                    toast(t('{n} files encrypted', { n: r.changed }));
                  } catch (e) {
                    toast((e as ApiError).message, 'error');
                  } finally {
                    setBusy(false);
                  }
                }}>{t('Encrypt all files now')}</button>
              )}
            </>
          )}
        </section>
      )}
    </>
  );
}

/** Server health checks and alert thresholds. */
export function HealthPanel() {
  const toast = useToast();
  const [checks, setChecks] = useState<HealthCheck[] | null>(null);
  const [thresholds, setThresholds] = useState<HealthThresholds | null>(null);

  useEffect(() => {
    api.health().then((h) => {
      setChecks(h.checks);
      setThresholds(h.thresholds);
    }).catch(() => {});
  }, []);

  if (!checks || !thresholds) return null;
  const save = async (event: FormEvent) => {
    event.preventDefault();
    try {
      const h = await api.saveHealth(thresholds);
      setChecks(h.checks);
      setThresholds(h.thresholds);
      toast(t('Alert settings saved'));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };
  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title"><Activity size={16} /> {t('Health')}</h2>
        <button className="btn btn-soft sm" onClick={async () => setChecks((await api.checkHealth()).checks)}>{t('Run health checks')}</button>
      </div>
      <ul className="mini-list health-checks">
        {checks.map((c) => (
          <li key={c.id}>
            <span className={`health-dot ${c.ok ? 'ok' : 'bad'}`} aria-hidden />
            <strong>{t(c.label)}</strong>
            <span className="grow muted small">{c.detail}</span>
            <span className={c.ok ? 'muted small' : 'small health-bad'}>{c.ok ? t('OK') : t('Needs attention')}
              {c.since ? ` · ${timeAgo(c.since)}` : ''}</span>
          </li>
        ))}
      </ul>
      <form className="form" onSubmit={save}>
        <div className="policy-grid">
          <label className="inline-field">{t('Alert below free disk (MB)')}
            <input type="number" min={0} className="wip-input" value={thresholds.minFreeDiskMb}
              onChange={(e) => setThresholds({ ...thresholds, minFreeDiskMb: Number(e.target.value) })} />
          </label>
          <label className="inline-field">{t('Alert when the newest backup is older than (hours)')}
            <input type="number" min={1} className="wip-input" value={thresholds.maxBackupAgeHours}
              onChange={(e) => setThresholds({ ...thresholds, maxBackupAgeHours: Number(e.target.value) })} />
          </label>
          <label className="inline-field">{t('Alert at server errors per 10 minutes')}
            <input type="number" min={1} className="wip-input" value={thresholds.maxServerErrors}
              onChange={(e) => setThresholds({ ...thresholds, maxServerErrors: Number(e.target.value) })} />
          </label>
          <label className="inline-field">{t('Alert at memory use (%)')}
            <input type="number" min={50} max={99} className="wip-input" value={thresholds.maxHeapPercent}
              onChange={(e) => setThresholds({ ...thresholds, maxHeapPercent: Number(e.target.value) })} />
          </label>
        </div>
        <label className="toggle">
          <input type="checkbox" checked={thresholds.alerts} onChange={(e) => setThresholds({ ...thresholds, alerts: e.target.checked })} />
          {t('Notify and email admins when a check fails or recovers')}
        </label>
        <div><button className="btn btn-primary">{t('Save alert settings')}</button></div>
      </form>
    </section>
  );
}
