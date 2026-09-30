import { createContext, useCallback, useContext, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useProjects } from '../projects';
import { RESOLUTIONS, RESOLUTION_LABEL, type CustomFieldValue, type Resolution, type Task } from '../types';
import { Modal } from './Modal';
import { t } from '../i18n';

/**
 * Runs a status or column change; when the project's workflow asks for something first (a resolution, an
 * assignee, points…), asks for it in a dialog, saves it and retries. Resolves to the updated task, or null
 * when the person cancels or the move is not allowed.
 */
type Attempt = (resolution?: Resolution | null) => Promise<Task>;
type Guard = (task: Task, attempt: Attempt) => Promise<Task | null>;

const TransitionContext = createContext<Guard>(async (_task, attempt) => attempt());

export const useTransitionGuard = () => useContext(TransitionContext);

interface Pending {
  task: Task;
  attempt: Attempt;
  required: string[];
  column: string;
  message: string;
  resolve: (task: Task | null) => void;
}

export function TransitionProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);

  const guard = useCallback<Guard>(async (task, attempt) => {
    try {
      return await attempt();
    } catch (e) {
      const error = e as ApiError;
      const required = error.fieldErrors?.required;
      if (error.status !== 400 || !required) throw e;
      return new Promise<Task | null>((resolve) => setPending({
        task, attempt, required: required.split(','), column: error.fieldErrors.column ?? '', message: error.message, resolve,
      }));
    }
  }, []);

  return (
    <TransitionContext.Provider value={guard}>
      {children}
      {pending && <RequirementsModal pending={pending} onDone={(task) => {
        pending.resolve(task);
        setPending(null);
      }} />}
    </TransitionContext.Provider>
  );
}

function RequirementsModal({ pending, onDone }: { pending: Pending; onDone: (task: Task | null) => void }) {
  const { byKey } = useProjects();
  const { task, required } = pending;
  const members = (byKey(task.projectKey)?.members ?? []).filter((m) => m.role !== 'VIEWER');
  const [resolution, setResolution] = useState<Resolution>('DONE');
  const [assignee, setAssignee] = useState<string>(task.assignee ? String(task.assignee.id) : '');
  const [points, setPoints] = useState(task.storyPoints?.toString() ?? '');
  const [due, setDue] = useState(task.dueDate ?? '');
  const [hours, setHours] = useState(task.estimateMinutes ? String(task.estimateMinutes / 60) : '');
  const [fields, setFields] = useState<CustomFieldValue[] | null>(null);
  const [values, setValues] = useState<Record<number, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const loaded = useRef(false);

  const fieldIds = required.filter((r) => r.startsWith('field:')).map((r) => Number(r.slice(6)));
  if (fieldIds.length && !loaded.current) {
    loaded.current = true;
    api.taskFields(task.id).then(setFields).catch(() => setFields([]));
  }
  const needs = (key: string) => required.includes(key);
  const blockedByApproval = needs('approval');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      let current = task;
      if (needs('assignee') && assignee) current = await api.assign(task.id, Number(assignee));
      if (needs('points') && points !== '') {
        current = await api.updateTask(task.id, {
          title: current.title, description: current.description, priority: current.priority, dueDate: current.dueDate,
          labels: current.labels, storyPoints: Number(points), epicId: current.epic?.id ?? null, type: current.type,
        });
      }
      if ((needs('due') && due) || (needs('estimate') && hours)) {
        current = await api.schedule(task.id, {
          startDate: current.startDate, dueDate: needs('due') && due ? due : current.dueDate,
          estimateMinutes: needs('estimate') && hours ? Math.round(Number(hours) * 60) : current.estimateMinutes,
        });
      }
      for (const id of fieldIds) {
        if (values[id]) await api.setTaskField(task.id, id, values[id]);
      }
      onDone(await pending.attempt(needs('resolution') ? resolution : null));
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={pending.column ? t('Move to {column}', { column: pending.column }) : t('Before moving on')}
      onClose={() => onDone(null)} footer={
        <>
          <button className="btn btn-ghost" onClick={() => onDone(null)}>{t('Cancel')}</button>
          {!blockedByApproval && <button className="btn btn-primary" form="requirements" disabled={busy}>{t('Save and move')}</button>}
        </>
      }>
      <form id="requirements" className="form" onSubmit={submit}>
        <p className="muted">{pending.message}</p>
        {error && <div className="alert" role="alert">{error}</div>}
        {needs('resolution') && (
          <label className="field">
            <span>{t('Resolution')}</span>
            <select value={resolution} onChange={(e) => setResolution(e.target.value as Resolution)}>
              {RESOLUTIONS.map((r) => <option key={r} value={r}>{t(RESOLUTION_LABEL[r])}</option>)}
            </select>
          </label>
        )}
        {needs('assignee') && (
          <label className="field">
            <span>{t('Assignee')}</span>
            <select value={assignee} required onChange={(e) => setAssignee(e.target.value)}>
              <option value="">{t('Choose')}…</option>
              {members.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
            </select>
          </label>
        )}
        {needs('points') && (
          <label className="field">
            <span>{t('Story points')}</span>
            <input type="number" min={0} max={100} required value={points} onChange={(e) => setPoints(e.target.value)} />
          </label>
        )}
        {needs('due') && (
          <label className="field">
            <span>{t('Due date')}</span>
            <input type="date" required value={due} onChange={(e) => setDue(e.target.value)} />
          </label>
        )}
        {needs('estimate') && (
          <label className="field">
            <span>{t('Estimate (hours)')}</span>
            <input type="number" min={0.25} step={0.25} required value={hours} onChange={(e) => setHours(e.target.value)} />
          </label>
        )}
        {fieldIds.map((id) => {
          const field = fields?.find((f) => f.fieldId === id);
          if (!field) return null;
          return (
            <label key={id} className="field">
              <span>{field.name}</span>
              {field.type === 'SELECT' ? (
                <select required value={values[id] ?? ''} onChange={(e) => setValues({ ...values, [id]: e.target.value })}>
                  <option value="">—</option>
                  {field.options.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              ) : (
                <input required value={values[id] ?? ''} type={field.type === 'NUMBER' ? 'number' : field.type === 'DATE' ? 'date'
                  : field.type === 'URL' ? 'url' : 'text'} onChange={(e) => setValues({ ...values, [id]: e.target.value })} />
              )}
            </label>
          );
        })}
        {blockedByApproval && (
          <div className="alert info">
            {t('This column needs every approval on the task to be granted.')}{' '}
            <Link to={`/tasks/${task.id}#approvals`} onClick={() => onDone(null)}>{t('Open the task to ask for approval')}</Link>
          </div>
        )}
      </form>
    </Modal>
  );
}
