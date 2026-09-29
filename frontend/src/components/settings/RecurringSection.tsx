import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Pause, Play, Plus, Repeat, Trash2 } from 'lucide-react';
import { api, ApiError, type RecurringInput } from '../../api';
import { useToast } from '../../toast';
import { formatDay } from '../../format';
import { Modal } from '../Modal';
import { TypeIcon } from '../Badges';
import {
  PRIORITIES, PRIORITY_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type Frequency, type Member, type RecurringTask,
} from '../../types';

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export function describeSchedule(r: { frequency: Frequency; dayOfWeek: number; dayOfMonth: number }) {
  switch (r.frequency) {
    case 'DAILY': return 'Every day';
    case 'WEEKDAYS': return 'Every weekday';
    case 'WEEKLY': return `Every ${WEEKDAYS[r.dayOfWeek - 1]}`;
    case 'MONTHLY': return `Monthly on day ${r.dayOfMonth}`;
  }
}

const EMPTY: RecurringInput = {
  title: '', description: '', type: 'TASK', priority: 'MEDIUM', labels: [], checklist: [], assigneeId: null,
  frequency: 'WEEKLY', dayOfWeek: 1, dayOfMonth: 1, dueInDays: null, active: true,
};

/** Tasks that create themselves on a schedule. */
export function RecurringSection({ projectKey, members, canEdit }: { projectKey: string; members: Member[]; canEdit: boolean }) {
  const toast = useToast();
  const [rules, setRules] = useState<RecurringTask[] | null>(null);
  const [editing, setEditing] = useState<{ id: number | null; input: RecurringInput } | null>(null);

  const load = useCallback(() => {
    api.recurring(projectKey).then(setRules).catch(() => setRules([]));
  }, [projectKey]);
  useEffect(load, [load]);

  const toInput = (r: RecurringTask): RecurringInput => ({ ...r, assigneeId: r.assignee?.id ?? null });

  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title"><Repeat size={16} /> Recurring tasks</h2>
        {canEdit && (
          <button className="btn btn-soft btn-sm" onClick={() => setEditing({ id: null, input: EMPTY })}><Plus size={15} /> New</button>
        )}
      </div>
      <p className="muted small hint">E.g. “Every Monday: deploy review”. Tasks are created early in the morning of each scheduled day.</p>
      {rules && rules.length > 0 && (
        <ul className="mini-list">
          {rules.map((r) => (
            <li key={r.id} className={r.active ? '' : 'paused'}>
              <TypeIcon type={r.type} />
              <div className="invite-text">
                <strong>{r.title}</strong>
                <span className="muted small">
                  {describeSchedule(r)}{r.assignee ? ` · ${r.assignee.displayName}` : ''}
                  {r.active ? ` · next ${formatDay(r.nextRun)}` : ' · paused'}
                  {r.lastTaskKey ? ` · last ${r.lastTaskKey}` : ''}
                </span>
              </div>
              {canEdit && (
                <>
                  <button className="btn btn-ghost btn-sm" onClick={async () => {
                    try {
                      const task = await api.runRecurring(r.id);
                      toast(`Created ${task.key}`);
                      load();
                    } catch (e) {
                      toast((e as ApiError).message, 'error');
                    }
                  }}>Create now</button>
                  <button className="icon-button sm" aria-label={r.active ? `Pause ${r.title}` : `Resume ${r.title}`}
                    title={r.active ? 'Pause' : 'Resume'} onClick={async () => {
                      await api.updateRecurring(r.id, { ...toInput(r), active: !r.active });
                      load();
                    }}>{r.active ? <Pause size={14} /> : <Play size={14} />}</button>
                  <button className="link small" onClick={() => setEditing({ id: r.id, input: toInput(r) })}>Edit</button>
                  <button className="icon-button sm" aria-label={`Delete ${r.title}`} onClick={async () => {
                    await api.deleteRecurring(r.id);
                    load();
                  }}><Trash2 size={14} /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {rules && rules.length === 0 && <p className="muted small">No recurring tasks yet.</p>}
      {editing && (
        <RecurringModal initial={editing.input} isNew={editing.id === null} members={members} onClose={() => setEditing(null)}
          onSave={async (input) => {
            if (editing.id === null) await api.createRecurring(projectKey, input);
            else await api.updateRecurring(editing.id, input);
            setEditing(null);
            toast('Recurring task saved');
            load();
          }} />
      )}
    </section>
  );
}

function RecurringModal({ initial, isNew, members, onClose, onSave }: {
  initial: RecurringInput;
  isNew: boolean;
  members: Member[];
  onClose: () => void;
  onSave: (input: RecurringInput) => Promise<void>;
}) {
  const [form, setForm] = useState(initial);
  const [checklist, setChecklist] = useState(initial.checklist.join('\n'));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = <K extends keyof RecurringInput>(key: K, value: RecurringInput[K]) => setForm({ ...form, [key]: value });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await onSave({ ...form, checklist: checklist.split('\n').map((l) => l.trim()).filter(Boolean) });
    } catch (e) {
      setError((e as ApiError).message);
      setBusy(false);
    }
  };

  return (
    <Modal title={isNew ? 'New recurring task' : 'Edit recurring task'} onClose={onClose} wide footer={<>
      <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
      <button type="submit" form="recurring-form" className="btn btn-primary" disabled={busy || !form.title.trim()}>Save</button>
    </>}>
      <form id="recurring-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field"><span>Title</span>
          <input value={form.title} maxLength={120} onChange={(e) => set('title', e.target.value)} autoFocus />
        </label>
        <div className="form-grid">
          <label className="field"><span>Repeats</span>
            <select value={form.frequency} onChange={(e) => set('frequency', e.target.value as Frequency)}>
              <option value="DAILY">Every day</option>
              <option value="WEEKDAYS">Every weekday (Mon–Fri)</option>
              <option value="WEEKLY">Every week</option>
              <option value="MONTHLY">Every month</option>
            </select>
          </label>
          {form.frequency === 'WEEKLY' && (
            <label className="field"><span>On</span>
              <select value={form.dayOfWeek} onChange={(e) => set('dayOfWeek', Number(e.target.value))}>
                {WEEKDAYS.map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
              </select>
            </label>
          )}
          {form.frequency === 'MONTHLY' && (
            <label className="field"><span>Day of month</span>
              <input type="number" min={1} max={28} value={form.dayOfMonth} onChange={(e) => set('dayOfMonth', Number(e.target.value))} />
            </label>
          )}
          <label className="field"><span>Due after (days)</span>
            <input type="number" min={0} max={365} placeholder="No due date" value={form.dueInDays ?? ''}
              onChange={(e) => set('dueInDays', e.target.value === '' ? null : Number(e.target.value))} />
          </label>
          <label className="field"><span>Assignee</span>
            <select value={form.assigneeId ?? ''} onChange={(e) => set('assigneeId', e.target.value ? Number(e.target.value) : null)}>
              <option value="">Unassigned</option>
              {members.filter((m) => m.role !== 'VIEWER').map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
            </select>
          </label>
          <label className="field"><span>Type</span>
            <select value={form.type} onChange={(e) => set('type', e.target.value as RecurringInput['type'])}>
              {TASK_TYPES.map((t) => <option key={t} value={t}>{TASK_TYPE_LABEL[t]}</option>)}
            </select>
          </label>
          <label className="field"><span>Priority</span>
            <select value={form.priority} onChange={(e) => set('priority', e.target.value as RecurringInput['priority'])}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
            </select>
          </label>
        </div>
        <label className="field"><span>Description</span>
          <textarea rows={4} maxLength={5000} value={form.description} onChange={(e) => set('description', e.target.value)} />
        </label>
        <label className="field"><span>Checklist (one item per line)</span>
          <textarea rows={3} value={checklist} onChange={(e) => setChecklist(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
