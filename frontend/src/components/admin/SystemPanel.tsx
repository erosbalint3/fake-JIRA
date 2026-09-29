import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Activity, CloudUpload, Database, HardDrive, RefreshCw, Server } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { fileSize, formatDate, timeAgo } from '../../format';
import { ErrorBanner, Spinner } from '../States';
import type { SystemInfo } from '../../types';

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
    run(() => api.setQuotas(Number(projectMb) || 0, Number(totalMb) || 0), 'Storage limits saved');
  };

  const diskUsed = info.disk.totalBytes ? Math.round(((info.disk.totalBytes - info.disk.freeBytes) / info.disk.totalBytes) * 100) : 0;
  const heap = info.jvm.heapMaxBytes ? Math.round((info.jvm.heapUsedBytes / info.jvm.heapMaxBytes) * 100) : 0;

  return (
    <>
      {info.update.available && (
        <div className="alert info update-banner">
          FakeJIRA <b>{info.update.latest}</b> is available (you run {info.update.current}).{' '}
          {info.update.url && <a href={info.update.url} target="_blank" rel="noreferrer noopener">What's new</a>}
        </div>
      )}
      <div className="stats system-stats">
        <div className="stat panel"><span className="muted"><Server size={14} /> Version</span><strong className="stat-text">{info.version}</strong>
          <span className="muted small">up {uptime(info.uptimeSeconds)} · Java {info.jvm.java}</span></div>
        <div className="stat panel"><span className="muted"><Database size={14} /> Database</span>
          <strong className={`stat-text ${info.database.ok ? '' : 'overdue-text'}`}>{info.database.ok ? info.database.product : 'Unreachable'}</strong>
          <span className="muted small">{info.database.sizeBytes !== null ? fileSize(info.database.sizeBytes) : info.database.version}</span></div>
        <div className="stat panel"><span className="muted"><HardDrive size={14} /> Disk</span><strong>{diskUsed}%</strong>
          <span className="muted small">{fileSize(info.disk.freeBytes)} free</span></div>
        <div className="stat panel"><span className="muted"><Activity size={14} /> Memory</span><strong>{heap}%</strong>
          <span className="muted small">{fileSize(info.jvm.heapUsedBytes)} of {fileSize(info.jvm.heapMaxBytes)}</span></div>
      </div>

      <section className="panel">
        <h2 className="panel-title">Contents</h2>
        <table className="viz-table system-counts">
          <tbody>
            <tr><th scope="row">People</th><td className="num">{info.counts.users}</td></tr>
            <tr><th scope="row">Projects</th><td className="num">{info.counts.projects}</td></tr>
            <tr><th scope="row">Tasks</th><td className="num">{info.counts.tasks} <span className="muted small">({info.counts.openTasks} open)</span></td></tr>
            <tr><th scope="row">Comments</th><td className="num">{info.counts.comments}</td></tr>
            <tr><th scope="row">Attachments</th><td className="num">{info.counts.attachments} · {fileSize(info.counts.attachmentBytes)}</td></tr>
          </tbody>
        </table>
        <p className="muted small">
          Email {info.features.mail ? 'on' : 'off'} · inbound email {info.features.inboundMail ? 'on' : 'off'} ·
          metrics {info.features.metrics ? <>on at <code>/api/metrics</code></> : 'off (set APP_METRICS_TOKEN)'} ·
          health check at <code>/api/health</code>
        </p>
      </section>

      <section className="panel">
        <div className="panel-head">
          <h2 className="panel-title"><CloudUpload size={16} /> Off-site backups</h2>
          {info.features.offsiteBackups && (
            <button className="btn btn-soft btn-sm" disabled={busy} onClick={() => run(async () => {
              const status = await api.uploadOffsite();
              if (status.lastError) throw new Error(status.lastError);
            }, 'Latest backup copied off-site')}>Copy latest backup now</button>
          )}
        </div>
        {!info.features.offsiteBackups ? (
          <p className="muted small">Not set up. Copy every backup to S3-compatible storage (APP_BACKUP_S3_ENDPOINT, _BUCKET, _ACCESS_KEY,
            _SECRET_KEY) and/or a WebDAV folder such as Nextcloud (APP_BACKUP_WEBDAV_URL, _USERNAME, _PASSWORD). See the README.</p>
        ) : (
          <ul className="mini-list">
            {info.offsite.s3 && <li><span>S3</span><code className="small">{info.offsite.s3Target}</code></li>}
            {info.offsite.webdav && <li><span>WebDAV</span><code className="small">{info.offsite.webdavTarget}</code></li>}
            <li>
              <span>Last copy</span>
              {info.offsite.lastUploadAt
                ? <span className={info.offsite.lastError ? 'overdue-text' : 'ok-text'}>
                  {info.offsite.lastFile} · {timeAgo(info.offsite.lastUploadAt)} · {info.offsite.lastError ?? 'OK'}</span>
                : <span className="muted">none since the app started</span>}
            </li>
          </ul>
        )}
        {info.backups.length > 0 && <p className="muted small">Newest local backup: {info.backups[0].name} ({formatDate(info.backups[0].createdAt)})</p>}
      </section>

      <section className="panel">
        <h2 className="panel-title">Attachment storage</h2>
        <form className="form-grid three quota-form" onSubmit={saveQuotas}>
          <label className="field">
            <span>Limit per project (MB)</span>
            <input type="number" min={0} value={projectMb} onChange={(e) => setProjectMb(e.target.value)} />
          </label>
          <label className="field">
            <span>Limit for the server (MB)</span>
            <input type="number" min={0} value={totalMb} onChange={(e) => setTotalMb(e.target.value)} />
          </label>
          <div className="quota-save"><button className="btn btn-soft" disabled={busy}>Save limits</button></div>
        </form>
        <p className="muted small">0 means unlimited. Uploads that would go over a limit are refused with an explanation.</p>
        {info.storageByProject.length > 0 && (
          <table className="viz-table">
            <thead><tr><th>Project</th><th className="num">Files</th><th className="num">Size</th></tr></thead>
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
          <h2 className="panel-title">Updates</h2>
          <button className="btn btn-ghost btn-sm" disabled={busy || !info.update.enabled}
            onClick={() => run(() => api.checkForUpdate(), 'Checked for updates')}><RefreshCw size={14} /> Check now</button>
        </div>
        <p className="muted small">
          {!info.update.enabled ? 'Update checks are off (APP_UPDATE_CHECK=false).'
            : info.update.available ? <>Version {info.update.latest} is available.</>
              : info.update.error ? <>Could not check: {info.update.error}.</>
                : info.update.checkedAt ? <>You are up to date (checked {timeAgo(info.update.checkedAt)}).</> : 'Not checked yet.'}
        </p>
      </section>
    </>
  );
}
