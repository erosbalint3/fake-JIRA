import { t } from '../../i18n';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ListPlus, Pencil, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { ConfirmDialog } from '../Modal';
import type { CustomFieldDef, CustomFieldType } from '../../types';

const TYPES: { value: CustomFieldType; label: string }[] = [
  { value: 'TEXT', label: 'Text' },
  { value: 'NUMBER', label: 'Number' },
  { value: 'SELECT', label: 'Choice list' },
  { value: 'DATE', label: 'Date' },
  { value: 'CHECKBOX', label: 'Yes / no' },
  { value: 'URL', label: 'Link' },
];

/** Extra task fields for this project, e.g. Customer or Severity. */
export function CustomFieldsSection({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const toast = useToast();
  const [fields, setFields] = useState<CustomFieldDef[]>([]);
  const [editing, setEditing] = useState<CustomFieldDef | null>(null);
  const [name, setName] = useState('');
  const [type, setType] = useState<CustomFieldType>('TEXT');
  const [options, setOptions] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [deleting, setDeleting] = useState<CustomFieldDef | null>(null);

  const load = useCallback(() => {
    api.customFields(projectKey).then(setFields).catch(() => setFields([]));
  }, [projectKey]);
  useEffect(load, [load]);

  const reset = () => {
    setEditing(null);
    setName('');
    setType('TEXT');
    setOptions('');
    setErrors({});
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const list = options.split('\n').map((o) => o.trim()).filter(Boolean);
    try {
      if (editing) await api.updateCustomField(editing.id, name.trim(), type, list);
      else await api.createCustomField(projectKey, name.trim(), type, list);
      toast(editing ? t('Field saved') : t('Field “{name}” added', { name: name.trim() }));
      reset();
      load();
    } catch (e) {
      const err = e as ApiError;
      setErrors(Object.keys(err.fieldErrors ?? {}).length ? err.fieldErrors : { form: err.message });
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><ListPlus size={16} /> {t('Custom fields')}</h2>
      <p className="muted small hint">{t('Extra fields on every task of this project. Search them like built-in ones, e.g.')}
         <code>severity = major</code> {t('or')} <code>"customer name" ~ acme</code>.</p>
      {fields.length > 0 && (
        <ul className="field-list">
          {fields.map((f) => (
            <li key={f.id}>
              <strong>{f.name}</strong>
              <span className="chip">{t(TYPES.find((x) => x.value === f.type)?.label ?? "")}</span>
              {f.options.length > 0 && <span className="muted small">{f.options.join(' · ')}</span>}
              <span className="spacer" />
              {canEdit && (
                <>
                  <button className="icon-button" aria-label={t('Edit {name}', { name: f.name })} onClick={() => {
                    setEditing(f);
                    setName(f.name);
                    setType(f.type);
                    setOptions(f.options.join('\n'));
                  }}><Pencil size={15} /></button>
                  <button className="icon-button" aria-label={t('Delete {name}', { name: f.name })} onClick={() => setDeleting(f)}><Trash2 size={15} /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && (
        <form className="form narrow" onSubmit={submit}>
          {errors.form && <div className="alert">{errors.form}</div>}
          <div className="form-grid two">
            <label className="field">
              <span>{editing ? t('Rename {name}', { name: editing.name }) : t('New field')}</span>
              <input value={name} maxLength={40} placeholder={t('Customer')} aria-invalid={!!errors.name} onChange={(e) => setName(e.target.value)} />
              {errors.name && <span className="field-error">{errors.name}</span>}
            </label>
            <label className="field">
              <span>{t('Type')}</span>
              <select value={type} disabled={!!editing} onChange={(e) => setType(e.target.value as CustomFieldType)}>
                {TYPES.map((x) => <option key={x.value} value={x.value}>{t(x.label)}</option>)}
              </select>
            </label>
          </div>
          {type === 'SELECT' && (
            <label className="field">
              <span>{t('Choices')} <span className="muted">{t('(one per line)')}</span></span>
              <textarea rows={3} value={options} placeholder={'Blocker\nMajor\nMinor'} onChange={(e) => setOptions(e.target.value)} />
              {errors.options && <span className="field-error">{errors.options}</span>}
            </label>
          )}
          <div className="button-row">
            <button className="btn btn-soft" disabled={!name.trim()}>{editing ? t('Save field') : t('Add field')}</button>
            {editing && <button type="button" className="btn btn-ghost" onClick={reset}>{t('Cancel')}</button>}
          </div>
        </form>
      )}
      {deleting && (
        <ConfirmDialog title={t('Delete {name}?', { name: deleting.name })} message={t('Its values on all tasks are deleted too.')} confirmLabel={t('Delete field')} danger
          onClose={() => setDeleting(null)} onConfirm={async () => {
            const f = deleting;
            setDeleting(null);
            await api.deleteCustomField(f.id).catch(() => {});
            toast(`${f.name} deleted`);
            load();
          }} />
      )}
    </section>
  );
}
