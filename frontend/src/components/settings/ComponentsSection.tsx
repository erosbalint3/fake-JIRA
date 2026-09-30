import { isReadOnlyRole } from '../../types';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Boxes, Pencil, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { ConfirmDialog } from '../Modal';
import type { Member, ProjectComponent } from '../../types';
import { t } from '../../i18n';

/** Areas of the project; new tasks in one go to its lead when nobody is assigned. */
export function ComponentsSection({ projectKey, members, canEdit }: { projectKey: string; members: Member[]; canEdit: boolean }) {
  const toast = useToast();
  const [components, setComponents] = useState<ProjectComponent[]>([]);
  const [editing, setEditing] = useState<ProjectComponent | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [lead, setLead] = useState('');
  const [error, setError] = useState('');
  const [deleting, setDeleting] = useState<ProjectComponent | null>(null);

  const load = useCallback(() => {
    api.components(projectKey).then(setComponents).catch(() => {});
  }, [projectKey]);
  useEffect(load, [load]);

  const reset = () => {
    setEditing(null);
    setName('');
    setDescription('');
    setLead('');
    setError('');
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = { name: name.trim(), description: description.trim(), leadId: lead ? Number(lead) : null };
    try {
      if (editing) await api.updateComponent(editing.id, input);
      else await api.createComponent(projectKey, input);
      toast(editing ? t('Component saved') : t('Component added'));
      reset();
      load();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><Boxes size={16} /> {t('Components')}</h2>
      <p className="muted small hint">{t('Areas like “Payments” or “Mobile app”. New tasks in a component go to its lead unless someone else is assigned. Search with component = payments.')}</p>
      {components.length > 0 && (
        <ul className="field-list">
          {components.map((c) => (
            <li key={c.id}>
              <strong>{c.name}</strong>
              {c.lead && <span className="chip chip-sm">{t('Lead')}: {c.lead.displayName}</span>}
              <span className="muted small">{c.description}</span>
              <span className="spacer" />
              <span className="muted small">{t('{n} tasks', { n: c.taskCount })}</span>
              {canEdit && (
                <>
                  <button className="icon-button" aria-label={t('Edit {name}', { name: c.name })} onClick={() => {
                    setEditing(c);
                    setName(c.name);
                    setDescription(c.description);
                    setLead(c.lead ? String(c.lead.id) : '');
                  }}><Pencil size={15} /></button>
                  <button className="icon-button" aria-label={t('Delete {name}', { name: c.name })} onClick={() => setDeleting(c)}>
                    <Trash2 size={15} /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && (
        <form className="form narrow" onSubmit={submit}>
          {error && <div className="alert">{error}</div>}
          <div className="form-grid two">
            <label className="field">
              <span>{editing ? t('Rename {name}', { name: editing.name }) : t('New component')}</span>
              <input value={name} maxLength={40} placeholder={t('Payments')} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="field">
              <span>{t('Lead')} <span className="muted">{t('(optional)')}</span></span>
              <select value={lead} onChange={(e) => setLead(e.target.value)}>
                <option value="">{t('Nobody')}</option>
                {members.filter((m) => !isReadOnlyRole(m.role)).map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
              </select>
            </label>
          </div>
          <label className="field">
            <span>{t('Description')} <span className="muted">{t('(optional)')}</span></span>
            <input value={description} maxLength={300} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <div className="button-row">
            <button className="btn btn-soft" disabled={!name.trim()}>{editing ? t('Save component') : t('Add component')}</button>
            {editing && <button type="button" className="btn btn-ghost" onClick={reset}>{t('Cancel')}</button>}
          </div>
        </form>
      )}
      {deleting && (
        <ConfirmDialog title={t('Delete {name}?', { name: deleting.name })} confirmLabel={t('Delete')} danger
          message={t('Tasks keep everything else; they just lose this component.')}
          onClose={() => setDeleting(null)} onConfirm={async () => {
            await api.deleteComponent(deleting.id).catch((e: ApiError) => toast(e.message, 'error'));
            setDeleting(null);
            load();
          }} />
      )}
    </section>
  );
}
