import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Activity, BarChart3, Bug, Clock, Dices, FileSpreadsheet, FileText, Gauge, HeartPulse, Hourglass, Mail, PackageCheck, ShieldCheck, Timer, TrendingDown } from 'lucide-react';
import { api, ApiError, saveBlob } from '../api';
import { useToast } from '../toast';
import { AgingReport, BugReport, BurnupReport, ForecastReport, SlaReportView } from '../components/reports/InsightReports';
import { HealthCheckReport } from '../components/reports/HealthCheckReport';
import { ScheduleReportModal } from '../components/reports/ScheduleReportModal';
import { useLiveRefresh } from '../live';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { BurndownChart } from '../components/BurndownChart';
import { VelocityChart } from '../components/VelocityChart';
import { CumulativeFlowChart, CycleScatter, ThroughputChart } from '../components/FlowCharts';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, formatMinutes, todayIso } from '../format';
import type { Burndown, CycleReport, FlowDay, Sprint, Throughput, TimeReport, VelocityEntry } from '../types';
import { t } from '../i18n';

type Tab = 'burndown' | 'velocity' | 'flow' | 'cycle' | 'time' | 'forecast' | 'burnup' | 'aging' | 'bugs' | 'sla' | 'health';

const TABS: { id: Tab; label: string; icon: typeof BarChart3; scrumOnly?: boolean }[] = [
  { id: 'burndown', label: 'Burndown', icon: TrendingDown, scrumOnly: true },
  { id: 'velocity', label: 'Velocity', icon: Gauge, scrumOnly: true },
  { id: 'flow', label: 'Cumulative flow', icon: Activity },
  { id: 'cycle', label: 'Cycle time', icon: Timer },
  { id: 'time', label: 'Time', icon: Clock },
  { id: 'forecast', label: 'Forecast', icon: Dices },
  { id: 'burnup', label: 'Release burn-up', icon: PackageCheck },
  { id: 'aging', label: 'Aging work', icon: Hourglass },
  { id: 'bugs', label: 'Bugs', icon: Bug },
  { id: 'sla', label: 'SLA', icon: ShieldCheck },
  { id: 'health', label: 'Team health', icon: HeartPulse },
];

export function ReportsPage() {
  const { key, project, loading, canEdit, isOwner } = useRouteProject();
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const [scheduling, setScheduling] = useState(false);
  const download = async (format: 'xlsx' | 'pdf') => {
    try {
      saveBlob(await api.exportReports(key, format), `${key.toLowerCase()}-reports-${todayIso()}.${format}`);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const tabs = TABS.filter((t) => !project.kanban || !t.scrumOnly);
  const first = tabs[0].id;
  const tab = (tabs.some((t) => t.id === params.get('tab')) ? params.get('tab') : first) as Tab;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t("Reports")}</h1>
        </div>
        <div className="header-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => download('xlsx')}><FileSpreadsheet size={15} /> {t('Excel')}</button>
          <button className="btn btn-ghost btn-sm" onClick={() => download('pdf')}><FileText size={15} /> {t('PDF')}</button>
          <button className="btn btn-soft btn-sm" onClick={() => setScheduling(true)}><Mail size={15} /> {t('Email me a summary')}</button>
        </div>
      </header>
      <nav className="tabs" role="tablist" aria-label={t("Reports")}>
        {tabs.map(({ id, label, icon: Icon }) => (
          <button key={id} role="tab" aria-selected={tab === id} className={`tab ${tab === id ? 'active' : ''}`}
            onClick={() => setParams(id === first ? {} : { tab: id }, { replace: true })}>
            <Icon size={15} /> {t(label)}
          </button>
        ))}
      </nav>
      {tab === 'burndown' && <BurndownReport projectKey={key} projectId={project.id} />}
      {tab === 'velocity' && <VelocityReport projectKey={key} projectId={project.id} />}
      {tab === 'flow' && <FlowReport projectKey={key} projectId={project.id} />}
      {tab === 'cycle' && <CycleReportView projectKey={key} projectId={project.id} />}
      {tab === 'time' && <TimeReportView projectKey={key} projectId={project.id} />}
      {tab === 'forecast' && <ForecastReport projectKey={key} />}
      {tab === 'burnup' && <BurnupReport projectKey={key} />}
      {tab === 'aging' && <AgingReport projectKey={key} />}
      {tab === 'bugs' && <BugReport projectKey={key} />}
      {tab === 'sla' && <SlaReportView projectKey={key} isOwner={isOwner} />}
      {tab === 'health' && <HealthCheckReport projectKey={key} canEdit={canEdit} />}
      {scheduling && <ScheduleReportModal kind="project" target={key} defaultTitle={`${project.name} summary`}
        onClose={() => setScheduling(false)} />}
    </div>
  );
}

