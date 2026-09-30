import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ArrowDown, ArrowUp, ClipboardCopy, ExternalLink, LifeBuoy, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { Modal } from '../Modal';
import {
  PRIORITIES, PRIORITY_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type FormField, type FormFieldKind, type Priority,
  type RequestTypeDef, type ServiceDeskSettings, type TaskType,
} from '../../types';
import { t } from '../../i18n';

const KIND_LABEL: Record<FormFieldKind, string> = {
  text: 'Short text', textarea: 'Long text', select: 'Choice', number: 'Number', date: 'Date', checkbox: 'Checkbox', url: 'Web address',
};

/** The public portal, its request types and forms, the embeddable widget, and the public roadmap/changelog. */
export function ServiceDeskSection({ projectKey, isOwner }: { projectKey: string; isOwner: boolean }) {
  const toast = useToast();
  const [settings, setSettings] = useState<ServiceDeskSettings | null>(null);
  const [intro, setIntro] = useState('');
  const [editing, setEditing] = useState<RequestTypeDef | 'new' | null>(null);

  const load = useCallback(() => {
    api.serviceDesk(projectKey).then((s) => {
      setSettings(s);
      setIntro(s.intro);
    }).catch(() => {});
  }, [projectKey]);
  useEffect(load, [load]);

  const save = async (change: Partial<{ portalEnabled: boolean; intro: string; roadmapPublic: boolean; changelogPublic: boolean }>) => {
    if (!settings) return;
    try {
      const next = await api.saveServiceDesk(projectKey, {
        portalEnabled: settings.portalEnabled, intro, roadmapPublic: settings.roadmapPublic,
        changelogPublic: settings.changelogPublic, ...change,
      });
      setSettings(next);
      setIntro(next.intro);
      toast(t('Service desk saved'));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast(t('Copied'));
    } catch {
      toast(t('Could not copy; select the text instead.'), 'error');
    }
  };

  const move = async (index: number, by: number) => {
    if (!settings) return;
    const ids = settings.requestTypes.map((r) => r.id);
    const [id] = ids.splice(index, 1);
    ids.splice(index + by, 0, id);
    try {
      const types = await api.reorderRequestTypes(projectKey, ids);
      setSettings({ ...settings, requestTypes: types });
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!settings) return null;
  return (
    <section className="panel" id="service-desk">
      <h2 className="panel-title"><LifeBuoy size={16} aria-hidden /> {t('Service desk')}</h2>
      <p className="muted small hint">
        {t('Let people outside the team send requests without an account, follow them and talk to you. Requests become tasks labelled “portal”.')}
      </p>
      <label className="toggle">
        <input type="checkbox" checked={settings.portalEnabled} disabled={!isOwner} onChange={(e) => save({ portalEnabled: e.target.checked })} />
        {t('Public request portal and feedback widget')}
      </label>
      {settings.portalEnabled && (
        <>
          <div className="public-links">
            <a href={settings.portalUrl} target="_blank" rel="noreferrer"><ExternalLink size={14} aria-hidden /> {settings.portalUrl}</a>
            <button className="btn btn-ghost btn-sm" onClick={() => copy(settings.portalUrl)}><ClipboardCopy size={14} /> {t('Copy link')}</button>
          </div>
          <form className="form" onSubmit={(e) => {
            e.preventDefault();
            save({ intro });
          }}>
            <label className="field">
              <span>{t('Welcome text (Markdown)')}</span>
              <textarea rows={3} maxLength={4000} value={intro} disabled={!isOwner} onChange={(e) => setIntro(e.target.value)} />
            </label>
            {isOwner && <div><button className="btn btn-soft" disabled={intro === settings.intro}>{t('Save text')}</button></div>}
          </form>

          <h3 className="subheading">{t('Request types')}</h3>
          <ul className="mini-list">
            {settings.requestTypes.map((type, index) => (
              <li key={type.id}>
                <span className="grow"><strong>{type.name}</strong>
                  <span className="muted small"> · {t(TASK_TYPE_LABEL[type.taskType])} · {t(PRIORITY_LABEL[type.priority])}
                    · {t('{n} extra fields', { n: type.fields.length })}</span></span>
                {isOwner && (
                  <>
                    <button className="icon-button" disabled={index === 0} onClick={() => move(index, -1)} aria-label={t('Move {name} up', { name: type.name })}><ArrowUp size={15} /></button>
                    <button className="icon-button" disabled={index === settings.requestTypes.length - 1} onClick={() => move(index, 1)}
                      aria-label={t('Move {name} down', { name: type.name })}><ArrowDown size={15} /></button>
                    <button className="icon-button" onClick={() => setEditing(type)} aria-label={t('Edit {name}', { name: type.name })}><Pencil size={15} /></button>
                    <button className="icon-button" aria-label={t('Delete {name}', { name: type.name })} onClick={async () => {
                      try {
                        await api.deleteRequestType(type.id);
                        load();
                      } catch (e) {
                        toast((e as ApiError).message, 'error');
                      }
                    }}><Trash2 size={15} /></button>
                  </>
                )}
              </li>
            ))}
          </ul>
          {isOwner && <button className="btn btn-soft" onClick={() => setEditing('new')}><Plus size={16} /> {t('Add a request type')}</button>}

          <h3 className="subheading">{t('Feedback widget')}</h3>
          <p className="muted small">{t('Paste this into any website to add a “Feedback” button that opens your request form:')}</p>
          <div className="snippet">
            <code>{settings.widgetSnippet}</code>
            <button className="btn btn-ghost btn-sm" onClick={() => copy(settings.widgetSnippet)}><ClipboardCopy size={14} /> {t('Copy')}</button>
          </div>
        </>
      )}

      <h3 className="subheading">{t('Public pages')}</h3>
      <label className="toggle">
        <input type="checkbox" checked={settings.roadmapPublic} disabled={!isOwner} onChange={(e) => save({ roadmapPublic: e.target.checked })} />
        {t('Public roadmap (epics: now, next, later)')}
      </label>
      {settings.roadmapPublic && <p className="small"><a href={settings.roadmapUrl} target="_blank" rel="noreferrer">{settings.roadmapUrl}</a></p>}
      <label className="toggle">
        <input type="checkbox" checked={settings.changelogPublic} disabled={!isOwner} onChange={(e) => save({ changelogPublic: e.target.checked })} />
        {t('Public changelog (released versions and what shipped)')}
      </label>
      {settings.changelogPublic && <p className="small"><a href={settings.changelogUrl} target="_blank" rel="noreferrer">{settings.changelogUrl}</a></p>}

      {editing && <RequestTypeModal projectKey={projectKey} existing={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={() => {
          setEditing(null);
          load();
        }} />}
    </section>
  );
}

function RequestTypeModal({ projectKey, existing, onClose, onSaved }: {
  projectKey: string; existing: RequestTypeDef | null; onClose: () => void; onSaved: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [taskType, setTaskType] = useState<TaskType>(existing?.taskType ?? 'TASK');
  const [priority, setPriority] = useState<Priority>(existing?.priority ?? 'MEDIUM');
  const [fields, setFields] = useState<FormField[]>(existing?.fields ?? []);
  const [error, setError] = useState('');

  const update = (index: number, change: Partial<FormField>) => setFields(fields.map((f, i) => (i === index ? { ...f, ...change } : f)));
  const move = (index: number, by: number) => {
    const next = [...fields];
    const [field] = next.splice(index, 1);
    next.splice(index + by, 0, field);
    setFields(next);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = { name: name.trim(), description: description.trim(), taskType, priority, fields };
    try {
      if (existing) await api.updateRequestType(existing.id, input);
      else await api.createRequestType(projectKey, input);
      onSaved();
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors?.fields ?? err.fieldErrors?.name ?? err.message);
    }
  };

  return (
    <Modal title={existing ? t('Edit request type') : t('New request type')} onClose={onClose} wide footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" type="submit" form="request-type-form" disabled={!name.trim()}>{t('Save')}</button>
      </>
    }>
      <form id="request-type-form" className="form" onSubmit={submit}>
        {error && <div className="alert" role="alert">{error}</div>}
        <div className="form-grid two">
          <label className="field">
            <span>{t('Name')}</span>
            <input value={name} maxLength={80} placeholder={t('Report a bug')} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Short description')}</span>
            <input value={description} maxLength={300} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Creates a')}</span>
            <select value={taskType} onChange={(e) => setTaskType(e.target.value as TaskType)}>
              {TASK_TYPES.map((type) => <option key={type} value={type}>{t(TASK_TYPE_LABEL[type])}</option>)}
            </select>
          </label>
          <label className="field">
            <span>{t('With priority')}</span>
            <select value={priority} onChange={(e) => setPriority(e.target.value as Priority)}>
              {PRIORITIES.map((p) => <option key={p} value={p}>{t(PRIORITY_LABEL[p])}</option>)}
            </select>
          </label>
        </div>

        <h3 className="subheading">{t('Form fields')}</h3>
        <p className="muted small">{t('Name, email, summary and details are always asked. Add anything else you need:')}</p>
        <ol className="form-builder">
          {fields.map((field, index) => (
            <li key={index}>
              <div className="form-builder-row">
                <input value={field.label} maxLength={80} placeholder={t('Question')} aria-label={t('Field {n} label', { n: index + 1 })}
                  onChange={(e) => update(index, { label: e.target.value })} />
                <select value={field.kind} aria-label={t('Field {n} kind', { n: index + 1 })}
                  onChange={(e) => update(index, { kind: e.target.value as FormFieldKind, options: e.target.value === 'select' ? field.options ?? [] : undefined })}>
                  {(Object.keys(KIND_LABEL) as FormFieldKind[]).map((k) => <option key={k} value={k}>{t(KIND_LABEL[k])}</option>)}
                </select>
                <label className="toggle small">
                  <input type="checkbox" checked={field.required} onChange={(e) => update(index, { required: e.target.checked })} /> {t('Required')}
                </label>
                <button type="button" className="icon-button" disabled={index === 0} onClick={() => move(index, -1)} aria-label={t('Move field {n} up', { n: index + 1 })}><ArrowUp size={15} /></button>
                <button type="button" className="icon-button" disabled={index === fields.length - 1} onClick={() => move(index, 1)} aria-label={t('Move field {n} down', { n: index + 1 })}><ArrowDown size={15} /></button>
                <button type="button" className="icon-button" onClick={() => setFields(fields.filter((_, i) => i !== index))} aria-label={t('Remove field {n}', { n: index + 1 })}><Trash2 size={15} /></button>
              </div>
              {field.kind === 'select' && (
                <input className="options-input" value={(field.options ?? []).join(', ')} placeholder={t('Options, separated by commas')}
                  aria-label={t('Field {n} options', { n: index + 1 })}
                  onChange={(e) => update(index, { options: e.target.value.split(',').map((o) => o.trim()).filter(Boolean) })} />
              )}
              <input className="options-input" value={field.help ?? ''} maxLength={200} placeholder={t('Help text (optional)')}
                aria-label={t('Field {n} help text', { n: index + 1 })} onChange={(e) => update(index, { help: e.target.value })} />
            </li>
          ))}
        </ol>
        {fields.length < 20 && (
          <button type="button" className="btn btn-ghost" onClick={() => setFields([...fields, { id: '', label: '', kind: 'text', required: false }])}>
            <Plus size={16} /> {t('Add a field')}
          </button>
        )}
      </form>
    </Modal>
  );
}
