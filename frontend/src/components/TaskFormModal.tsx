import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useProjects } from '../projects';
import {
  PRIORITIES, PRIORITY_LABEL, type CreateTaskInput, type Epic, type Priority, type Sprint, type TaskInput, type User,
} from '../types';
import { Modal } from './Modal';
import { PriorityBadge } from './Badges';
import { MarkdownEditor } from './MarkdownEditor';
import { LabelInput } from './LabelInput';

type Mode =
  | {
    kind: 'create';
    projectKey?: string;
    sprintId?: number | null;
    /** Creates a subtask of this task. */
    parent?: { id: number; key: string };
    onSubmit: (input: CreateTaskInput) => Promise<void>;
  }
  | { kind: 'edit'; projectKey: string; initial: TaskInput; onSubmit: (input: TaskInput) => Promise<void> };

interface Props {
  title: string;
  submitLabel: string;
  mode: Mode;
  onClose: () => void;
}

export function TaskFormModal({ title, submitLabel, mode, onClose }: Props) {
  const { projects, lastKey } = useProjects();
  const initial = mode.kind === 'edit' ? mode.initial : null;
  const [projectKey, setProjectKey] = useState(mode.projectKey ?? lastKey() ?? '');
  const [form, setForm] = useState<TaskInput>(initial ?? {
    title: '', description: '', priority: 'MEDIUM', dueDate: null, labels: [], storyPoints: null, epicId: null,
  });
  const [epics, setEpics] = useState<Epic[]>([]);
  const [assigneeId, setAssigneeId] = useState<number | null>(null);
  const [sprintId, setSprintId] = useState<number | null>(mode.kind === 'create' ? mode.sprintId ?? null : null);
  const [members, setMembers] = useState<User[]>([]);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [labelSuggestions, setLabelSuggestions] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!projectKey) return;
    const project = projects?.find((p) => p.key === projectKey);
    setMembers((project?.members ?? []).filter((m) => m.role !== 'VIEWER'));
    api.epics(projectKey).then(setEpics).catch(() => setEpics([]));
    api.labels(projectKey).then(setLabelSuggestions).catch(() => setLabelSuggestions([]));
    if (mode.kind === 'create') {
      api.sprints(projectKey)
        .then((list) => setSprints(list.filter((s) => s.state !== 'COMPLETED')))
        .catch(() => setSprints([]));
    }
  }, [projectKey, projects, mode.kind]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.title.trim()) {
      setErrors({ title: 'Title is required' });
      return;
    }
    if (mode.kind === 'create' && !projectKey) {
      setErrors({ projectKey: 'Choose a project' });
      return;
    }
    setBusy(true);
    setErrors({});
    setFormError('');
    const input = { ...form, title: form.title.trim(), dueDate: form.dueDate || null };
    try {
      if (mode.kind === 'create') {
        await mode.onSubmit({ ...input, projectKey, assigneeId, sprintId, parentId: mode.parent?.id ?? null });
      } else {
        await mode.onSubmit(input);
      }
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors);
        setFormError(error.message);
      }
      setBusy(false);
    }
  };

  return (
    <Modal
      title={title}
      onClose={onClose}
      wide
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" form="task-form" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : submitLabel}
          </button>
        </>
      }
    >
      <form id="task-form" className="form" onSubmit={submit} noValidate>
        {formError && !Object.keys(errors).length && <div className="alert">{formError}</div>}
        {mode.kind === 'create' && mode.parent && (
          <p className="muted form-note">Subtask of <b>{mode.parent.key}</b>. It joins the parent's sprint and epic.</p>
        )}
        {mode.kind === 'create' && !mode.projectKey && (
          <label className="field">
            <span>Project</span>
            <select value={projectKey} onChange={(e) => {
              setProjectKey(e.target.value);
              setAssigneeId(null);
              setSprintId(null);
            }} aria-invalid={!!errors.projectKey}>
              <option value="" disabled>Choose a project</option>
              {projects?.map((p) => <option key={p.key} value={p.key}>{p.name} ({p.key})</option>)}
            </select>
            {errors.projectKey && <small className="field-error">{errors.projectKey}</small>}
          </label>
        )}
        <label className="field">
          <span>Title</span>
          <input
            value={form.title}
            maxLength={120}
            placeholder="What needs to be done?"
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            aria-invalid={!!errors.title}
          />
          {errors.title && <small className="field-error">{errors.title}</small>}
        </label>
        <div className="field">
          <span>Description</span>
          <MarkdownEditor
            value={form.description}
            onChange={(description) => setForm({ ...form, description })}
            members={members}
            maxLength={5000}
            rows={6}
            label="Description"
            placeholder="Add context, acceptance criteria, links… Markdown and @mentions work."
            invalid={!!errors.description}
          />
          {errors.description && <small className="field-error">{errors.description}</small>}
        </div>
        <fieldset className="field">
          <span>Priority</span>
          <div className="segmented">
            {PRIORITIES.map((priority: Priority) => (
              <label key={priority} className={form.priority === priority ? 'active' : ''}>
                <input
                  type="radio"
                  name="priority"
                  value={priority}
                  checked={form.priority === priority}
                  onChange={() => setForm({ ...form, priority })}
                />
                <PriorityBadge priority={priority} compact />
                {PRIORITY_LABEL[priority]}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="form-grid">
          {mode.kind === 'create' && (
            <label className="field">
              <span>Assignee</span>
              <select value={assigneeId ?? ''} onChange={(e) => setAssigneeId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">Unassigned</option>
                {members.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
              </select>
            </label>
          )}
          <label className="field">
            <span>Due date</span>
            <input type="date" value={form.dueDate ?? ''} onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />
          </label>
          <label className="field">
            <span>Story points</span>
            <input type="number" min={0} max={100} inputMode="numeric" placeholder="–"
              value={form.storyPoints ?? ''}
              onChange={(e) => setForm({ ...form, storyPoints: e.target.value === '' ? null : Math.max(0, Math.min(100, Number(e.target.value))) })} />
          </label>
          <label className="field">
            <span>Epic</span>
            <select value={form.epicId ?? ''} onChange={(e) => setForm({ ...form, epicId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{mode.kind === 'create' && mode.parent ? 'Same as parent' : 'No epic'}</option>
              {epics.map((epic) => <option key={epic.id} value={epic.id}>{epic.name}</option>)}
            </select>
          </label>
          {mode.kind === 'create' && !mode.parent && (
            <label className="field">
              <span>Sprint</span>
              <select value={sprintId ?? ''} onChange={(e) => setSprintId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">Backlog</option>
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
              </select>
            </label>
          )}
        </div>
        <div className="field">
          <span>Labels</span>
          <LabelInput value={form.labels} onChange={(labels) => setForm({ ...form, labels })} suggestions={labelSuggestions} />
          {errors.labels && <small className="field-error">{errors.labels}</small>}
        </div>
      </form>
    </Modal>
  );
}
