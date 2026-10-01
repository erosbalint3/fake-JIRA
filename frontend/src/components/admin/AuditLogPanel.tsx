import { useCallback, useEffect, useState } from 'react';
import { ScrollText } from 'lucide-react';
import { api, ApiError, type AuditPage } from '../../api';
import { formatDate, timeAgo } from '../../format';
import { ErrorBanner, Spinner } from '../States';
import { t } from '../../i18n';

/** Who did what and from where: sign-ins, role and settings changes, deletions. */
export function AuditLogPanel() {
  const [action, setAction] = useState('');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AuditPage | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setError('');
    api.audit({ action: action || undefined, q: search || undefined, page }).then(setData)
      .catch((e: ApiError) => setError(e.message));
  }, [action, search, page]);
  useEffect(load, [load]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(q.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [q]);

  return (
    <section className="panel">
      <h2 className="panel-title"><ScrollText size={16} /> {t('Audit log')}</h2>
      <div className="report-toolbar">
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('Search user, target, IP…')} aria-label={t('Search the audit log')}
          className="audit-search" />
        <select value={action} onChange={(e) => {
          setAction(e.target.value);
          setPage(0);
        }} aria-label={t('Action')}>
          <option value="">{t('All actions')}</option>
          {data?.actions.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      </div>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <div className="table-scroll">
            <table className="viz-table audit-table">
              <thead><tr><th>{t('When')}</th><th>{t('Who')}</th><th>{t('Action')}</th><th>{t('Target')}</th><th>{t('Details')}</th><th>IP</th></tr></thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id}>
                    <td title={formatDate(e.createdAt)}>{timeAgo(e.createdAt)}</td>
                    <td>{e.actor ?? '—'}</td>
                    <td><code className={e.action.includes('failure') ? 'audit-bad' : ''}>{e.action}</code></td>
                    <td>{e.target ?? ''}</td>
                    <td className="muted">{e.details ?? ''}</td>
                    <td className="mono small">{e.ip ?? ''}</td>
                  </tr>
                ))}
                {data.items.length === 0 && <tr><td colSpan={6} className="muted">{t('Nothing found.')}</td></tr>}
              </tbody>
            </table>
          </div>
          {data.pages > 1 && (
            <div className="pager">
              <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('Newer')}</button>
              <span className="muted small">{t('Page {page} of {pages} · {total} entries', { page: page + 1, pages: data.pages, total: data.total })}</span>
              <button className="btn btn-ghost btn-sm" disabled={page + 1 >= data.pages} onClick={() => setPage(page + 1)}>{t('Older')}</button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