interface ReportProps {
  projectKey: string;
  projectId: number;
}

function BurndownReport({ projectKey: key, projectId }: ReportProps) {
  const [sprints, setSprints] = useState<Sprint[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [burndown, setBurndown] = useState<Burndown | null>(null);
  const [unit, setUnit] = useState<'tasks' | 'points'>('tasks');
  const [error, setError] = useState('');

  const loadSprints = useCallback(() => {
    api.sprints(key).then((list) => {
      const reportable = list.filter((s) => s.state !== 'PLANNED').sort((a, b) => b.id - a.id);
      setSprints(reportable);
      setSelected((current) => (current && reportable.some((s) => s.id === current) ? current : reportable[0]?.id ?? null));
    }).catch((e: ApiError) => setError(e.message));
  }, [key]);

  const loadBurndown = useCallback(() => {
    if (selected === null) {
      setBurndown(null);
      return;
    }
    api.burndown(selected).then((b) => {
      setBurndown(b);
      if (b.totalPoints === 0) setUnit('tasks');
    }).catch((e: ApiError) => setError(e.message));
  }, [selected]);

  useEffect(loadSprints, [loadSprints]);
  useEffect(loadBurndown, [loadBurndown]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === projectId, () => {
    loadSprints();
    loadBurndown();
  }, 600);

  const sprint = burndown?.sprint;
  const points = unit === 'points';
  const total = burndown ? (points ? burndown.totalPoints : burndown.total) : 0;
  const done = burndown ? (points ? burndown.donePoints : burndown.done) : 0;
  const percent = total ? Math.round((done / total) * 100) : 0;
  const noun = points ? t('points') : t('tasks');

  return (
    <>
      {error && <ErrorBanner message={error} />}
      {!sprints && !error && <Spinner />}
      {sprints && sprints.length === 0 && (
        <EmptyState icon={<BarChart3 size={28} />} title={t("No sprint data yet")}>
          {t('Start a sprint from the')} <Link to={`/p/${key}/backlog`}>{t("backlog")}</Link> {t('to see its burndown here.')}
        </EmptyState>
      )}
      {burndown && sprint && sprints && (
        <>
          <div className="report-toolbar">
            <select value={selected ?? ''} onChange={(e) => setSelected(Number(e.target.value))} aria-label={t("Sprint")}>
              {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
            </select>
            <div className="segmented small" role="radiogroup" aria-label={t("Measure")}>
              {(['tasks', 'points'] as const).map((u) => (
                <label key={u} className={unit === u ? 'active' : ''}>
                  <input type="radio" name="unit" checked={unit === u} disabled={u === 'points' && burndown.totalPoints === 0}
                    onChange={() => setUnit(u)} />
                  {u === 'tasks' ? t('Tasks') : t('Story points')}
                </label>
              ))}
            </div>
          </div>
          <div className="stats">
            <div className="stat panel"><span className="muted">{t("Sprint")}</span><strong className="stat-text">{sprint.name}</strong>
              <span className="muted small">{sprint.startDate && sprint.endDate ? `${formatDay(sprint.startDate)} – ${formatDay(sprint.endDate)}` : ''}</span></div>
            <div className="stat panel"><span className="muted">{t("Committed")}</span><strong>{total}</strong><span className="muted small">{noun}</span></div>
            <div className="stat panel"><span className="muted">{t("Completed")}</span><strong>{done}</strong><span className="muted small">{t('{n}% of committed', { n: percent })}</span></div>
            <div className="stat panel"><span className="muted">{sprint.state === 'COMPLETED' ? t('Carried over') : t('Remaining')}</span>
              <strong>{sprint.state === 'COMPLETED' ? (points ? sprint.carriedOverPoints : sprint.carriedOver) : total - done}</strong>
              <span className="muted small">{sprint.state === 'COMPLETED' ? t('back to backlog') : t('open {what}', { what: noun })}</span></div>
          </div>
          <section className="panel">
            <h2 className="panel-title">{t("Burndown")}</h2>
            {sprint.goal && <p className="muted sprint-goal">{t('Goal: {goal}', { goal: sprint.goal })}</p>}
            <BurndownChart points={burndown.points} total={total} unit={unit} changes={burndown.changes} />
          </section>
          {burndown.changes.length > 0 && (
            <section className="panel">
              <h2 className="panel-title">{t("Scope changes")}</h2>
              <p className="muted small">{t("Work added to or taken out of the sprint after it started.")}</p>
              <ul className="scope-list">
                {burndown.changes.map((c, i) => (
                  <li key={i}>
                    <span className={`scope-sign ${c.added ? 'added' : 'removed'}`}>{c.added ? '+' : '−'}</span>
                    <span className="muted small">{formatDay(c.date)}</span>
                    <span className="task-key">{c.key}</span> {c.title}
                    {c.points !== null && <span className="muted small"> · {t('{n} pt', { n: c.points })}</span>}
                    {c.actor && <span className="muted small"> · {t('by {name}', { name: c.actor })}</span>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          <p className="small"><Link to={`/p/${key}/sprints/${sprint.id}`}>{t("Sprint review & retrospective →")}</Link></p>
        </>
      )}
    </>
  );
}

function VelocityReport({ projectKey: key, projectId }: ReportProps) {
  const [entries, setEntries] = useState<VelocityEntry[] | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.velocity(key).then(setEntries).catch((e: ApiError) => setError(e.message));
  }, [key]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === projectId, load, 600);

  if (error) return <ErrorBanner message={error} onRetry={load} />;
  if (!entries) return <Spinner />;
  if (entries.length === 0) {
    return (
      <EmptyState icon={<Gauge size={28} />} title={t("No completed sprints yet")}>
        {t('Velocity shows story points completed per sprint once you finish your first sprint.')}
      </EmptyState>
    );
  }

  const recent = entries.slice(-3);
  const average = Math.round(recent.reduce((sum, e) => sum + e.completedPoints, 0) / recent.length);
  const committed = entries.reduce((sum, e) => sum + e.committedPoints, 0);
  const completed = entries.reduce((sum, e) => sum + e.completedPoints, 0);

  return (
    <>
      <div className="stats">
        <div className="stat panel"><span className="muted">{t("Average velocity")}</span><strong>{average}</strong>
          <span className="muted small">{recent.length === 1 ? t('points, last sprint') : t('points, last {n} sprints', { n: recent.length })}</span></div>
        <div className="stat panel"><span className="muted">{t("Last sprint")}</span><strong>{entries[entries.length - 1].completedPoints}</strong>
          <span className="muted small">{t("points completed")}</span></div>
        <div className="stat panel"><span className="muted">{t("Say/do ratio")}</span>
          <strong>{committed ? `${Math.round((completed / committed) * 100)}%` : '—'}</strong>
          <span className="muted small">{t("completed of committed")}</span></div>
      </div>
      <section className="panel">
        <h2 className="panel-title">{t("Velocity")}</h2>
        <p className="muted small">{t("Use the average to decide how much to pull into the next sprint.")}</p>
        <VelocityChart entries={entries} />
      </section>
    </>
  );
}

function FlowReport({ projectKey: key, projectId }: ReportProps) {
  const [days, setDays] = useState(30);
  const [flow, setFlow] = useState<FlowDay[] | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => {
    api.flow(key, days).then(setFlow).catch((e: ApiError) => setError(e.message));
  }, [key, days]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.projectId === projectId, load, 800);
  if (error) return <ErrorBanner message={error} onRetry={load} />;
  if (!flow) return <Spinner />;
  const today = flow[flow.length - 1];
  const wip = today ? today.inProgress + today.inReview : 0;
  const start = flow[0];
  const finished = today && start ? today.done - start.done : 0;
  return (
    <>
      <div className="report-toolbar">
        <div className="chip-row">
          {[14, 30, 90].map((d) => (
            <button key={d} className={`chip ${days === d ? 'active' : ''}`} aria-pressed={days === d} onClick={() => setDays(d)}>
              {t('Last {n} days', { n: d })}
            </button>
          ))}
        </div>
      </div>
      <div className="stats">
        <div className="stat panel"><span className="muted">{t("Work in progress")}</span><strong>{wip}</strong>
          <span className="muted small">{t("in progress or review today")}</span></div>
        <div className="stat panel"><span className="muted">{t("Finished")}</span><strong>{finished}</strong>
          <span className="muted small">{t('tasks in the last {n} days', { n: days })}</span></div>
        <div className="stat panel"><span className="muted">{t("Waiting")}</span><strong>{today?.todo ?? 0}</strong>
          <span className="muted small">{t("to do")}</span></div>
      </div>
      <section className="panel">
        <h2 className="panel-title">{t("Cumulative flow")}</h2>
        <p className="muted small">{t("A band that keeps widening means work is piling up in that stage.")}</p>
        <CumulativeFlowChart days={flow} />
      </section>
    </>
  );
}

function CycleReportView({ projectKey: key, projectId }: ReportProps) {
  const [report, setReport] = useState<CycleReport | null>(null);
  const [weeks, setWeeks] = useState<Throughput[] | null>(null);
  const [measure, setMeasure] = useState<'cycle' | 'lead'>('cycle');
  const [unit, setUnit] = useState<'tasks' | 'points'>('tasks');
  const [error, setError] = useState('');
  const load = useCallback(() => {
    Promise.all([api.cycleTime(key, 90), api.throughput(key, 12)])
      .then(([r, w]) => {
        setReport(r);
        setWeeks(w);
      })
      .catch((e: ApiError) => setError(e.message));
  }, [key]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.projectId === projectId, load, 800);
  if (error) return <ErrorBanner message={error} onRetry={load} />;
  if (!report || !weeks) return <Spinner />;
  const days = (v: number | null) => (v === null ? '—' : `${v} d`);
  const recent = weeks.slice(-4);
  const perWeek = recent.length ? Math.round((recent.reduce((s, w) => s + w.tasks, 0) / recent.length) * 10) / 10 : 0;
  return (
    <>
      <div className="stats">
        <div className="stat panel"><span className="muted">{t("Cycle time (median)")}</span><strong>{days(report.cycleP50)}</strong>
          <span className="muted small">{t('started → done · 85% within {n}', { n: days(report.cycleP85) })}</span></div>
        <div className="stat panel"><span className="muted">{t("Lead time (median)")}</span><strong>{days(report.leadP50)}</strong>
          <span className="muted small">{t('created → done · 85% within {n}', { n: days(report.leadP85) })}</span></div>
        <div className="stat panel"><span className="muted">{t("Throughput")}</span><strong>{perWeek}</strong>
          <span className="muted small">{t("tasks per week, last 4 weeks")}</span></div>
      </div>
      {report.count === 0 ? (
        <EmptyState icon={<Timer size={28} />} title={t("Nothing finished in the last 90 days")}>
          {t('Cycle time appears once tasks move through In progress to Done.')}
        </EmptyState>
      ) : (
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">{measure === 'cycle' ? t('Cycle time per task') : t('Lead time per task')}</h2>
            <div className="segmented small" role="radiogroup" aria-label={t("Measure")}>
              {(['cycle', 'lead'] as const).map((m) => (
                <label key={m} className={measure === m ? 'active' : ''}>
                  <input type="radio" name="measure" checked={measure === m} onChange={() => setMeasure(m)} />
                  {m === 'cycle' ? t('Cycle time') : t('Lead time')}
                </label>
              ))}
            </div>
          </div>
          <p className="muted small">{t("Each dot is a finished task (last 90 days). Click one to open it.")}</p>
          <CycleScatter tasks={report.tasks} measure={measure} p85={measure === 'cycle' ? report.cycleP85 : report.leadP85} />
        </section>
      )}
      <section className="panel">
        <div className="panel-head">
          <h2 className="panel-title">{t("Throughput")}</h2>
          <div className="segmented small" role="radiogroup" aria-label={t("Unit")}>
            {(['tasks', 'points'] as const).map((u) => (
              <label key={u} className={unit === u ? 'active' : ''}>
                <input type="radio" name="tp-unit" checked={unit === u} onChange={() => setUnit(u)} />
                {u === 'tasks' ? t('Tasks') : t('Story points')}
              </label>
            ))}
          </div>
        </div>
        <ThroughputChart weeks={weeks} unit={unit} />
      </section>
    </>
  );
}

function daysAgo(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function TimeReportView({ projectKey: key, projectId }: ReportProps) {
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(todayIso());
  const [report, setReport] = useState<TimeReport | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    if (!from || !to || from > to) return;
    setError('');
    api.timeReport(key, from, to).then(setReport).catch((e: ApiError) => setError(e.message));
  }, [key, from, to]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.projectId === projectId, load, 800);

  const maxUser = Math.max(1, ...(report?.byUser.map((u) => u.minutes) ?? [0]));

  return (
    <>
      <div className="report-toolbar">
        <label className="inline-field">{t("From")} <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="inline-field">{t("To")} <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
        <div className="chip-row">
          {[7, 30, 90].map((days) => (
            <button key={days} className="chip" onClick={() => {
              setFrom(daysAgo(days - 1));
              setTo(todayIso());
            }}>{t('Last {n} days', { n: days })}</button>
          ))}
        </div>
      </div>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!report && !error && <Spinner />}
      {report && (
        <>
          <div className="stats">
            <div className="stat panel"><span className="muted">{t("Time logged")}</span><strong>{formatMinutes(report.totalMinutes)}</strong>
              <span className="muted small">{formatDay(report.from, true)} – {formatDay(report.to, true)}</span></div>
            <div className="stat panel"><span className="muted">{t("Entries")}</span><strong>{report.entries}</strong>
              <span className="muted small">{t("work logs")}</span></div>
            <div className="stat panel"><span className="muted">{t("People")}</span><strong>{report.byUser.length}</strong>
              <span className="muted small">{t("logged time")}</span></div>
          </div>
          {report.entries === 0 ? (
            <EmptyState icon={<Clock size={28} />} title={t("No time logged in this period")}>
              {t("Log work from a task's Time section, e.g. “1h 30m”.")}
            </EmptyState>
          ) : (
            <div className="report-grid">
              <section className="panel">
                <h2 className="panel-title">{t("By person")}</h2>
                <table className="viz-table">
                  <thead><tr><th>{t("Person")}</th><th className="num">{t("Time")}</th><th className="num">{t("Share")}</th></tr></thead>
                  <tbody>
                    {report.byUser.map(({ user, minutes }) => (
                      <tr key={user.id}>
                        <td><span className="person"><Avatar user={user} size={22} /> {user.displayName}</span></td>
                        <td className="num">{formatMinutes(minutes)}</td>
                        <td className="num share">
                          <span className="share-bar"><span style={{ width: `${(minutes / maxUser) * 100}%` }} /></span>
                          {Math.round((minutes / report.totalMinutes) * 100)}%
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
              <section className="panel">
                <h2 className="panel-title">{t("By task")}</h2>
                <table className="viz-table">
                  <thead><tr><th>{t("Task")}</th><th className="num">{t("Time")}</th></tr></thead>
                  <tbody>
                    {report.byTask.map(({ task, minutes }) => (
                      <tr key={task.id}>
                        <td><Link to={`/tasks/${task.id}`}><span className="task-key">{task.key}</span> {task.title}</Link></td>
                        <td className="num">{formatMinutes(minutes)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            </div>
          )}
        </>
      )}
    </>
  );
}
