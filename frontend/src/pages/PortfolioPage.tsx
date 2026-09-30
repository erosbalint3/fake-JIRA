import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, Briefcase, CheckCircle2, Eye } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { formatDay, timeAgo } from '../format';
import type { PortfolioRow, Risk } from '../types';
import { t } from '../i18n';

const RISK: Record<Risk, { label: string; icon: typeof Eye }> = {
  OK: { label: 'On track', icon: CheckCircle2 },
  WATCH: { label: 'Watch', icon: Eye },
  AT_RISK: { label: 'At risk', icon: AlertTriangle },
};

/** All my projects on one screen: progress, dates and what puts them at risk. */
export function PortfolioPage() {
  const [rows, setRows] = useState<PortfolioRow[] | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => {
    api.portfolio().then(setRows).catch((e: ApiError) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' || m.type === 'project', load, 2000);

  const counts = (rows ?? []).reduce((acc, r) => ({ ...acc, [r.risk]: (acc[r.risk] ?? 0) + 1 }), {} as Partial<Record<Risk, number>>);

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t('Workspace')}</span>
          <h1>{t('Portfolio')}</h1>
          <p className="muted">{t('Progress, dates and risks across every project you are in.')}</p>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!rows && !error && <Spinner />}
      {rows && rows.length === 0 && (
        <EmptyState icon={<Briefcase size={28} />} title={t('No projects yet')}>
          <Link to="/projects?new=1">{t('Create your first project')}</Link>
        </EmptyState>
      )}
      {rows && rows.length > 0 && (
        <>
          <div className="stats">
            {(['AT_RISK', 'WATCH', 'OK'] as Risk[]).map((risk) => {
              const Icon = RISK[risk].icon;
              return (
                <div key={risk} className={`stat panel risk-${risk.toLowerCase()}`}>
                  <span className="muted"><Icon size={14} /> {t(RISK[risk].label)}</span>
                  <strong>{counts[risk] ?? 0}</strong>
                  <span className="muted small">{t('projects')}</span>
                </div>
              );
            })}
          </div>
          <div className="portfolio-grid">
            {rows.map((r) => {
              const Icon = RISK[r.risk].icon;
              return (
                <article key={r.key} className={`panel portfolio-card risk-${r.risk.toLowerCase()}`}
                  style={r.color ? { borderTopColor: r.color } : undefined}>
                  <header className="portfolio-head">
                    <div>
                      <Link to={`/p/${r.key}/board`} className="portfolio-name">{r.name}</Link>
                      <span className="muted small"> {r.key}{r.kanban ? ' · Kanban' : ''}</span>
                    </div>
                    <span className={`risk-badge risk-${r.risk.toLowerCase()}`}><Icon size={13} /> {t(RISK[r.risk].label)}</span>
                  </header>
                  <div className="progress-line" aria-label={t('{p}% done', { p: r.percentDone })}>
                    <div className="progress"><span style={{ width: `${r.percentDone}%` }} /></div>
                    <span className="small">{r.percentDone}%</span>
                  </div>
                  <dl className="portfolio-facts">
                    <div><dt>{t('Open')}</dt><dd>{r.open}</dd></div>
                    <div><dt>{t('In progress')}</dt><dd>{r.inProgress}</dd></div>
                    <div><dt>{t('Done')}</dt><dd>{r.done}</dd></div>
                    <div><dt>{t('Overdue')}</dt><dd className={r.overdue ? 'overdue-text' : ''}>{r.overdue}</dd></div>
                    <div><dt>{t('Unassigned')}</dt><dd>{r.unassigned}</dd></div>
                  </dl>
                  {r.sprint && (
                    <p className="small">
                      <Link to={`/p/${r.key}/sprints/${r.sprint.id}`}>{r.sprint.name}</Link>: {r.sprint.done}/{r.sprint.total} {t('done')}
                      {r.sprint.end && <> · {t('ends {date}', { date: formatDay(r.sprint.end) })}</>}
                      {' · '}<span className={r.sprint.behind ? 'overdue-text' : 'muted'}>
                        {t('{done}% done, {time}% of time used', { done: r.sprint.percentDone, time: r.sprint.percentTime })}</span>
                    </p>
                  )}
                  {r.release && (
                    <p className="small">
                      <Link to={`/p/${r.key}/releases`}>{r.release.name}</Link>
                      {r.release.date && <> · <span className={r.release.late ? 'overdue-text' : ''}>{formatDay(r.release.date)}</span></>}
                      {' · '}{t('{n} open', { n: r.release.open })}
                    </p>
                  )}
                  {r.reasons.length > 0 && (
                    <ul className="risk-reasons small">{r.reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
                  )}
                  <footer className="muted small">{t('Last activity {when}', { when: timeAgo(r.lastActivity) })}</footer>
                </article>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
