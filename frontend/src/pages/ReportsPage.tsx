import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Activity, BarChart3, Clock, Gauge, Timer, TrendingDown } from 'lucide-react';
import { api, ApiError } from '../api';
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

type Tab = 'burndown' | 'velocity' | 'flow' | 'cycle' | 'time';

const TABS: { id: Tab; label: string; icon: typeof BarChart3; scrumOnly?: boolean }[] = [
  { id: 'burndown', label: 'Burndown', icon: TrendingDown, scrumOnly: true },
  { id: 'velocity', label: 'Velocity', icon: Gauge, scrumOnly: true },
  { id: 'flow', label: 'Cumulative flow', icon: Activity },
  { id: 'cycle', label: 'Cycle time', icon: Timer },
  { id: 'time', label: 'Time', icon: Clock },
];

export function ReportsPage() {
  const { key, project, loading } = useRouteProject();
  const [params, setParams] = useSearchParams();

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
          <h1>Reports</h1>
        </div>
      </header>
      <nav className="tabs" role="tablist" aria-label="Reports">
        {tabs.map(({ id, label, icon: Icon }) => (
          <button key={id} role="tab" aria-selected={tab === id} className={`tab ${tab === id ? 'active' : ''}`}
            onClick={() => setParams(id === first ? {} : { tab: id }, { replace: true })}>
            <Icon size={15} /> {label}
          </button>
        ))}
      </nav>
      {tab === 'burndown' && <BurndownReport projectKey={key} projectId={project.id} />}
      {tab === 'velocity' && <VelocityReport projectKey={key} projectId={project.id} />}
      {tab === 'flow' && <FlowReport projectKey={key} projectId={project.id} />}
      {tab === 'cycle' && <CycleReportView projectKey={key} projectId={project.id} />}
      {tab === 'time' && <TimeReportView projectKey={key} projectId={project.id} />}
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
  const noun = points ? 'points' : 'tasks';

  return (
    <>
      {error && <ErrorBanner message={error} />}
      {!sprints && !error && <Spinner />}
      {sprints && sprints.length === 0 && (
        <EmptyState icon={<BarChart3 size={28} />} title="No sprint data yet">
          Start a sprint from the <Link to={`/p/${key}/backlog`}>backlog</Link> to see its burndown here.
        </EmptyState>
      )}
      {burndown && sprint && sprints && (
        <>
          <div className="report-toolbar">
            <select value={selected ?? ''} onChange={(e) => setSelected(Number(e.target.value))} aria-label="Sprint">
              {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
            </select>
            <div className="segmented small" role="radiogroup" aria-label="Measure">
              {(['tasks', 'points'] as const).map((u) => (
                <label key={u} className={unit === u ? 'active' : ''}>
                  <input type="radio" name="unit" checked={unit === u} disabled={u === 'points' && burndown.totalPoints === 0}
                    onChange={() => setUnit(u)} />
                  {u === 'tasks' ? 'Tasks' : 'Story points'}
                </label>
              ))}
            </div>
          </div>
          <div className="stats">
            <div className="stat panel"><span className="muted">Sprint</span><strong className="stat-text">{sprint.name}</strong>
              <span className="muted small">{sprint.startDate && sprint.endDate ? `${formatDay(sprint.startDate)} – ${formatDay(sprint.endDate)}` : ''}</span></div>
            <div className="stat panel"><span className="muted">Committed</span><strong>{total}</strong><span className="muted small">{noun}</span></div>
            <div className="stat panel"><span className="muted">Completed</span><strong>{done}</strong><span className="muted small">{percent}% of committed</span></div>
            <div className="stat panel"><span className="muted">{sprint.state === 'COMPLETED' ? 'Carried over' : 'Remaining'}</span>
              <strong>{sprint.state === 'COMPLETED' ? (points ? sprint.carriedOverPoints : sprint.carriedOver) : total - done}</strong>
              <span className="muted small">{sprint.state === 'COMPLETED' ? 'back to backlog' : `open ${noun}`}</span></div>
          </div>
          <section className="panel">
            <h2 className="panel-title">Burndown</h2>
            {sprint.goal && <p className="muted sprint-goal">Goal: {sprint.goal}</p>}
            <BurndownChart points={burndown.points} total={total} unit={unit} changes={burndown.changes} />
          </section>
          {burndown.changes.length > 0 && (
            <section className="panel">
              <h2 className="panel-title">Scope changes</h2>
              <p className="muted small">Work added to or taken out of the sprint after it started.</p>
              <ul className="scope-list">
                {burndown.changes.map((c, i) => (
                  <li key={i}>
                    <span className={`scope-sign ${c.added ? 'added' : 'removed'}`}>{c.added ? '+' : '−'}</span>
                    <span className="muted small">{formatDay(c.date)}</span>
                    <span className="task-key">{c.key}</span> {c.title}
                    {c.points !== null && <span className="muted small"> · {c.points} pt</span>}
                    {c.actor && <span className="muted small"> · by {c.actor}</span>}
                  </li>
                ))}
              </ul>
            </section>
          )}
          <p className="small"><Link to={`/p/${key}/sprints/${sprint.id}`}>Sprint review & retrospective →</Link></p>
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
      <EmptyState icon={<Gauge size={28} />} title="No completed sprints yet">
        Velocity shows story points completed per sprint once you finish your first sprint.
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
        <div className="stat panel"><span className="muted">Average velocity</span><strong>{average}</strong>
          <span className="muted small">points, last {recent.length} sprint{recent.length === 1 ? '' : 's'}</span></div>
        <div className="stat panel"><span className="muted">Last sprint</span><strong>{entries[entries.length - 1].completedPoints}</strong>
          <span className="muted small">points completed</span></div>
        <div className="stat panel"><span className="muted">Say/do ratio</span>
          <strong>{committed ? `${Math.round((completed / committed) * 100)}%` : '—'}</strong>
          <span className="muted small">completed of committed</span></div>
      </div>
      <section className="panel">
        <h2 className="panel-title">Velocity</h2>
        <p className="muted small">Use the average to decide how much to pull into the next sprint.</p>
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
              Last {d} days
            </button>
          ))}
        </div>
      </div>
      <div className="stats">
        <div className="stat panel"><span className="muted">Work in progress</span><strong>{wip}</strong>
          <span className="muted small">in progress or review today</span></div>
        <div className="stat panel"><span className="muted">Finished</span><strong>{finished}</strong>
          <span className="muted small">tasks in the last {days} days</span></div>
        <div className="stat panel"><span className="muted">Waiting</span><strong>{today?.todo ?? 0}</strong>
          <span className="muted small">to do</span></div>
      </div>
      <section className="panel">
        <h2 className="panel-title">Cumulative flow</h2>
        <p className="muted small">A band that keeps widening means work is piling up in that stage.</p>
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
        <div className="stat panel"><span className="muted">Cycle time (median)</span><strong>{days(report.cycleP50)}</strong>
          <span className="muted small">started → done · 85% within {days(report.cycleP85)}</span></div>
        <div className="stat panel"><span className="muted">Lead time (median)</span><strong>{days(report.leadP50)}</strong>
          <span className="muted small">created → done · 85% within {days(report.leadP85)}</span></div>
        <div className="stat panel"><span className="muted">Throughput</span><strong>{perWeek}</strong>
          <span className="muted small">tasks per week, last 4 weeks</span></div>
      </div>
      {report.count === 0 ? (
        <EmptyState icon={<Timer size={28} />} title="Nothing finished in the last 90 days">
          Cycle time appears once tasks move through In progress to Done.
        </EmptyState>
      ) : (
        <section className="panel">
          <div className="panel-head">
            <h2 className="panel-title">{measure === 'cycle' ? 'Cycle' : 'Lead'} time per task</h2>
            <div className="segmented small" role="radiogroup" aria-label="Measure">
              {(['cycle', 'lead'] as const).map((m) => (
                <label key={m} className={measure === m ? 'active' : ''}>
                  <input type="radio" name="measure" checked={measure === m} onChange={() => setMeasure(m)} />
                  {m === 'cycle' ? 'Cycle time' : 'Lead time'}
                </label>
              ))}
            </div>
          </div>
          <p className="muted small">Each dot is a finished task (last 90 days). Click one to open it.</p>
          <CycleScatter tasks={report.tasks} measure={measure} p85={measure === 'cycle' ? report.cycleP85 : report.leadP85} />
        </section>
      )}
      <section className="panel">
        <div className="panel-head">
          <h2 className="panel-title">Throughput</h2>
          <div className="segmented small" role="radiogroup" aria-label="Unit">
            {(['tasks', 'points'] as const).map((u) => (
              <label key={u} className={unit === u ? 'active' : ''}>
                <input type="radio" name="tp-unit" checked={unit === u} onChange={() => setUnit(u)} />
                {u === 'tasks' ? 'Tasks' : 'Story points'}
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
        <label className="inline-field">From <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="inline-field">To <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
        <div className="chip-row">
          {[7, 30, 90].map((days) => (
            <button key={days} className="chip" onClick={() => {
              setFrom(daysAgo(days - 1));
              setTo(todayIso());
            }}>Last {days} days</button>
          ))}
        </div>
      </div>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!report && !error && <Spinner />}
      {report && (
        <>
          <div className="stats">
            <div className="stat panel"><span className="muted">Time logged</span><strong>{formatMinutes(report.totalMinutes)}</strong>
              <span className="muted small">{formatDay(report.from, true)} – {formatDay(report.to, true)}</span></div>
            <div className="stat panel"><span className="muted">Entries</span><strong>{report.entries}</strong>
              <span className="muted small">work logs</span></div>
            <div className="stat panel"><span className="muted">People</span><strong>{report.byUser.length}</strong>
              <span className="muted small">logged time</span></div>
          </div>
          {report.entries === 0 ? (
            <EmptyState icon={<Clock size={28} />} title="No time logged in this period">
              Log work from a task's Time section, e.g. “1h 30m”.
            </EmptyState>
          ) : (
            <div className="report-grid">
              <section className="panel">
                <h2 className="panel-title">By person</h2>
                <table className="viz-table">
                  <thead><tr><th>Person</th><th className="num">Time</th><th className="num">Share</th></tr></thead>
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
                <h2 className="panel-title">By task</h2>
                <table className="viz-table">
                  <thead><tr><th>Task</th><th className="num">Time</th></tr></thead>
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
