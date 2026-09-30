import { isReadOnlyRole } from '../types';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useCollaborativeText } from '../collab';
import { api, ApiError } from '../api';
import { useProjects } from '../projects';
import {
  PRIORITIES, PRIORITY_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type CreateTaskInput, type TaskTemplate, type Epic, type Priority, type Sprint, type TaskInput, type User,
  type ProjectComponent,
  type SimilarTask,
} from '../types';
import { Modal } from './Modal';
import { PriorityBadge, TypeIcon } from './Badges';
import { draftStore } from '../drafts';
import { MarkdownEditor } from './MarkdownEditor';
import { LabelInput } from './LabelInput';
import { t } from '../i18n';

type Mode =
  | {
    kind: 'create';
    projectKey?: string;
    sprintId?: number | null;
    /** Creates a subtask of this task. */
    parent?: { id: number; key: string };
    onSubmit: (input: CreateTaskInput) => Promise<void>;
  }
  | { kind: 'edit'; projectKey: string; initial: TaskInput; onSubmit: (input: TaskInput) => Promise<void>;
      /** Turns on live co-editing of the description with others editing the same task. */
      taskId?: number; coEditors?: string[] };

interface Props {
  title: string;
  submitLabel: string;
  mode: Mode;
  onClose: () => void;
  /** Lets images be pasted into the description (editing an existing task). */
  uploadImage?: (file: File) => Promise<string>;
}

