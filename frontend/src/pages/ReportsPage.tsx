import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { BarChart3, Clock, Gauge, TrendingDown } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { BurndownChart } from '../components/BurndownChart';
import { VelocityChart } from '../components/VelocityChart';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, formatMinutes, todayIso } from '../format';
import type { Burndown, Sprint, TimeReport, VelocityEntry } from '../types';

type Tab = 'burndown' | 'velocity' | 'time';

const TABS: { id: Tab; label: string; icon: typeof BarChart3 }[] = [
  { id: 'burndown', label: 'Burndown', icon: TrendingDown },
  { id: 'velocity', label: 'Velocity', icon: Gauge },
  { id: 'time', label: 'Time', icon: Clock },
];

export function ReportsPage() {
  const { key, project, loading } = useRouteProject();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.some((t) => t.id === params.get('tab')) ? params.get('tab') : 'burndown') as Tab;

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Reports</h1>
        </div>
      </header>
      <nav className="tabs" role="tablist" aria-label="Reports">
        {TABS.map(({ id, label, icon: Icon }) => (
          <button key={id} role="tab" aria-selected={tab === id} className={`tab ${tab === id ? 'active' : ''}`}
            onClick={() => setParams(id === 'burndown' ? {} : { tab: id }, { replace: true })}>
            <Icon size={15} /> {label}
          </button>
        ))}
      </nav>
      {tab === 'burndown' && <BurndownReport projectKey={key} projectId={project.id} />}
      {tab === 'velocity' && <VelocityReport projectKey={key} projectId={project.id} />}
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
            <BurndownChart points={burndown.points} total={total} unit={unit} />
          </section>
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
