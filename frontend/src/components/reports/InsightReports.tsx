import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { formatDay } from '../../format';
import { Avatar } from '../Avatar';
import { BarChart, DonutChart, LineChart } from '../Charts';
import { EmptyState, ErrorBanner, Spinner } from '../States';
import {
  PRIORITY_LABEL, RESOLUTION_LABEL, STATUS_LABEL, type AgingWip, type BugTrends, type Burnup, type Epic, type ForecastResult,
  type Priority, type Release, type Resolution, type SlaReport, type SlaTarget, type Sprint,
} from '../../types';
import { t } from '../../i18n';

function useReport<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(() => {
    setError('');
    load().then(setData).catch((e: ApiError) => setError(e.message));
  }, deps);
  useEffect(run, [run]);
  useLiveRefresh((m) => m.type === 'task' || m.type === 'project', run, 1500);
  return { data, error, reload: run };
}

// ---- Forecast --------------------------------------------------------------------------------------------------

export function ForecastReport({ projectKey }: { projectKey: string }) {
  const [scope, setScope] = useState('all');
  const [by, setBy] = useState('');
  const [releases, setReleases] = useState<Release[]>([]);
  const [epics, setEpics] = useState<Epic[]>([]);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  useEffect(() => {
    api.releases(projectKey).then((r) => setReleases(r.filter((x) => !x.released))).catch(() => {});
    api.epics(projectKey).then(setEpics).catch(() => {});
    api.sprints(projectKey).then((s) => setSprints(s.filter((x) => x.state !== 'COMPLETED'))).catch(() => {});
  }, [projectKey]);

  const params = () => {
    const [kind, id] = scope.split(':');
    const value = Number(id);
    return {
      ...(kind === 'release' ? { release: value } : kind === 'epic' ? { epic: value } : kind === 'sprint' ? { sprint: value } : {}),
      ...(by ? { by } : {}),
    };
  };
  const { data, error, reload } = useReport<ForecastResult>(() => api.forecast(projectKey, params()), [projectKey, scope, by]);

  return (
    <section className="panel">
      <div className="panel-title-row">
        <h2 className="panel-title">{t('When will it be done?')}</h2>
        <div className="row-actions">
          <select value={scope} onChange={(e) => setScope(e.target.value)} aria-label={t('What to forecast')}>
            <option value="all">{t('All open tasks')}</option>
            {releases.length > 0 && <optgroup label={t('Releases')}>{releases.map((r) => <option key={r.id} value={`release:${r.id}`}>{r.name}</option>)}</optgroup>}
            {epics.length > 0 && <optgroup label={t('Epics')}>{epics.map((e) => <option key={e.id} value={`epic:${e.id}`}>{e.name}</option>)}</optgroup>}
            {sprints.length > 0 && <optgroup label={t('Sprints')}>{sprints.map((s) => <option key={s.id} value={`sprint:${s.id}`}>{s.name}</option>)}</optgroup>}
          </select>
          <label className="inline-label">
            <span>{t('Target date')}</span>
            <input type="date" value={by} onChange={(e) => setBy(e.target.value)} />
          </label>
        </div>
      </div>
      <p className="muted small hint">
        {t('A Monte Carlo simulation replays your weekly throughput 10,000 times to show how likely each finish date is.')}
      </p>
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {!data && !error && <Spinner />}
      {data && data.remaining === 0 && <p>{t('Nothing left to do in this scope.')}</p>}
      {data && data.remaining > 0 && !data.enoughData && (
        <EmptyState icon={<span aria-hidden>🎲</span>} title={t('Not enough history yet')}>
          {t('Forecasts need at least two weeks with finished tasks.')}
        </EmptyState>
      )}
      {data && data.enoughData && data.remaining > 0 && (
        <>
          <div className="forecast-cards">
            {data.completion.map((e) => (
              <div key={e.confidence} className={`forecast-card ${e.confidence === 85 ? 'highlight' : ''}`}>
                <span className="muted small">{t('{n}% likely by', { n: e.confidence })}</span>
                <strong>{formatDay(e.date, true)}</strong>
                <span className="muted small">{t('{n} weeks', { n: e.weeks })}</span>
              </div>
            ))}
          </div>
          <p>
            {t('{n} open tasks in {scope}.', { n: data.remaining, scope: data.scope })}{' '}
            {data.targetDate && data.targetProbability !== null && (
              <strong className={data.targetProbability >= 85 ? 'good-text' : data.targetProbability >= 50 ? '' : 'danger-text'}>
                {t('{p}% chance of finishing by {date}.', { p: data.targetProbability, date: formatDay(data.targetDate, true) })}
              </strong>
            )}
          </p>
          {data.byTarget.length > 0 && (
            <p className="muted">
              {t('By then you will likely finish:')}{' '}
              {data.byTarget.map((c) => t('{items} tasks ({n}% sure)', { items: c.items, n: c.confidence })).join(' · ')}
            </p>
          )}
          <h3 className="subheading">{t('Simulated outcomes')}</h3>
          <BarChart label={t('How many simulations finished after each number of weeks')}
            xLabels={Object.keys(data.histogram).map((w) => t('{n} wk', { n: w }))}
            series={[{ label: t('Simulations'), values: Object.values(data.histogram) }]} />
          <p className="muted small">{t('Weekly throughput used: {list}', { list: data.weeklyThroughput.join(', ') })}</p>
        </>
      )}
    </section>
  );
}

