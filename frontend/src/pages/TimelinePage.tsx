import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, GanttChart, Route as RouteIcon } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { TypeIcon } from '../components/Badges';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import type { Timeline, TimelineBar } from '../types';
import { locale, t } from '../i18n';

const DAY_MS = 86400000;
const ROW = 36;
const ZOOMS = { day: 30, week: 14, month: 5 } as const;
type Zoom = keyof typeof ZOOMS;

const toDays = (iso: string) => Math.round(new Date(`${iso}T00:00:00Z`).getTime() / DAY_MS);
const toIso = (days: number) => new Date(days * DAY_MS).toISOString().slice(0, 10);

interface Drag {
  id: number;
  mode: 'move' | 'end';
  startX: number;
  delta: number;
}

/**
 * Task-level Gantt chart: scheduled tasks as bars, "blocks" links as arrows and the critical path highlighted.
 * Editors drag a bar to move it or its right edge to change the due date.
 */
export function TimelinePage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const toast = useToast();
  const [data, setData] = useState<Timeline | null>(null);
  const [error, setError] = useState('');
  const [zoom, setZoom] = useState<Zoom>('week');
  const [criticalOnly, setCriticalOnly] = useState(false);
  const [groupBy, setGroupBy] = useState<'task' | 'person'>('task');
  const [drag, setDrag] = useState<Drag | null>(null);
  const dragRef = useRef<Drag | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.timeline(key).then(setData).catch((e: ApiError) => setError(e.message));
  }, [key, project]);
  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id && !dragRef.current, load, 700);

  const bars = useMemo(() => (data?.tasks ?? []).filter((b) => !criticalOnly || b.critical), [data, criticalOnly]);
  const range = useMemo(() => {
    const today = toDays(todayIso());
    const starts = bars.map((b) => toDays(b.start));
    const ends = bars.map((b) => toDays(b.due));
    let first = Math.min(today, ...starts) - 3;
    let last = Math.max(today, ...ends) + 10;
    if (last - first < 42) last = first + 42;
    // Start on a Monday so week lines line up.
    first -= (new Date(first * DAY_MS).getUTCDay() + 6) % 7;
    return { first, last, today };
  }, [bars]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const dayW = ZOOMS[zoom];
  const width = (range.last - range.first + 1) * dayW;
  const index = new Map(bars.map((b, i) => [b.id, i]));
  // Rows: one per task, or per person with their work packed into as few lanes as possible.
  type NameRow = { kind: 'task'; bar: TimelineBar } | { kind: 'person'; user: TimelineBar['assignee']; lane: number; lanes: number; count: number };
  const rowOf = new Map<number, number>();
  const nameRows: NameRow[] = [];
  if (groupBy === 'task') {
    bars.forEach((b, i) => {
      rowOf.set(b.id, i);
      nameRows.push({ kind: 'task', bar: b });
    });
  } else {
    const groups = new Map<string, TimelineBar[]>();
    for (const b of bars) {
      const id = b.assignee ? String(b.assignee.id) : '';
      groups.set(id, [...(groups.get(id) ?? []), b]);
    }
    const ordered = [...groups.entries()].sort(([a, x], [b, y]) => (a === '' ? 1 : b === '' ? -1
      : (x[0].assignee?.displayName ?? '').localeCompare(y[0].assignee?.displayName ?? '')));
    for (const [, list] of ordered) {
      const lanes: number[] = [];
      const laneOf = new Map<number, number>();
      for (const b of [...list].sort((a, c) => a.start.localeCompare(c.start))) {
        const start = toDays(b.start);
        let lane = lanes.findIndex((end) => end < start);
        if (lane < 0) lane = lanes.length;
        lanes[lane] = toDays(b.due);
        laneOf.set(b.id, lane);
      }
      const base = nameRows.length;
      for (let l = 0; l < lanes.length; l++) {
        nameRows.push({ kind: 'person', user: list[0].assignee, lane: l, lanes: lanes.length, count: list.length });
      }
      list.forEach((b) => rowOf.set(b.id, base + (laneOf.get(b.id) ?? 0)));
    }
  }
  const rowCount = nameRows.length;
  const geometry = (b: TimelineBar) => {
    let start = toDays(b.start);
    let end = toDays(b.due);
    if (drag?.id === b.id) {
      if (drag.mode === 'move') {
        start += drag.delta;
        end += drag.delta;
      } else {
        end = Math.max(start, end + drag.delta);
      }
    }
    return { left: (start - range.first) * dayW, width: (end - start + 1) * dayW, start, end };
  };

  const onPointerDown = (b: TimelineBar, mode: Drag['mode']) => (e: ReactPointerEvent) => {
    if (!canEdit) return;
    e.preventDefault();
    e.stopPropagation();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    const next = { id: b.id, mode, startX: e.clientX, delta: 0 };
    dragRef.current = next;
    setDrag(next);
  };
  const onPointerMove = (e: ReactPointerEvent) => {
    const current = dragRef.current;
    if (!current) return;
    const delta = Math.round((e.clientX - current.startX) / dayW);
    if (delta !== current.delta) {
      dragRef.current = { ...current, delta };
      setDrag(dragRef.current);
    }
  };
  const onPointerUp = async () => {
    const current = dragRef.current;
    dragRef.current = null;
    setDrag(null);
    if (!current || current.delta === 0 || !data) return;
    const bar = data.tasks.find((b) => b.id === current.id);
    if (!bar) return;
    const start = toDays(bar.start) + (current.mode === 'move' ? current.delta : 0);
    const end = Math.max(start, toDays(bar.due) + current.delta);
    try {
      const task = await api.task(bar.id);
      await api.schedule(bar.id, { startDate: toIso(start), dueDate: toIso(end), estimateMinutes: task.estimateMinutes });
      toast(t('{key} now runs {start} – {end}', { key: bar.key, start: formatDay(toIso(start)), end: formatDay(toIso(end)) }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
    load();
  };

  // Header: month labels plus week (or day) ticks.
  const ticks: { x: number; label: string; major: boolean }[] = [];
  for (let d = range.first; d <= range.last; d++) {
    const date = new Date(d * DAY_MS);
    const monday = date.getUTCDay() === 1;
    const firstOfMonth = date.getUTCDate() === 1;
    if (zoom === 'day' || (zoom === 'week' && monday) || (zoom === 'month' && firstOfMonth)) {
      ticks.push({
        x: (d - range.first) * dayW,
        label: zoom === 'day' ? String(date.getUTCDate())
          : date.toLocaleDateString(locale(), { month: 'short', day: zoom === 'month' ? undefined : 'numeric', timeZone: 'UTC' }),
        major: firstOfMonth || (zoom !== 'day' && monday),
      });
    }
  }
  const arrows = (data?.dependencies ?? []).filter((dep) => index.has(dep.from) && index.has(dep.to)).map((dep) => {
    const from = bars[index.get(dep.from)!];
    const to = bars[index.get(dep.to)!];
    const a = geometry(from);
    const b = geometry(to);
    const x1 = a.left + a.width;
    const y1 = rowOf.get(dep.from)! * ROW + ROW / 2;
    const x2 = b.left;
    const y2 = rowOf.get(dep.to)! * ROW + ROW / 2;
    // Room for a simple elbow; otherwise step back around the start of the next bar.
    const d = x2 - x1 >= 16
      ? `M ${x1} ${y1} H ${x1 + 8} V ${y2} H ${x2 - 2}`
      : `M ${x1} ${y1} H ${x1 + 8} V ${(y1 + y2) / 2} H ${x2 - 10} V ${y2} H ${x2 - 2}`;
    return { id: `${dep.from}-${dep.to}`, d, critical: from.critical && to.critical, late: x2 < x1 };
  });

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t('Timeline')}</h1>
          <p className="muted">{t('Tasks with dates, what blocks what, and the critical path — the longest chain of dependent work.')}</p>
        </div>
        <div className="header-actions">
          <div className="segmented" role="radiogroup" aria-label={t('Zoom')}>
            {(Object.keys(ZOOMS) as Zoom[]).map((z) => (
              <label key={z} className={zoom === z ? 'active' : ''}>
                <input type="radio" name="zoom" checked={zoom === z} onChange={() => setZoom(z)} />
                {z === 'day' ? t('Days') : z === 'week' ? t('Weeks') : t('Months')}
              </label>
            ))}
          </div>
          <div className="segmented segmented-2" role="radiogroup" aria-label={t('Rows')}>
            {(['task', 'person'] as const).map((g) => (
              <label key={g} className={groupBy === g ? 'active' : ''}>
                <input type="radio" name="groupBy" checked={groupBy === g} onChange={() => setGroupBy(g)} />
                {g === 'task' ? t('By task') : t('By person')}
              </label>
            ))}
          </div>
          <label className="toggle">
            <input type="checkbox" checked={criticalOnly} onChange={(e) => setCriticalOnly(e.target.checked)} />
            {t('Critical path only')}
          </label>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <div className="timeline-summary">
            {data.criticalPath.length > 0 ? (
              <span className="chip chip-critical"><RouteIcon size={14} /> {t('Critical path: {n} tasks, {days} days', {
                n: data.criticalPath.length, days: data.criticalDays })}</span>
            ) : <span className="muted small">{t('No critical path yet — link tasks with “blocks” to see one.')}</span>}
            {data.tasks.some((b) => b.conflict) && (
              <span className="chip chip-warn"><AlertTriangle size={14} /> {t('Some tasks start before their blockers are due')}</span>
            )}
            {data.unscheduled > 0 && <span className="muted small">{t('{n} open tasks have no dates and are not shown.', { n: data.unscheduled })}</span>}
            <span className="muted small">{data.autoSchedule ? t('Automatic rescheduling is on.')
              : <>{t('Automatic rescheduling is off')} (<Link to={`/p/${key}/settings`}>{t('Settings')}</Link>).</>}</span>
          </div>
          {bars.length === 0 ? (
            <EmptyState icon={<GanttChart size={28} />} title={t('Nothing scheduled yet')}>
              {t('Give tasks a start or due date on their page and they appear here.')}
            </EmptyState>
          ) : (
            <div className="gantt" onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
              <div className="gantt-names">
                <div className="gantt-head" />
                {nameRows.map((row, i) => row.kind === 'task' ? (
                  <div key={row.bar.id} className={`gantt-name ${row.bar.critical ? 'critical' : ''}`} style={{ height: ROW }}>
                    <TypeIcon type={row.bar.type} />
                    <Link to={`/tasks/${row.bar.id}`} className="task-key">{row.bar.key}</Link>
                    <span className="gantt-title" title={row.bar.title}>{row.bar.title}</span>
                    {row.bar.assignee && <Avatar user={row.bar.assignee} size={20} />}
                  </div>
                ) : (
                  <div key={`p${i}`} className={`gantt-name gantt-person ${row.lane === 0 ? 'first' : ''}`} style={{ height: ROW }}>
                    {row.lane === 0 && (
                      <>
                        {row.user ? <Avatar user={row.user} size={22} /> : <span className="avatar-empty sm" />}
                        <span className="gantt-title">{row.user?.displayName ?? t('Unassigned')}</span>
                        <span className="muted small">{t('{n} tasks', { n: row.count })}</span>
                        {row.lanes > 1 && row.user && <span className="chip chip-warn chip-sm" title={t('Some of their tasks overlap in time')}>{t('Overlapping')}</span>}
                      </>
                    )}
                  </div>
                ))}
              </div>
              <div className="gantt-scroll">
                <div className="gantt-canvas" style={{ width }}>
                  <div className="gantt-head">
                    {ticks.map((tick) => (
                      <span key={tick.x} className={`gantt-tick ${tick.major ? 'major' : ''}`} style={{ left: tick.x }}>{tick.label}</span>
                    ))}
                  </div>
                  <div className="gantt-body" style={{ height: rowCount * ROW }}>
                    {ticks.map((tick) => <span key={tick.x} className={`gantt-line ${tick.major ? 'major' : ''}`} style={{ left: tick.x }} />)}
                    <span className="gantt-today" style={{ left: (range.today - range.first) * dayW + dayW / 2 }} title={t('Today')} />
                    <svg className="gantt-arrows" width={width} height={rowCount * ROW} aria-hidden>
                      <defs>
                        <marker id="gantt-head" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
                          <path d="M0,0 L8,4 L0,8 z" className="gantt-arrow-head" />
                        </marker>
                      </defs>
                      {arrows.map((a) => (
                        <path key={a.id} d={a.d} markerEnd="url(#gantt-head)"
                          className={`gantt-arrow ${a.critical ? 'critical' : ''} ${a.late ? 'late' : ''}`} />
                      ))}
                    </svg>
                    {bars.map((b) => {
                      const g = geometry(b);
                      const i = rowOf.get(b.id) ?? 0;
                      return (
                        <div key={b.id} className={`gantt-bar status-${b.status.toLowerCase()} ${b.critical ? 'critical' : ''} ${b.conflict ? 'conflict' : ''} ${drag?.id === b.id ? 'dragging' : ''}`}
                          style={{ left: g.left, width: g.width, top: i * ROW + 7 }}
                          title={`${b.key} ${b.title}\n${formatDay(toIso(g.start))} – ${formatDay(toIso(g.end))}${b.slack > 0 ? `\n${t('Slack: {n} days', { n: b.slack })}` : ''}`}
                          onPointerDown={onPointerDown(b, 'move')} role="img"
                          aria-label={`${b.key}: ${formatDay(toIso(g.start))} – ${formatDay(toIso(g.end))}${b.critical ? `, ${t('critical')}` : ''}`}>
                          <span className="gantt-bar-label">{g.width > 60 ? b.key : ''}</span>
                          {canEdit && <span className="gantt-handle" onPointerDown={onPointerDown(b, 'end')} />}
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          )}
          {bars.length > 0 && (
            <div className="gantt-legend muted small">
              <span><span className="legend-swatch critical" /> {t('Critical path')}</span>
              <span><span className="legend-swatch conflict" /> {t('Starts before a blocker is due')}</span>
              {canEdit && <span>{t('Drag a bar to move it; drag its right edge to change the due date.')}</span>}
            </div>
          )}
        </>
      )}
    </div>
  );
}
