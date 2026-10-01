import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Activity, CloudUpload, Database, HardDrive, RefreshCw, Server } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { fileSize, formatDate, timeAgo } from '../../format';
import { ErrorBanner, Spinner } from '../States';
import type { SystemInfo } from '../../types';
import { t } from '../../i18n';

function uptime(seconds: number) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
}

/** Health, storage, off-site backups, quotas and updates for admins. */
export function SystemPanel() {
  const toast = useToast();
  const [info, setInfo] = useState<SystemInfo | null>(null);
  const [error, setError] = useState('');
  const [projectMb, setProjectMb] = useState('0');
  const [totalMb, setTotalMb] = useState('0');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.system().then((s) => {
      setInfo(s);
      setProjectMb(String(s.quota.projectMb));
      setTotalMb(String(s.quota.totalMb));
    }).catch((e: ApiError) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  if (error) return <ErrorBanner message={error} onRetry={load} />;
  if (!info) return <Spinner />;

  const run = async (action: () => Promise<unknown>, message: string) => {
    setBusy(true);
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const saveQuotas = (event: FormEvent) => {
    event.preventDefault();
    run(() => api.setQuotas(Number(projectMb) || 0, Number(totalMb) || 0), t('Storage limits saved'));
  };

  const diskUsed = info.disk.totalBytes ? Math.round(((info.disk.totalBytes - info.disk.freeBytes) / info.disk.totalBytes) * 100) : 0;
  const heap = info.jvm.heapMaxBytes ? Math.round((info.jvm.heapUsedBytes / info.jvm.heapMaxBytes) * 100) : 0;
  const onOff = (on: boolean) => (on ? t('on') : t('off'));

  return (
    <>
      {info.update.available && (
        <div className="alert info update-banner">
          {t('FakeJIRA {latest} is available (you run {current}).', { latest: info.update.latest ?? '', current: info.update.current })}{' '}
          {info.update.url && <a href={info.update.url} target="_blank" rel="noreferrer noopener">{t("What's new")}</a>}
        </div>
      )}
      <div className="stats system-stats">
        <div className="stat panel"><span className="muted"><Server size={14} /> {t('Version')}</span><strong className="stat-text">{info.version}</strong>
          <span className="muted small">{t('up {time} · Java {java}', { time: uptime(info.uptimeSeconds), java: info.jvm.java })}</span></div>
        <div className="stat panel"><span className="muted"><Database size={14} /> {t('Database')}</span>
          <strong className={`stat-text ${info.database.ok ? '' : 'overdue-text'}`}>{info.database.ok ? info.database.product : t('Unreachable')}</strong>
          <span className="muted small">{info.database.sizeBytes !== null ? fileSize(info.database.sizeBytes) : info.database.version}</span></div>
        <div className="stat panel"><span className="muted"><HardDrive size={14} /> {t('Disk')}</span><strong>{diskUsed}%</strong>
          <span className="muted small">{t('{size} free', { size: fileSize(info.disk.freeBytes) })}</span></div>
        <div className="stat panel"><span className="muted"><Activity size={14} /> {t('Memory')}</span><strong>{heap}%</strong>
          <span className="muted small">{t('{used} of {max}', { used: fileSize(info.jvm.heapUsedBytes), max: fileSize(info.jvm.heapMaxBytes) })}</span></div>
      </div>

      <section className="panel">
        <h2 className="panel-title">{t('Contents')}</h2>
        <table className="viz-table system-counts">
          <tbody>
            <tr><th scope="row">{t('People')}</th><td className="num">{info.counts.users}</td></tr>
            <tr><th scope="row">{t('Projects')}</th><td className="num">{info.counts.projects}</td></tr>
            <tr><th scope="row">{t('Tasks')}</th><td className="num">{info.counts.tasks} <span className="muted small">({t('{n} open', { n: info.counts.openTasks })})</span></td></tr>
            <tr><th scope="row">{t('Comments')}</th><td className="num">{info.counts.comments}</td></tr>
            <tr><th scope="row">{t('Attachments')}</th><td className="num">{info.counts.attachments} · {fileSize(info.counts.attachmentBytes)}</td></tr>
          </tbody>
        </table>
        <p className="muted small">
          {t('Email')} {onOff(info.features.mail)} · {t('inbound email')} {onOff(info.features.inboundMail)} ·{' '}
          {t('metrics')} {info.features.metrics ? <>{t('on at')} <code>/api/metrics</code></> : t('off (set APP_METRICS_TOKEN)')} ·{' '}
          {t('health check at')} <code>/api/health</code>
        </p>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2 className="panel-title"><CloudUpload size={16} /> {t('Off-site backups')}</h2>
          {info.features.offsiteBackups && (
            <button className="btn btn-soft btn-sm" disabled={busy} onClick={() => run(async () => {
              const status = await api.uploadOffsite();
              if (status.lastError) throw new Error(status.lastError);
            }, t('Latest backup copied off-site'))}>{t('Copy latest backup now')}</button>
          )}
        </div>
        {!info.features.offsiteBackups ? (
          <p className="muted small">{t('Not set up. Copy every backup to S3-compatible storage (APP_BACKUP_S3_ENDPOINT, _BUCKET, _ACCESS_KEY, _SECRET_KEY) and/or a WebDAV folder such as Nextcloud (APP_BACKUP_WEBDAV_URL, _USERNAME, _PASSWORD). See the README.')}</p>
        ) : (
          <ul className="mini-list">
            {info.offsite.s3 && <li><span>S3</span><code className="small">{info.offsite.s3Target}</code></li>}
            {info.offsite.webdav && <li><span>WebDAV</span><code className="small">{info.offsite.webdavTarget}</code></li>}
            <li>
              <span>{t('Last copy')}</span>
              {info.offsite.lastUploadAt
                ? <span className={info.offsite.lastError ? 'overdue-text' : 'ok-text'}>
                  {info.offsite.lastFile} · {timeAgo(info.offsite.lastUploadAt)} · {info.offsite.lastError ?? t('OK')}</span>
                : <span className="muted">{t('none since the app started')}</span>}
            </li>
          </ul>
        )}
        {info.backups.length > 0 && <p className="muted small">{t('Newest local backup: {name} ({date})', { name: info.backups[0].name, date: formatDate(info.backups[0].createdAt) })}</p>}
      </section>

      <section className="panel">
        <h2 className="panel-title">{t('Attachment storage')}</h2>
        <form className="form-grid three quota-form" onSubmit={saveQuotas}>
          <label className="field">
            <span>{t('Limit per project (MB)')}</span>
            <input type="number" min={0} value={projectMb} onChange={(e) => setProjectMb(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Limit for the server (MB)')}</span>
            <input type="number" min={0} value={totalMb} onChange={(e) => setTotalMb(e.target.value)} />
          </label>
          <div className="quota-save"><button className="btn btn-soft" disabled={busy}>{t('Save limits')}</button></div>
        </form>
        <p className="muted small">{t('0 means unlimited. Uploads that would go over a limit are refused with an explanation.')}</p>
        {info.storageByProject.length > 0 && (
          <table className="viz-table">
            <thead><tr><th>{t('Project')}</th><th className="num">{t('Files')}</th><th className="num">{t('Size')}</th></tr></thead>
            <tbody>
              {info.storageByProject.map((p) => (
                <tr key={p.key}><td>{p.key} · {p.name}</td><td className="num">{p.files}</td><td className="num">{fileSize(p.bytes)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2 className="panel-title">{t('Updates')}</h2>
          <button className="btn btn-ghost btn-sm" disabled={busy || !info.update.enabled}
            onClick={() => run(() => api.checkForUpdate(), t('Checked for updates'))}><RefreshCw size={14} /> {t('Check for updates')}</button>
        </div>
        <p className="muted small">
          {!info.update.enabled ? t('Update checks are off (APP_UPDATE_CHECK=false).')
            : info.update.available ? t('Version {v} is available.', { v: info.update.latest ?? '' })
              : info.update.error ? t('Could not check: {error}.', { error: info.update.error })
                : info.update.checkedAt ? t('You are up to date (checked {when}).', { when: timeAgo(info.update.checkedAt) }) : t('Not checked yet.')}
        </p>
      </section>
    </>
  );
}