// ---- Release burn-up ---------------------------------------------------------------------------------------------

export function BurnupReport({ projectKey }: { projectKey: string }) {
  const [releases, setReleases] = useState<Release[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [unit, setUnit] = useState<'tasks' | 'points'>('tasks');
  useEffect(() => {
    api.releases(projectKey).then((list) => {
      setReleases(list);
      setSelected((current) => current ?? list.find((r) => !r.released)?.id ?? list[0]?.id ?? null);
    }).catch(() => setReleases([]));
  }, [projectKey]);
  const { data, error, reload } = useReport<Burnup | null>(
    () => (selected ? api.burnup(selected) : Promise.resolve(null)), [selected]);

  if (releases && releases.length === 0) {
    return (
      <EmptyState icon={<span aria-hidden>📦</span>} title={t('No releases yet')}>
        <Link to={`/p/${projectKey}/releases`}>{t('Create a release')}</Link> {t('to follow its scope and progress here.')}
      </EmptyState>
    );
  }
  return (
    <section className="panel">
      <div className="panel-title-row">
        <h2 className="panel-title">{t('Release burn-up')}</h2>
        <div className="row-actions">
          <select value={selected ?? ''} onChange={(e) => setSelected(Number(e.target.value))} aria-label={t('Release')}>
            {releases?.map((r) => <option key={r.id} value={r.id}>{r.name}{r.released ? ` (${t('released')})` : ''}</option>)}
          </select>
          <select value={unit} onChange={(e) => setUnit(e.target.value as 'tasks' | 'points')} aria-label={t('Unit')}>
            <option value="tasks">{t('Tasks')}</option>
            <option value="points">{t('Story points')}</option>
          </select>
        </div>
      </div>
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <LineChart label={t('Scope and completed work of {name}', { name: data.name })}
            xLabels={data.days.map((d) => formatDay(d.date))}
            series={[
              { label: t('Scope'), values: data.days.map((d) => (unit === 'points' ? d.scopePoints : d.scope)), dashed: true },
              { label: t('Done'), values: data.days.map((d) => (unit === 'points' ? d.donePoints : d.done)) },
            ]} />
          <p className="muted">
            {data.projectedDate
              ? t('At the current pace ({rate} tasks a day) the scope is done around {date}.', { rate: data.dailyRate, date: formatDay(data.projectedDate, true) })
              : t('Nothing was finished in the last two weeks, so there is no projection yet.')}
            {data.releaseDate && ` ${t('Planned release: {date}.', { date: formatDay(data.releaseDate, true) })}`}
          </p>
        </>
      )}
    </section>
  );
}

// ---- Aging work in progress --------------------------------------------------------------------------------------

