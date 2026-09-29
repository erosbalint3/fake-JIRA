import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowDown, ArrowUp, CalendarDays, Clock, Gauge, LayoutDashboard, ListFilter, Pencil, Plus, Rows3, Trash2, Zap,
} from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useProjects } from '../projects';
import { Avatar } from '../components/Avatar';
import { DueBadge, PriorityBadge, StatusBadge, TypeIcon } from '../components/Badges';
import { ConfirmDialog, Modal } from '../components/Modal';
import { ErrorBanner, Spinner } from '../components/States';
import { formatDay, timeAgo, todayIso } from '../format';
import type {
  Burndown, CalendarEvent, Dashboard, FeedItem, RecentTask, SearchGroup, SearchResult, Widget, WidgetType,
} from '../types';

const WIDGET_TYPES: { type: WidgetType; label: string; hint: string }[] = [
  { type: 'filter', label: 'Task list', hint: 'Tasks matching a query' },
  { type: 'counter', label: 'Counter', hint: 'How many tasks match a query' },
  { type: 'chart', label: 'Chart', hint: 'Tasks of a query grouped by a field' },
  { type: 'sprint', label: 'Sprint progress', hint: "A project's active sprint" },
  { type: 'calendar', label: 'Upcoming', hint: 'Due dates, sprints and releases in the next two weeks' },
  { type: 'activity', label: 'Activity', hint: 'Latest changes and comments' },
  { type: 'recent', label: 'Recently viewed', hint: 'Tasks you opened lately' },
];

const GROUPS = ['status', 'priority', 'assignee', 'type', 'project', 'epic', 'sprint', 'release'];

