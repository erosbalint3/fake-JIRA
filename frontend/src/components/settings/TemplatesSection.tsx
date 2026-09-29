import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { FileStack, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError, type TemplateInput } from '../../api';
import { useToast } from '../../toast';
import { Modal } from '../Modal';
import { TypeIcon } from '../Badges';
import { PRIORITIES, PRIORITY_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type TaskTemplate } from '../../types';

const EMPTY: TemplateInput = {
  name: '', type: 'TASK', title: '', description: '', priority: 'MEDIUM', labels: [], checklist: [], storyPoints: null,
};

const STARTERS: TemplateInput[] = [
  { ...EMPTY, name: 'Bug report', type: 'BUG', title: 'Bug: ', priority: 'HIGH', labels: ['bug'],
    description: '## Steps to reproduce\n1. \n\n## Expected\n\n## Actual\n\n## Environment\n' },
  { ...EMPTY, name: 'User story', type: 'STORY', title: 'As a … I want … so that …',
    description: '## Acceptance criteria\n- [ ] \n', checklist: ['Design', 'Implement', 'Test', 'Document'] },
];

/** Reusable starting points for new tasks. */
export function TemplatesSection({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const toast = useToast();
  const [templates, setTemplates] = useState<TaskTemplate[] | null>(null);
  const [editing, setEditing] = useState<{ id: number | null; input: TemplateInput } | null>(null);

  const load = useCallback(() => {
    api.templates(projectKey).then(setTemplates).catch(() => setTemplates([]));
  }, [projectKey]);
  useEffect(load, [load]);

  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title"><FileStack size={16} /> Task templates</h2>
        {canEdit && (
          <button className="btn btn-soft btn-sm" onClick={() => setEditing({ id: null, input: EMPTY })}><Plus size={15} /> New template</button>
        )}
      </div>
      <p className="muted small hint">Pick a template in the “Create task” dialog to start with its type, text, labels and checklist.</p>
      {templates && templates.length === 0 && canEdit && (
        <div className="chip-row">
          {STARTERS.map((starter) => (
            <button key={starter.name} className="chip" onClick={async () => {
              try {
                await api.createTemplate(projectKey, starter);
                load();
              } catch (e) {
                toast((e as ApiError).message, 'error');
              }
            }}><Plus size={13} /> Add “{starter.name}”</button>
          ))}
        </div>
      )}
      {templates && templates.length > 0 && (
        <ul className="mini-list">
          {templates.map((t) => (
            <li key={t.id}>
              <TypeIcon type={t.type} />
              <div className="invite-text">
                <strong>{t.name}</strong>
                <span className="muted small">
                  {PRIORITY_LABEL[t.priority]}{t.labels.length ? ` · ${t.labels.join(', ')}` : ''}
                  {t.checklist.length ? ` · ${t.checklist.length} checklist item${t.checklist.length === 1 ? '' : 's'}` : ''}
                </span>
              </div>
              {canEdit && (
                <>
                  <button className="icon-button sm" aria-label={`Edit ${t.name}`} onClick={() => setEditing({ id: t.id, input: t })}>
                    <Pencil size={14} />
                  </button>
                  <button className="icon-button sm" aria-label={`Delete ${t.name}`} onClick={async () => {
                    await api.deleteTemplate(t.id);
                    load();
                  }}><Trash2 size={14} /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {editing && (
        <TemplateModal initial={editing.input} isNew={editing.id === null} onClose={() => setEditing(null)}
          onSave={async (input) => {
            if (editing.id === null) await api.createTemplate(projectKey, input);
            else await api.updateTemplate(editing.id, input);
            setEditing(null);
            toast('Template saved');
            load();
          }} />
      )}
    </section>
  );
}

function TemplateModal({ initial, isNew, onClose, onSave }: {
  initial: TemplateInput;
  isNew: boolean;
  onClose: () => void;
  onSave: (input: TemplateInput) => Promise<void>;
}) {
  const [form, setForm] = useState(initial);
  const [labels, setLabels] = useState(initial.labels.join(', '));
  const [checklist, setChecklist] = useState(initial.checklist.join('\n'));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await onSave({
        ...form,
        labels: labels.split(',').map((l) => l.trim()).filter(Boolean),
        checklist: checklist.split('\n').map((l) => l.trim()).filter(Boolean),
      });
    } catch (e) {
      setError((e as ApiError).message);
      setBusy(false);
    }
  };

  return (
    <Modal title={isNew ? 'New template' : `Edit ${initial.name}`} onClose={onClose} wide footer={<>
      <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
      <button type="submit" form="template-form" className="btn btn-primary" disabled={busy || !form.name.trim()}>Save</button>
    </>}>
      <form id="template-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid">
          <label className="field"><span>Template name</span>
            <input value={form.name} maxLength={60} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
          </label>
          <label className="field"><span>Type</span>
            <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as TemplateInput['type'] })}>
              {TASK_TYPES.map((t) => <option key={t} value={t}>{TASK_TYPE_LABEL[t]}</option>)}
            </select>
          </label>
          <label className="field"><span>Priority</span>
            <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value as TemplateInput['priority'] })}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
            </select>
          </label>
          <label className="field"><span>Story points</span>
            <input type="number" min={0} max={100} value={form.storyPoints ?? ''}
              onChange={(e) => setForm({ ...form, storyPoints: e.target.value === '' ? null : Number(e.target.value) })} />
          </label>
        </div>
        <label className="field"><span>Title starts with</span>
          <input value={form.title} maxLength={120} onChange={(e) => setForm({ ...form, title: e.target.value })} />
        </label>
        <label className="field"><span>Description</span>
          <textarea rows={6} maxLength={5000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </label>
        <label className="field"><span>Labels (comma separated)</span>
          <input value={labels} onChange={(e) => setLabels(e.target.value)} />
        </label>
        <label className="field"><span>Checklist (one item per line)</span>
          <textarea rows={4} value={checklist} onChange={(e) => setChecklist(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