export function AgingReport({ projectKey }: { projectKey: string }) {
  const { data, error, reload } = useReport<AgingWip>(() => api.agingWip(projectKey), [projectKey]);
  const max = Math.max(1, ...(data?.items.map((i) => i.ageDays) ?? [1]), data?.cycleP85 ?? 0);
  return (
    <section className="panel">
      <h2 className="panel-title">{t('Aging work in progress')}</h2>
      <p className="muted small hint">
        {data?.cycleP50 != null
          ? t('Half of recent tasks finished within {p50} days, 85% within {p85} days. Tasks past that are worth a look.',
            { p50: data.cycleP50, p85: data.cycleP85 ?? '–' })
          : t('Once tasks go from In progress to Done, their usual cycle time appears here for comparison.')}
      </p>
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {!data && !error && <Spinner />}
      {data && data.items.length === 0 && <p className="muted">{t('Nothing is in progress right now.')}</p>}
      {data && data.items.length > 0 && (
        <ul className="aging-list">
          {data.items.map((item) => (
            <li key={item.task.id} className={`aging-${item.level}`}>
              <Link to={`/tasks/${item.task.id}`} className="task-key">{item.task.key}</Link>
              <Link to={`/tasks/${item.task.id}`} className="aging-title">{item.task.title}</Link>
              <span className="muted small">{item.column ?? t(STATUS_LABEL[item.status])}</span>
              {item.assignee ? <Avatar user={item.assignee} size={22} /> : <span className="avatar-placeholder" />}
              <div className="aging-bar" aria-hidden>
                <span style={{ width: `${(item.ageDays / max) * 100}%` }} />
                {data.cycleP85 != null && <i style={{ left: `${(data.cycleP85 / max) * 100}%` }} />}
              </div>
              <strong className="aging-days">{t('{n} d', { n: item.ageDays })}</strong>
              <span className={`aging-badge ${item.level}`}>{item.level === 'late' ? t('Late') : item.level === 'watch' ? t('Watch') : t('On track')}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ---- Bug trends --------------------------------------------------------------------------------------------------

export function BugReport({ projectKey }: { projectKey: string }) {
  const [weeks, setWeeks] = useState(12);
  const { data, error, reload } = useReport<BugTrends>(() => api.bugTrends(projectKey, weeks), [projectKey, weeks]);
  return (
    <>
      <section className="panel">
        <div className="panel-title-row">
          <h2 className="panel-title">{t('Bugs per week')}</h2>
          <select value={weeks} onChange={(e) => setWeeks(Number(e.target.value))} aria-label={t('Period')}>
            {[8, 12, 26, 52].map((w) => <option key={w} value={w}>{t('Last {n} weeks', { n: w })}</option>)}
          </select>
        </div>
        {error && <ErrorBanner message={error} onRetry={reload} />}
        {!data && !error && <Spinner />}
        {data && (
          <>
            <BarChart label={t('Bugs created and resolved per week')} xLabels={data.weeks.map((w) => formatDay(w.weekStart))}
              series={[{ label: t('Created'), values: data.weeks.map((w) => w.created) },
                { label: t('Resolved'), values: data.weeks.map((w) => w.resolved) }]} />
            <h3 className="subheading">{t('Open bugs')}</h3>
            <LineChart label={t('Open bugs at the end of each week')} xLabels={data.weeks.map((w) => formatDay(w.weekStart))}
              series={[{ label: t('Open'), values: data.weeks.map((w) => w.open) }]} />
          </>
        )}
      </section>
      {data && (
        <div className="report-grid">
          <section className="panel">
            <h2 className="panel-title">{t('Open by priority')}</h2>
            <DonutChart label={t('Open bugs by priority')}
              slices={(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as Priority[]).map((p) => ({ label: t(PRIORITY_LABEL[p]), value: data.openByPriority[p] ?? 0 }))} />
          </section>
          <section className="panel">
            <h2 className="panel-title">{t('How bugs were closed')}</h2>
            {Object.keys(data.resolutions).length === 0 ? <p className="muted">{t('No bugs closed in this period.')}</p> : (
              <DonutChart label={t('Resolutions of closed bugs')}
                slices={Object.entries(data.resolutions).map(([r, n]) => ({ label: t(RESOLUTION_LABEL[r as Resolution]), value: n ?? 0 }))} />
            )}
            <p className="muted small">
              {data.meanDaysToResolve != null ? t('On average a bug is closed after {n} days.', { n: data.meanDaysToResolve }) : ''}
            </p>
          </section>
          <section className="panel">
            <h2 className="panel-title">{t('Oldest open bugs')}</h2>
            {data.oldestOpen.length === 0 ? <p className="muted">{t('No open bugs. 🎉')}</p> : (
              <ul className="mini-list">
                {data.oldestOpen.map((b) => <li key={b.id}><Link to={`/tasks/${b.id}`} className="task-key">{b.key}</Link> {b.title}</li>)}
              </ul>
            )}
          </section>
        </div>
      )}
    </>
  );
}

// ---- SLA -------------------------------------------------------------------------------------------------------------

export const SLA_STATE_LABEL: Record<string, string> = {
  ok: 'On track', at_risk: 'At risk', breached: 'Breached', met: 'Met',
};

export function SlaReportView({ projectKey, isOwner }: { projectKey: string; isOwner: boolean }) {
  const toast = useToast();
  const [days, setDays] = useState(30);
  const { data, error, reload } = useReport<SlaReport>(() => api.slaReport(projectKey, days), [projectKey, days]);
  const [targets, setTargets] = useState<SlaTarget[] | null>(null);
  useEffect(() => {
    api.slaTargets(projectKey).then(setTargets).catch(() => {});
  }, [projectKey]);
  const configured = targets?.some((t) => t.responseHours || t.resolveHours);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!targets) return;
    try {
      setTargets(await api.saveSlaTargets(projectKey, targets));
      toast(t('SLA targets saved'));
      reload();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };
  const hours = (value: string) => (value.trim() === '' ? null : Math.max(1, Math.round(Number(value))));

  return (
    <>
      <section className="panel">
        <h2 className="panel-title">{t('Service-level targets')}</h2>
        <p className="muted small hint">
          {t('Response: someone other than the reporter comments or work starts. Resolution: the task is done. Hours are calendar hours.')}
        </p>
        {targets && (
          <form onSubmit={save}>
            <table className="viz-table sla-targets">
              <thead><tr><th>{t('Priority')}</th><th>{t('First response within (hours)')}</th><th>{t('Resolved within (hours)')}</th></tr></thead>
              <tbody>
                {targets.map((target, i) => (
                  <tr key={target.priority}>
                    <th scope="row">{t(PRIORITY_LABEL[target.priority])}</th>
                    <td><input type="number" min={1} max={8760} value={target.responseHours ?? ''} disabled={!isOwner}
                      aria-label={t('{p}: first response within (hours)', { p: t(PRIORITY_LABEL[target.priority]) })}
                      onChange={(e) => setTargets(targets.map((x, j) => (j === i ? { ...x, responseHours: hours(e.target.value) } : x)))} /></td>
                    <td><input type="number" min={1} max={8760} value={target.resolveHours ?? ''} disabled={!isOwner}
                      aria-label={t('{p}: resolved within (hours)', { p: t(PRIORITY_LABEL[target.priority]) })}
                      onChange={(e) => setTargets(targets.map((x, j) => (j === i ? { ...x, resolveHours: hours(e.target.value) } : x)))} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            {isOwner ? <button className="btn btn-soft" type="submit">{t('Save targets')}</button>
              : <p className="muted small">{t('Only the project owner can change the targets.')}</p>}
          </form>
        )}
      </section>

      {configured && (
        <section className="panel">
          <div className="panel-title-row">
            <h2 className="panel-title">{t('SLA results')}</h2>
            <select value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label={t('Period')}>
              {[7, 30, 90].map((d) => <option key={d} value={d}>{t('Tasks created in the last {n} days', { n: d })}</option>)}
            </select>
          </div>
          {error && <ErrorBanner message={error} onRetry={reload} />}
          {!data && !error && <Spinner />}
          {data && (
            <>
              <div className="forecast-cards">
                <div className="forecast-card"><span className="muted small">{t('Responses on time')}</span>
                  <strong>{data.responseMetPercent == null ? '–' : `${data.responseMetPercent}%`}</strong></div>
                <div className="forecast-card"><span className="muted small">{t('Resolved on time')}</span>
                  <strong>{data.resolveMetPercent == null ? '–' : `${data.resolveMetPercent}%`}</strong></div>
                <div className="forecast-card"><span className="muted small">{t('Open and at risk or late')}</span>
                  <strong>{data.attention.length}</strong></div>
              </div>
              <table className="viz-table">
                <thead><tr>
                  <th>{t('Priority')}</th><th>{t('Tasks')}</th><th>{t('Responses met / late')}</th><th>{t('Avg. response')}</th>
                  <th>{t('Resolutions met / late')}</th><th>{t('Avg. resolution')}</th>
                </tr></thead>
                <tbody>
                  {data.priorities.filter((p) => p.responseHours || p.resolveHours).map((p) => (
                    <tr key={p.priority}>
                      <th scope="row">{t(PRIORITY_LABEL[p.priority])}</th>
                      <td>{p.tasks}</td>
                      <td>{p.responseHours ? `${p.responseMet} / ${p.responseBreached}` : '–'}</td>
                      <td>{p.averageResponseHours == null ? '–' : `${p.averageResponseHours} h`}</td>
                      <td>{p.resolveHours ? `${p.resolveMet} / ${p.resolveBreached}` : '–'}</td>
                      <td>{p.averageResolveHours == null ? '–' : `${p.averageResolveHours} h`}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {data.attention.length > 0 && (
                <>
                  <h3 className="subheading">{t('Needs attention')}</h3>
                  <ul className="mini-list">
                    {data.attention.map((item) => (
                      <li key={`${item.task.id}-${item.kind}`}>
                        <span className={`sla-badge ${item.state}`}>{t(SLA_STATE_LABEL[item.state ?? 'ok'])}</span>
                        <Link to={`/tasks/${item.task.id}`} className="task-key">{item.task.key}</Link>
                        <span className="grow">{item.task.title}</span>
                        <span className="muted small">
                          {item.kind === 'response' ? t('response due') : t('resolution due')} {new Date(item.dueAt).toLocaleString()}
                        </span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </>
          )}
        </section>
      )}
    </>
  );
}