function addDays(days: number) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function DashboardPage() {
  const toast = useToast();
  const [dashboards, setDashboards] = useState<Dashboard[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [widgetDialog, setWidgetDialog] = useState<{ index: number | null } | null>(null);
  const [nameDialog, setNameDialog] = useState<'new' | 'rename' | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    api.dashboards().then((list) => {
      setDashboards(list);
      setSelected((current) => (current && list.some((d) => d.id === current) ? current : list[0]?.id ?? null));
    }).catch((e: ApiError) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' || m.type === 'project', () => setTick((t) => t + 1), 1500);

  const dashboard = dashboards?.find((d) => d.id === selected) ?? null;

  const save = async (widgets: Widget[], name = dashboard?.name ?? '') => {
    if (!dashboard) return;
    const previous = dashboards;
    setDashboards((list) => list?.map((d) => (d.id === dashboard.id ? { ...d, name, widgets } : d)) ?? null);
    try {
      await api.updateDashboard(dashboard.id, name, widgets);
    } catch (e) {
      setDashboards(previous);
      toast((e as ApiError).message, 'error');
    }
  };

  const move = (index: number, delta: number) => {
    if (!dashboard) return;
    const widgets = [...dashboard.widgets];
    const [w] = widgets.splice(index, 1);
    widgets.splice(index + delta, 0, w);
    save(widgets);
  };

  if (error) return <div className="page"><ErrorBanner message={error} onRetry={load} /></div>;
  if (!dashboards || !dashboard) return <div className="page"><Spinner /></div>;

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">Dashboard</span>
          <h1>{dashboard.name}</h1>
        </div>
        <div className="header-actions">
          {dashboards.length > 1 && (
            <select value={dashboard.id} onChange={(e) => setSelected(Number(e.target.value))} aria-label="Dashboard">
              {dashboards.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          )}
          {editing ? (
            <>
              <button className="btn btn-ghost btn-sm" onClick={() => setNameDialog('rename')}><Pencil size={15} /> Rename</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setNameDialog('new')}><Plus size={15} /> New dashboard</button>
              {dashboards.length > 1 && (
                <button className="btn btn-ghost btn-sm danger" onClick={() => setConfirmDelete(true)}><Trash2 size={15} /> Delete</button>
              )}
              <button className="btn btn-soft btn-sm" onClick={() => setWidgetDialog({ index: null })}><Plus size={15} /> Add widget</button>
              <button className="btn btn-primary btn-sm" onClick={() => setEditing(false)}>Done</button>
            </>
          ) : (
            <button className="btn btn-ghost btn-sm" onClick={() => setEditing(true)}><Pencil size={15} /> Edit dashboard</button>
          )}
        </div>
      </header>

      {dashboard.widgets.length === 0 && (
        <div className="panel dashboard-empty">
          <LayoutDashboard size={28} />
          <p>This dashboard is empty.</p>
          <button className="btn btn-primary" onClick={() => setWidgetDialog({ index: null })}><Plus size={16} /> Add a widget</button>
        </div>
      )}
      <div className="dashboard-grid">
        {dashboard.widgets.map((widget, index) => (
          <section key={`${dashboard.id}-${index}`} className={`panel widget widget-${widget.type}`} aria-label={widget.title}>
            <header className="widget-head">
              <h2 className="panel-title">{widget.title}</h2>
              {editing && (
                <span className="widget-tools">
                  <button className="icon-button sm" aria-label={`Move ${widget.title} up`} disabled={index === 0}
                    onClick={() => move(index, -1)}><ArrowUp size={14} /></button>
                  <button className="icon-button sm" aria-label={`Move ${widget.title} down`} disabled={index === dashboard.widgets.length - 1}
                    onClick={() => move(index, 1)}><ArrowDown size={14} /></button>
                  <button className="icon-button sm" aria-label={`Edit ${widget.title}`} onClick={() => setWidgetDialog({ index })}>
                    <Pencil size={14} /></button>
                  <button className="icon-button sm" aria-label={`Remove ${widget.title}`}
                    onClick={() => save(dashboard.widgets.filter((_, i) => i !== index))}><Trash2 size={14} /></button>
                </span>
              )}
            </header>
            <WidgetBody widget={widget} tick={tick} />
          </section>
        ))}
      </div>

      {widgetDialog && (
        <WidgetModal widget={widgetDialog.index === null ? null : dashboard.widgets[widgetDialog.index]}
          onClose={() => setWidgetDialog(null)}
          onSave={(widget) => {
            const widgets = [...dashboard.widgets];
            if (widgetDialog.index === null) widgets.push(widget);
            else widgets[widgetDialog.index] = widget;
            setWidgetDialog(null);
            save(widgets);
          }} />
      )}
      {nameDialog && (
        <NameModal initial={nameDialog === 'rename' ? dashboard.name : ''} title={nameDialog === 'rename' ? 'Rename dashboard' : 'New dashboard'}
          onClose={() => setNameDialog(null)}
          onSave={async (name) => {
            try {
              if (nameDialog === 'rename') {
                await save(dashboard.widgets, name);
              } else {
                const created = await api.createDashboard(name, []);
                setDashboards((list) => [...(list ?? []), created]);
                setSelected(created.id);
              }
              setNameDialog(null);
            } catch (e) {
              toast((e as ApiError).message, 'error');
            }
          }} />
      )}
      {confirmDelete && (
        <ConfirmDialog title={`Delete ${dashboard.name}?`} message="Its widgets are removed. Your tasks are not affected."
          confirmLabel="Delete dashboard" danger onClose={() => setConfirmDelete(false)}
          onConfirm={async () => {
            setConfirmDelete(false);
            await api.deleteDashboard(dashboard.id);
            setEditing(false);
            load();
          }} />
      )}
    </div>
  );
}

function useWidgetData<T>(fetcher: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    fetcher().then((d) => !cancelled && (setData(d), setError(''))).catch((e: ApiError) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { data, error };
}

function WidgetBody({ widget, tick }: { widget: Widget; tick: number }) {
  switch (widget.type) {
    case 'filter': return <FilterWidget query={widget.query ?? ''} limit={widget.limit ?? 8} tick={tick} />;
    case 'counter': return <CounterWidget query={widget.query ?? ''} tick={tick} />;
    case 'chart': return <ChartWidget query={widget.query ?? ''} groupBy={widget.groupBy ?? 'status'} tick={tick} />;
    case 'sprint': return <SprintWidget project={widget.project ?? ''} tick={tick} />;
    case 'calendar': return <UpcomingWidget project={widget.project} tick={tick} />;
    case 'activity': return <ActivityWidget project={widget.project} tick={tick} />;
    default: return <RecentWidget tick={tick} />;
  }
}

function WidgetState({ error, loading, children }: { error: string; loading: boolean; children: ReactNode }) {
  if (error) return <p className="widget-error small">{error}</p>;
  if (loading) return <Spinner />;
  return <>{children}</>;
}

function FilterWidget({ query, limit, tick }: { query: string; limit: number; tick: number }) {
  const { data, error } = useWidgetData<SearchResult>(() => api.search(query, limit), [query, limit, tick]);
  return (
    <WidgetState error={error} loading={!data}>
      {data && (data.tasks.length === 0 ? <p className="muted">Nothing here. 🎉</p> : (
        <>
          <ul className="widget-list">
            {data.tasks.map((t) => (
              <li key={t.id}>
                <TypeIcon type={t.type} />
                <Link to={`/tasks/${t.id}`}><span className="task-key">{t.key}</span> {t.title}</Link>
                <span className="spacer" />
                {t.dueDate && t.status !== 'DONE' && <DueBadge date={t.dueDate} done={false} />}
                <PriorityBadge priority={t.priority} compact />
                {t.assignee && <Avatar user={t.assignee} size={20} />}
              </li>
            ))}
          </ul>
          <Link className="small widget-more" to={`/search?q=${encodeURIComponent(query)}`}>
            {data.total > data.tasks.length ? `All ${data.total} tasks →` : 'Open in search →'}
          </Link>
        </>
      ))}
    </WidgetState>
  );
}

function CounterWidget({ query, tick }: { query: string; tick: number }) {
  const { data, error } = useWidgetData<SearchResult>(() => api.search(query, 1), [query, tick]);
  return (
    <WidgetState error={error} loading={!data}>
      {data && (
        <Link className="counter" to={`/search?q=${encodeURIComponent(query)}`}>
          <strong>{data.total}</strong>
          <span className="muted small">task{data.total === 1 ? '' : 's'}</span>
        </Link>
      )}
    </WidgetState>
  );
}

function ChartWidget({ query, groupBy, tick }: { query: string; groupBy: string; tick: number }) {
  const { data, error } = useWidgetData<SearchGroup[]>(() => api.searchStats(query, groupBy), [query, groupBy, tick]);
  const max = Math.max(1, ...(data ?? []).map((g) => g.count));
  const total = (data ?? []).reduce((s, g) => s + g.count, 0);
  return (
    <WidgetState error={error} loading={!data}>
      {data && (data.length === 0 ? <p className="muted">No tasks match.</p> : (
        <table className="bar-table" aria-label={`Tasks by ${groupBy}`}>
          <tbody>
            {data.map((g, i) => (
              <tr key={g.key || '∅'}>
                <th scope="row">{g.label}</th>
                <td>
                  <span className="bar"><span style={{ width: `${(g.count / max) * 100}%`, background: `var(--cat-${i % 8})` }} /></span>
                </td>
                <td className="num">{g.count}</td>
                <td className="num muted small">{Math.round((g.count / total) * 100)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </WidgetState>
  );
}

function SprintWidget({ project, tick }: { project: string; tick: number }) {
  const { data, error } = useWidgetData<Burndown | 'none'>(async () => {
    const sprints = await api.sprints(project);
    const active = sprints.find((s) => s.state === 'ACTIVE');
    return active ? api.burndown(active.id) : 'none';
  }, [project, tick]);
  if (data === 'none') return <p className="muted">{project} has no active sprint.</p>;
  const b = data;
  const usePoints = !!b && b.totalPoints > 0;
  const total = b ? (usePoints ? b.totalPoints : b.total) : 0;
  const done = b ? (usePoints ? b.donePoints : b.done) : 0;
  const percent = total ? Math.round((done / total) * 100) : 0;
  const daysLeft = b?.sprint.endDate
    ? Math.round((new Date(`${b.sprint.endDate}T00:00:00`).getTime() - new Date(`${todayIso()}T00:00:00`).getTime()) / 86400000) : null;
  return (
    <WidgetState error={error} loading={!b}>
      {b && (
        <div className="sprint-widget">
          <p><Zap size={14} /> <strong>{b.sprint.name}</strong>
            {daysLeft !== null && <span className="muted"> · {daysLeft < 0 ? `${-daysLeft} days over` : `${daysLeft} days left`}</span>}</p>
          <div className="progress big"><span style={{ width: `${percent}%` }} /></div>
          <p className="muted small">{done} of {total} {usePoints ? 'points' : 'tasks'} done ({percent}%)
            {b.changes.length > 0 && ` · ${b.changes.length} scope change${b.changes.length === 1 ? '' : 's'}`}</p>
          <Link className="small" to={`/p/${project}/board`}>Open board →</Link>
        </div>
      )}
    </WidgetState>
  );
}

function UpcomingWidget({ project, tick }: { project?: string; tick: number }) {
  const { data, error } = useWidgetData<CalendarEvent[]>(() => api.calendar(todayIso(), addDays(14), project), [project, tick]);
  const upcoming = (data ?? []).filter((e) => e.kind !== 'EPIC' && (e.kind !== 'TASK' || e.status !== 'DONE')).slice(0, 10);
  return (
    <WidgetState error={error} loading={!data}>
      {upcoming.length === 0 ? <p className="muted">Nothing in the next two weeks.</p> : (
        <ul className="widget-list">
          {upcoming.map((e) => (
            <li key={`${e.kind}-${e.id}`}>
              <span className={`cal-dot cal-${e.kind.toLowerCase()}`} />
              <span className="muted small nowrap">{formatDay(e.start)}</span>
              {e.kind === 'TASK' ? <Link to={`/tasks/${e.id}`}>{e.key} {e.title}</Link>
                : <span>{e.kind === 'AWAY' ? `${e.person?.displayName} away` : e.kind === 'SPRINT' ? `${e.title} (sprint)` : `${e.title} release`}</span>}
            </li>
          ))}
        </ul>
      )}
      <Link className="small widget-more" to="/calendar"><CalendarDays size={13} /> Calendar →</Link>
    </WidgetState>
  );
}

function ActivityWidget({ project, tick }: { project?: string; tick: number }) {
  const { data, error } = useWidgetData<FeedItem[]>(() => api.feed({ project, limit: 8 }), [project, tick]);
  return (
    <WidgetState error={error} loading={!data}>
      {data && (data.length === 0 ? <p className="muted">No activity yet.</p> : (
        <ul className="widget-list feed-mini">
          {data.map((item) => (
            <li key={`${item.kind}-${item.id}`}>
              <Avatar user={item.actor} size={20} />
              <span className="feed-line">
                <strong>{item.actor.displayName}</strong> {item.message}{' '}
                <Link to={`/tasks/${item.task.id}`}>{item.task.key}</Link>
                <span className="muted small"> · {timeAgo(item.createdAt)}</span>
              </span>
            </li>
          ))}
        </ul>
      ))}
      <Link className="small widget-more" to="/activity">All activity →</Link>
    </WidgetState>
  );
}

function RecentWidget({ tick }: { tick: number }) {
  const { data, error } = useWidgetData<RecentTask[]>(() => api.recent(), [tick]);
  return (
    <WidgetState error={error} loading={!data}>
      {data && (data.length === 0 ? <p className="muted">Tasks you open show up here.</p> : (
        <ul className="widget-list">
          {data.slice(0, 8).map((t) => (
            <li key={t.id}>
              <TypeIcon type={t.type} />
              <Link to={`/tasks/${t.id}`}><span className="task-key">{t.key}</span> {t.title}</Link>
              <span className="spacer" />
              <StatusBadge status={t.status} />
            </li>
          ))}
        </ul>
      ))}
    </WidgetState>
  );
}

function WidgetModal({ widget, onClose, onSave }: { widget: Widget | null; onClose: () => void; onSave: (w: Widget) => void }) {
  const { projects } = useProjects();
  const [type, setType] = useState<WidgetType>(widget?.type ?? 'filter');
  const [title, setTitle] = useState(widget?.title ?? '');
  const [query, setQuery] = useState(widget?.query ?? 'assignee = me AND status != done ORDER BY priority DESC');
  const [groupBy, setGroupBy] = useState(widget?.groupBy ?? 'status');
  const [project, setProject] = useState(widget?.project ?? projects?.[0]?.key ?? '');
  const [limit, setLimit] = useState(widget?.limit ?? 8);
  const [error, setError] = useState('');
  const needsQuery = type === 'filter' || type === 'counter' || type === 'chart';
  const icons: Record<WidgetType, ReactNode> = {
    filter: <ListFilter size={16} />, counter: <Gauge size={16} />, chart: <Rows3 size={16} />, sprint: <Zap size={16} />,
    calendar: <CalendarDays size={16} />, activity: <Clock size={16} />, recent: <Clock size={16} />,
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (needsQuery) {
      try {
        await api.search(query, 1);
      } catch (e) {
        setError(`Query: ${(e as ApiError).message}`);
        return;
      }
    }
    const label = WIDGET_TYPES.find((w) => w.type === type)!.label;
    onSave({
      type, title: title.trim() || label,
      ...(needsQuery ? { query } : {}),
      ...(type === 'chart' ? { groupBy } : {}),
      ...(type === 'filter' ? { limit } : {}),
      ...(type === 'sprint' || type === 'activity' || type === 'calendar' ? { project: project || undefined } : {}),
    });
  };

  return (
    <Modal title={widget ? 'Edit widget' : 'Add widget'} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" form="widget-form">{widget ? 'Save' : 'Add'}</button>
      </>
    }>
      <form id="widget-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <div className="widget-types" role="radiogroup" aria-label="Widget type">
          {WIDGET_TYPES.map((w) => (
            <label key={w.type} className={`widget-type ${type === w.type ? 'active' : ''}`}>
              <input type="radio" name="widget-type" checked={type === w.type} onChange={() => setType(w.type)} />
              {icons[w.type]} <strong>{w.label}</strong>
              <span className="muted small">{w.hint}</span>
            </label>
          ))}
        </div>
        <label className="field">
          <span>Title</span>
          <input value={title} maxLength={60} placeholder={WIDGET_TYPES.find((w) => w.type === type)?.label}
            onChange={(e) => setTitle(e.target.value)} />
        </label>
        {needsQuery && (
          <label className="field">
            <span>Query</span>
            <input className="mono" value={query} onChange={(e) => setQuery(e.target.value)} spellCheck={false} />
            <span className="muted small">Same language as <Link to="/search" target="_blank">Search</Link>.</span>
          </label>
        )}
        {type === 'chart' && (
          <label className="field">
            <span>Group by</span>
            <select value={groupBy} onChange={(e) => setGroupBy(e.target.value)}>
              {GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
            </select>
          </label>
        )}
        {type === 'filter' && (
          <label className="field">
            <span>Show</span>
            <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {[5, 8, 12, 20].map((n) => <option key={n} value={n}>{n} tasks</option>)}
            </select>
          </label>
        )}
        {(type === 'sprint' || type === 'activity' || type === 'calendar') && (
          <label className="field">
            <span>Project</span>
            <select value={project} onChange={(e) => setProject(e.target.value)}>
              {type !== 'sprint' && <option value="">All projects</option>}
              {(projects ?? []).map((p) => <option key={p.key} value={p.key}>{p.key} · {p.name}</option>)}
            </select>
          </label>
        )}
      </form>
    </Modal>
  );
}

function NameModal({ initial, title, onClose, onSave }: {
  initial: string; title: string; onClose: () => void; onSave: (name: string) => void;
}) {
  const [name, setName] = useState(initial);
  return (
    <Modal title={title} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" form="name-form" disabled={!name.trim()}>Save</button>
      </>
    }>
      <form id="name-form" className="form" onSubmit={(e) => {
        e.preventDefault();
        onSave(name.trim());
      }}>
        <label className="field">
          <span>Name</span>
          <input value={name} maxLength={60} autoFocus onChange={(e) => setName(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