export function TaskFormModal({ title, submitLabel, mode, onClose, uploadImage }: Props) {
  const descriptionRef = useRef<HTMLTextAreaElement | null>(null);
  const { projects, lastKey } = useProjects();
  const initial = mode.kind === 'edit' ? mode.initial : null;
  const [projectKey, setProjectKey] = useState(mode.projectKey ?? lastKey() ?? '');
  const empty: TaskInput = {
    title: '', description: '', priority: 'MEDIUM', dueDate: null, labels: [], storyPoints: null, epicId: null, type: 'TASK',
  };
  // New top-level tasks keep a draft per project, so a refresh or accidental close loses nothing.
  const draftKey = mode.kind === 'create' && !mode.parent ? `new-task:${projectKey || 'any'}` : null;
  const [form, setForm] = useState<TaskInput>(() => initial ?? (draftKey ? draftStore.get<TaskInput>(draftKey) : null) ?? empty);
  const [restored, setRestored] = useState(() => !initial && !!draftKey && !!draftStore.get<TaskInput>(draftKey));
  const [templates, setTemplates] = useState<TaskTemplate[]>([]);
  const [checklist, setChecklist] = useState<string[]>([]);
  const [epics, setEpics] = useState<Epic[]>([]);
  const [assigneeId, setAssigneeId] = useState<number | null>(null);
  const [components, setComponents] = useState<ProjectComponent[]>([]);
  const [componentId, setComponentId] = useState<number | null>(null);
  const [sprintId, setSprintId] = useState<number | null>(mode.kind === 'create' ? mode.sprintId ?? null : null);
  const [members, setMembers] = useState<User[]>([]);
  const [sprints, setSprints] = useState<Sprint[]>([]);
  const [labelSuggestions, setLabelSuggestions] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);
  const [duplicates, setDuplicates] = useState<SimilarTask[]>([]);
  const collab = useCollaborativeText(mode.kind === 'edit' ? mode.taskId ?? null : null, form.description,
    (description) => setForm((current) => ({ ...current, description })), descriptionRef);

  // While creating, point out existing tasks that look the same.
  useEffect(() => {
    const text = form.title.trim();
    if (mode.kind !== 'create' || !projectKey || text.length < 8) {
      setDuplicates([]);
      return;
    }
    const timer = window.setTimeout(() => {
      api.similarTasks(projectKey, text).then(setDuplicates).catch(() => setDuplicates([]));
    }, 450);
    return () => window.clearTimeout(timer);
  }, [form.title, projectKey, mode.kind]);

  useEffect(() => {
    if (!projectKey) return;
    const project = projects?.find((p) => p.key === projectKey);
    setMembers((project?.members ?? []).filter((m) => !isReadOnlyRole(m.role)));
    api.epics(projectKey).then(setEpics).catch(() => setEpics([]));
    api.labels(projectKey).then(setLabelSuggestions).catch(() => setLabelSuggestions([]));
    if (mode.kind === 'create') {
      api.templates(projectKey).then(setTemplates).catch(() => setTemplates([]));
      api.components(projectKey).then(setComponents).catch(() => setComponents([]));
      api.sprints(projectKey)
        .then((list) => setSprints(list.filter((s) => s.state !== 'COMPLETED')))
        .catch(() => setSprints([]));
    }
  }, [projectKey, projects, mode.kind]);

  useEffect(() => {
    if (!draftKey) return;
    const timer = window.setTimeout(() => {
      if (form.title.trim() || form.description.trim()) draftStore.set(draftKey, form);
    }, 400);
    return () => window.clearTimeout(timer);
  }, [form, draftKey]);

  const applyTemplate = (id: string) => {
    const template = templates.find((t) => String(t.id) === id);
    if (!template) return;
    setForm({
      ...form, type: template.type, priority: template.priority, storyPoints: template.storyPoints,
      title: form.title.trim() ? form.title : template.title,
      description: form.description.trim() ? form.description : template.description,
      labels: [...new Set([...form.labels, ...template.labels])],
    });
    setChecklist(template.checklist);
  };

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
        await mode.onSubmit({ ...input, projectKey, assigneeId, sprintId, parentId: mode.parent?.id ?? null, checklist,
          componentIds: componentId ? [componentId] : [] });
        if (draftKey) draftStore.clear(draftKey);
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
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
          <button type="submit" form="task-form" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : submitLabel}
          </button>
        </>
      }
    >
      <form id="task-form" className="form" onSubmit={submit} noValidate>
        {formError && !Object.keys(errors).length && <div className="alert">{formError}</div>}
        {restored && (
          <p className="notice small">{t("Restored your unsaved task.")} <button type="button" className="link" onClick={() => {
            if (draftKey) draftStore.clear(draftKey);
            setForm(empty);
            setRestored(false);
          }}>{t("Discard it")}</button></p>
        )}
        {mode.kind === 'create' && templates.length > 0 && (
          <label className="field">
            <span>{t("Template")}</span>
            <select defaultValue="" onChange={(e) => applyTemplate(e.target.value)}>
              <option value="">{t("Blank task")}</option>
              {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </label>
        )}
        {mode.kind === 'create' && mode.parent && (
          <p className="muted form-note">{t("Subtask of")} <b>{mode.parent.key}</b>. It joins the parent's sprint and epic.</p>
        )}
        {mode.kind === 'create' && !mode.projectKey && (
          <label className="field">
            <span>{t("Project")}</span>
            <select value={projectKey} onChange={(e) => {
              setProjectKey(e.target.value);
              setAssigneeId(null);
              setSprintId(null);
            }} aria-invalid={!!errors.projectKey}>
              <option value="" disabled>{t("Choose a project")}</option>
              {projects?.map((p) => <option key={p.key} value={p.key}>{p.name} ({p.key})</option>)}
            </select>
            {errors.projectKey && <small className="field-error">{errors.projectKey}</small>}
          </label>
        )}
        <label className="field">
          <span>{t("Title")}</span>
          <input
            value={form.title}
            maxLength={120}
            placeholder={t("What needs to be done?")}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            aria-invalid={!!errors.title}
          />
          {errors.title && <small className="field-error">{errors.title}</small>}
          {duplicates.length > 0 && (
            <div className="duplicate-hint" role="status">
              <span className="small">{t('Similar tasks already exist:')}</span>
              <ul>
                {duplicates.slice(0, 3).map((d) => (
                  <li key={d.task.id} className="small">
                    <a href={`/tasks/${d.task.id}`} target="_blank" rel="noreferrer">{d.task.key}</a> {d.task.title}
                    {d.done && <span className="muted"> · {t('done')}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </label>
        <div className="field">
          <span className="field-label-row">{t("Description")}
            {collab === 'live' && (
              <span className="live-chip" title={t("Changes by others appear as they type")}>
                <span className="live-dot on" /> {mode.kind === 'edit' && mode.coEditors?.length
                  ? t('Editing live with {names}', { names: mode.coEditors.join(', ') }) : t('Live co-editing')}
              </span>
            )}
            {collab === 'offline' && <span className="muted small">{t('Live co-editing unavailable — your text saves as usual')}</span>}
          </span>
          <MarkdownEditor
            inputRef={descriptionRef}
            value={form.description}
            onChange={(description) => setForm((current) => ({ ...current, description }))}
            members={members}
            maxLength={5000}
            rows={6}
            label={t("Description")}
            onUploadImage={uploadImage}
            placeholder={t("Add context, acceptance criteria, links… Markdown and @mentions work.")}
            invalid={!!errors.description}
          />
          {errors.description && <small className="field-error">{errors.description}</small>}
        </div>
        <fieldset className="field">
          <span>{t("Type")}</span>
          <div className="segmented types">
            {TASK_TYPES.map((type) => (
              <label key={type} className={form.type === type ? 'active' : ''}>
                <input type="radio" name="type" value={type} checked={form.type === type}
                  onChange={() => setForm({ ...form, type })} />
                <TypeIcon type={type} /> {TASK_TYPE_LABEL[type]}
              </label>
            ))}
          </div>
        </fieldset>
        <fieldset className="field">
          <span>{t("Priority")}</span>
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
              <span>{t("Assignee")}</span>
              <select value={assigneeId ?? ''} onChange={(e) => setAssigneeId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">{t("Unassigned")}</option>
                {members.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
              </select>
            </label>
          )}
          {mode.kind === 'create' && components.length > 0 && (
            <label className="field">
              <span>{t("Component")}</span>
              <select value={componentId ?? ''} onChange={(e) => setComponentId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">{t("None")}</option>
                {components.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}{c.lead && !assigneeId ? ` → ${c.lead.displayName}` : ''}</option>
                ))}
              </select>
            </label>
          )}
          <label className="field">
            <span>{t("Due date")}</span>
            <input type="date" value={form.dueDate ?? ''} onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />
          </label>
          <label className="field">
            <span>{t("Story points")}</span>
            <input type="number" min={0} max={100} inputMode="numeric" placeholder="–"
              value={form.storyPoints ?? ''}
              onChange={(e) => setForm({ ...form, storyPoints: e.target.value === '' ? null : Math.max(0, Math.min(100, Number(e.target.value))) })} />
          </label>
          <label className="field">
            <span>{t("Epic")}</span>
            <select value={form.epicId ?? ''} onChange={(e) => setForm({ ...form, epicId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{mode.kind === 'create' && mode.parent ? 'Same as parent' : 'No epic'}</option>
              {epics.map((epic) => <option key={epic.id} value={epic.id}>{epic.name}</option>)}
            </select>
          </label>
          {mode.kind === 'create' && !mode.parent && (
            <label className="field">
              <span>{t("Sprint")}</span>
              <select value={sprintId ?? ''} onChange={(e) => setSprintId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">{t("Backlog")}</option>
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}{s.state === 'ACTIVE' ? ' (active)' : ''}</option>)}
              </select>
            </label>
          )}
        </div>
        {checklist.length > 0 && (
          <div className="field">
            <span>{t("Checklist from template")}</span>
            <ul className="template-checklist">
              {checklist.map((item, i) => (
                <li key={`${item}-${i}`}>{item}
                  <button type="button" className="icon-button sm" aria-label={`Remove ${item}`}
                    onClick={() => setChecklist(checklist.filter((_, j) => j !== i))}>×</button>
                </li>
              ))}
            </ul>
          </div>
        )}
        <div className="field">
          <span>{t("Labels")}</span>
          <LabelInput value={form.labels} onChange={(labels) => setForm({ ...form, labels })} suggestions={labelSuggestions} />
          {errors.labels && <small className="field-error">{errors.labels}</small>}
        </div>
      </form>
    </Modal>
  );
}
