import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Map as MapIcon, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError, type EpicInput } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { ConfirmDialog, Modal } from '../components/Modal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import type { Epic } from '../types';

const DAY = 86400000;

function toTime(day: string) {
  return new Date(`${day}T00:00:00`).getTime();
}

function monthStart(time: number) {
  const d = new Date(time);
  return new Date(d.getFullYear(), d.getMonth(), 1).getTime();
}

function addMonths(time: number, months: number) {
  const d = new Date(time);
  return new Date(d.getFullYear(), d.getMonth() + months, 1).getTime();
}

/** Epics with their progress, and a month-by-month timeline of their date ranges. */
export function RoadmapPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const toast = useToast();
  const [epics, setEpics] = useState<Epic[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Epic | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Epic | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.epics(key).then(setEpics).catch((e: ApiError) => setError(e.message));
  }, [key, project]);

  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id, load, 600);

  // The visible range: whole months covering every dated epic and today, at least four months.
  const range = useMemo(() => {
    const today = toTime(todayIso());
    const times = (epics ?? []).flatMap((e) => [e.startDate, e.dueDate]).filter((d): d is string => !!d).map(toTime);
    let start = monthStart(Math.min(today, ...times));
    let end = addMonths(monthStart(Math.max(today, ...times)), 1);
    while ((end - start) / DAY < 120) {
      end = addMonths(end, 1);
      if ((end - start) / DAY < 120) start = addMonths(start, -1);
    }
    const months: { time: number; label: string }[] = [];
    for (let t = start; t < end; t = addMonths(t, 1)) {
      const d = new Date(t);
      months.push({ time: t, label: d.toLocaleDateString(undefined, { month: 'short', year: d.getMonth() === 0 || t === start ? 'numeric' : undefined }) });
    }
    return { start, end, months, today };
  }, [epics]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const pct = (time: number) => ((time - range.start) / (range.end - range.start)) * 100;

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Roadmap</h1>
          <p className="muted">Epics group related tasks into bigger pieces of work.</p>
        </div>
        {canEdit && (
          <button className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={16} /> New epic</button>
        )}
      </header>

      {error && <ErrorBanner message={error} onRetry={load} />}
      {!epics && !error && <Spinner />}
      {epics && epics.length === 0 && (
        <EmptyState icon={<MapIcon size={28} />} title="No epics yet">
          {canEdit
            ? <>Create an epic, give it start and due dates, then add tasks to it from the task form.</>
            : 'The project has no epics yet.'}
        </EmptyState>
      )}
      {epics && epics.length > 0 && (
        <div className="roadmap panel">
          <div className="roadmap-row roadmap-head">
            <div className="roadmap-name muted small">Epic</div>
            <div className="roadmap-track">
              {range.months.map((m) => (
                <span key={m.time} className="roadmap-month" style={{ left: `${pct(m.time)}%` }}>{m.label}</span>
              ))}
            </div>
          </div>
          {epics.map((epic) => {
            const percent = epic.taskCount ? Math.round((epic.doneCount / epic.taskCount) * 100) : 0;
            const from = epic.startDate ? toTime(epic.startDate) : epic.dueDate ? toTime(epic.dueDate) - 13 * DAY : null;
            const to = epic.dueDate ? toTime(epic.dueDate) + DAY : epic.startDate ? toTime(epic.startDate) + 14 * DAY : null;
            const color = `var(--cat-${epic.colorIndex % 8})`;
            return (
              <div key={epic.id} className="roadmap-row">
                <div className="roadmap-name">
                  <div className="roadmap-title">
                    <span className="epic-dot" style={{ background: color }} aria-hidden />
                    <Link to={`/p/${key}/backlog?epic=${epic.id}`} title="Show this epic's tasks">{epic.name}</Link>
                    {canEdit && (
                      <span className="roadmap-actions">
                        <button className="icon-button sm" aria-label={`Edit ${epic.name}`} onClick={() => setEditing(epic)}>
                          <Pencil size={14} />
                        </button>
                        <button className="icon-button sm" aria-label={`Delete ${epic.name}`} onClick={() => setDeleting(epic)}>
                          <Trash2 size={14} />
                        </button>
                      </span>
                    )}
                  </div>
                  <div className="progress" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}
                    aria-label={`${epic.name} progress`}>
                    <span style={{ width: `${percent}%` }} />
                  </div>
                  <span className="muted small">
                    {epic.doneCount}/{epic.taskCount} tasks{epic.points ? ` · ${epic.donePoints}/${epic.points} pts` : ''}
                  </span>
                </div>
                <div className="roadmap-track">
                  {range.months.map((m) => <span key={m.time} className="roadmap-gridline" style={{ left: `${pct(m.time)}%` }} />)}
                  <span className="roadmap-today" style={{ left: `${pct(range.today)}%` }} title="Today" />
                  {from !== null && to !== null ? (
                    <div className="roadmap-bar" style={{ left: `${pct(from)}%`, width: `${Math.max(1, pct(to) - pct(from))}%`, borderColor: color }}
                      title={`${epic.startDate ? formatDay(epic.startDate, true) : '?'} – ${epic.dueDate ? formatDay(epic.dueDate, true) : '?'}`}>
                      <span className="roadmap-fill" style={{ width: `${percent}%`, background: color }} />
                      <span className="roadmap-bar-label">{percent}%</span>
                    </div>
                  ) : (
                    <span className="roadmap-nodates muted small">No dates{canEdit ? ' — edit the epic to plan it' : ''}</span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {editing && (
        <EpicModal epic={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
          onSave={async (input) => {
            if (editing === 'new') await api.createEpic(key, input);
            else await api.updateEpic(editing.id, input);
            toast(editing === 'new' ? `Epic “${input.name}” created` : 'Epic saved');
            setEditing(null);
            load();
          }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.name}?`} confirmLabel="Delete epic" danger
          message="Its tasks stay in the project and simply lose the epic."
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            try {
              await api.deleteEpic(deleting.id);
              toast('Epic deleted');
              load();
            } catch (e) {
              toast((e as ApiError).message, 'error');
            }
            setDeleting(null);
          }} />
      )}
    </div>
  );
}

function EpicModal({ epic, onClose, onSave }: { epic: Epic | null; onClose: () => void; onSave: (input: EpicInput) => Promise<void> }) {
  const [form, setForm] = useState<EpicInput>({
    name: epic?.name ?? '', description: epic?.description ?? '', startDate: epic?.startDate ?? null, dueDate: epic?.dueDate ?? null,
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.name.trim()) {
      setError('Name is required');
      return;
    }
    if (form.startDate && form.dueDate && form.dueDate < form.startDate) {
      setError('The due date must be after the start date');
      return;
    }
    setBusy(true);
    try {
      await onSave({ ...form, name: form.name.trim() });
    } catch (e) {
      setError((e as ApiError).message);
      setBusy(false);
    }
  };

  return (
    <Modal title={epic ? 'Edit epic' : 'New epic'} onClose={onClose}
      footer={<>
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" form="epic-form" className="btn btn-primary" disabled={busy}>{epic ? 'Save' : 'Create epic'}</button>
      </>}>
      <form id="epic-form" className="form" onSubmit={submit} noValidate>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>Name</span>
          <input value={form.name} maxLength={80} autoFocus placeholder="e.g. Mobile checkout"
            onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
        <label className="field">
          <span>Description</span>
          <textarea rows={3} maxLength={1000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </label>
        <div className="form-grid">
          <label className="field">
            <span>Start date</span>
            <input type="date" value={form.startDate ?? ''} onChange={(e) => setForm({ ...form, startDate: e.target.value || null })} />
          </label>
          <label className="field">
            <span>Due date</span>
            <input type="date" value={form.dueDate ?? ''} onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />
          </label>
        </div>
      </form>
    </Modal>
  );
}
