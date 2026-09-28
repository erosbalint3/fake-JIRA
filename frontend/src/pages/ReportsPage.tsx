import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { BarChart3 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useRouteProject } from '../useProject';
import { BurndownChart } from '../components/BurndownChart';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay } from '../format';
import type { Burndown, Sprint } from '../types';

export function ReportsPage() {
  const { key, project, loading } = useRouteProject();
  const [sprints, setSprints] = useState<Sprint[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [burndown, setBurndown] = useState<Burndown | null>(null);
  const [error, setError] = useState('');

  const loadSprints = useCallback(() => {
    if (!project) return;
    api.sprints(key).then((list) => {
      const reportable = list.filter((s) => s.state !== 'PLANNED').sort((a, b) => b.id - a.id);
      setSprints(reportable);
      setSelected((current) => (current && reportable.some((s) => s.id === current) ? current : reportable[0]?.id ?? null));
    }).catch((e: ApiError) => setError(e.message));
  }, [key, project]);

  const loadBurndown = useCallback(() => {
    if (selected === null) {
      setBurndown(null);
      return;
    }
    api.burndown(selected).then(setBurndown).catch((e: ApiError) => setError(e.message));
  }, [selected]);

  useEffect(loadSprints, [loadSprints]);
  useEffect(loadBurndown, [loadBurndown]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id, () => {
    loadSprints();
    loadBurndown();
  }, 600);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const sprint = burndown?.sprint;
  const percent = burndown && burndown.total ? Math.round((burndown.done / burndown.total) * 100) : 0;

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Sprint report</h1>
          <p className="muted">How the remaining work burned down over the sprint.</p>
        </div>
        {sprints && sprints.length > 0 && (
          <select value={selected ?? ''} onChange={(e) => setSelected(Number(e.target.value))} aria-label="Sprint">
            {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
          </select>
        )}
      </header>

      {error && <ErrorBanner message={error} />}
      {!sprints && !error && <Spinner />}
      {sprints && sprints.length === 0 && (
        <EmptyState icon={<BarChart3 size={28} />} title="No sprint data yet">
          Start a sprint from the <Link to={`/p/${key}/backlog`}>backlog</Link> to see its burndown here.
        </EmptyState>
      )}
      {burndown && sprint && (
        <>
          <div className="stats">
            <div className="stat panel"><span className="muted">Sprint</span><strong className="stat-text">{sprint.name}</strong>
              <span className="muted small">{sprint.startDate && sprint.endDate ? `${formatDay(sprint.startDate)} – ${formatDay(sprint.endDate)}` : ''}</span></div>
            <div className="stat panel"><span className="muted">Committed</span><strong>{burndown.total}</strong><span className="muted small">tasks</span></div>
            <div className="stat panel"><span className="muted">Completed</span><strong>{burndown.done}</strong><span className="muted small">{percent}% of committed</span></div>
            <div className="stat panel"><span className="muted">{sprint.state === 'COMPLETED' ? 'Carried over' : 'Remaining'}</span>
              <strong>{sprint.state === 'COMPLETED' ? sprint.carriedOver : burndown.total - burndown.done}</strong>
              <span className="muted small">{sprint.state === 'COMPLETED' ? 'back to backlog' : 'open tasks'}</span></div>
          </div>
          <section className="panel">
            <h2 className="panel-title">Burndown</h2>
            {sprint.goal && <p className="muted sprint-goal">Goal: {sprint.goal}</p>}
            <BurndownChart points={burndown.points} total={burndown.total} />
          </section>
        </>
      )}
    </div>
  );
}
