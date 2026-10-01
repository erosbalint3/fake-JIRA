import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { FileStack, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError, type TemplateInput } from '../../api';
import { useToast } from '../../toast';
import { Modal } from '../Modal';
import { TypeIcon } from '../Badges';
import { PRIORITIES, PRIORITY_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type TaskTemplate } from '../../types';
import { t } from '../../i18n';

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
        <h2 className="panel-title"><FileStack size={16} /> {t('Task templates')}</h2>
        {canEdit && (
          <button className="btn btn-soft btn-sm" onClick={() => setEditing({ id: null, input: EMPTY })}><Plus size={15} /> {t('New template')}</button>
        )}
      </div>
      <p className="muted small hint">{t('Pick a template in the “Create task” dialog to start with its type, text, labels and checklist.')}</p>
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
            }}><Plus size={13} /> {t('Add “{name}”', { name: starter.name })}</button>
          ))}
        </div>
      )}
      {templates && templates.length > 0 && (
        <ul className="mini-list">
          {templates.map((tpl) => (
            <li key={tpl.id}>
              <TypeIcon type={tpl.type} />
              <div className="invite-text">
                <strong>{tpl.name}</strong>
                <span className="muted small">
                  {t(PRIORITY_LABEL[tpl.priority])}{tpl.labels.length ? ` · ${tpl.labels.join(', ')}` : ''}
                  {tpl.checklist.length ? ` · ${tpl.checklist.length === 1 ? t('1 checklist item') : t('{n} checklist items', { n: tpl.checklist.length })}` : ''}
                </span>
              </div>
              {canEdit && (
                <>
                  <button className="icon-button sm" aria-label={t('Edit {name}', { name: tpl.name })} onClick={() => setEditing({ id: tpl.id, input: tpl })}>
                    <Pencil size={14} />
                  </button>
                  <button className="icon-button sm" aria-label={t('Delete {name}', { name: tpl.name })} onClick={async () => {
                    await api.deleteTemplate(tpl.id);
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
            toast(t('Template saved'));
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
    <Modal title={isNew ? t('New template') : t('Edit {name}', { name: initial.name })} onClose={onClose} wide footer={<>
      <button type="button" className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
      <button type="submit" form="template-form" className="btn btn-primary" disabled={busy || !form.name.trim()}>{t('Save')}</button>
    </>}>
      <form id="template-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid">
          <label className="field"><span>{t('Template name')}</span>
            <input value={form.name} maxLength={60} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
          </label>
          <label className="field"><span>{t('Type')}</span>
            <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as TemplateInput['type'] })}>
              {TASK_TYPES.map((type) => <option key={type} value={type}>{t(TASK_TYPE_LABEL[type])}</option>)}
            </select>
          </label>
          <label className="field"><span>{t('Priority')}</span>
            <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value as TemplateInput['priority'] })}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{t(PRIORITY_LABEL[p])}</option>)}
            </select>
          </label>
          <label className="field"><span>{t('Story points')}</span>
            <input type="number" min={0} max={100} value={form.storyPoints ?? ''}
              onChange={(e) => setForm({ ...form, storyPoints: e.target.value === '' ? null : Number(e.target.value) })} />
          </label>
        </div>
        <label className="field"><span>{t('Title starts with')}</span>
          <input value={form.title} maxLength={120} onChange={(e) => setForm({ ...form, title: e.target.value })} />
        </label>
        <label className="field"><span>{t('Description')}</span>
          <textarea rows={6} maxLength={5000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </label>
        <label className="field"><span>{t('Labels (comma separated)')}</span>
          <input value={labels} onChange={(e) => setLabels(e.target.value)} />
        </label>
        <label className="field"><span>{t('Checklist (one item per line)')}</span>
          <textarea rows={4} value={checklist} onChange={(e) => setChecklist(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
