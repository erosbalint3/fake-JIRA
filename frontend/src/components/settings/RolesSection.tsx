import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Pencil, Trash2, UserCog } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { ConfirmDialog } from '../Modal';
import type { CustomRole, Member, Permission, RolesCatalog } from '../../types';
import { isReadOnlyRole } from '../../types';
import { t } from '../../i18n';

/** Custom roles: named permission sets the owner gives to members (e.g. a contractor who may comment and log time). */
export function RolesSection({ projectKey, members, isOwner }: { projectKey: string; members: Member[]; isOwner: boolean }) {
  const toast = useToast();
  const [catalog, setCatalog] = useState<RolesCatalog | null>(null);
  const [editing, setEditing] = useState<CustomRole | 'new' | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [error, setError] = useState('');
  const [deleting, setDeleting] = useState<CustomRole | null>(null);

  const load = useCallback(() => {
    api.projectRoles(projectKey).then(setCatalog).catch(() => {});
  }, [projectKey]);
  useEffect(load, [load]);

  if (!catalog || (!isOwner && catalog.roles.length === 0)) return null;

  const open = (role: CustomRole | 'new') => {
    setEditing(role);
    setName(role === 'new' ? '' : role.name);
    setDescription(role === 'new' ? '' : role.description);
    setPermissions(role === 'new' ? ['COMMENT'] : role.permissions);
    setError('');
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = { name: name.trim(), description: description.trim(), permissions };
    try {
      if (editing === 'new') await api.createRole(projectKey, input);
      else if (editing) await api.updateRole(projectKey, editing.id, input);
      toast(t('Role saved'));
      setEditing(null);
      load();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  const roleOf = (memberId: number) => catalog.roles.find((r) => r.memberIds.includes(memberId));
  const assignable = members.filter((m) => m.role !== 'OWNER' && !isReadOnlyRole(m.role));

  return (
    <section className="panel">
      <h2 className="panel-title"><UserCog size={16} /> {t('Roles')}</h2>
      <p className="muted small hint">{t('Members have full access unless you give them a role. A role allows only what it lists.')}</p>
      {catalog.roles.length > 0 && (
        <ul className="field-list">
          {catalog.roles.map((r) => (
            <li key={r.id}>
              <strong>{r.name}</strong>
              <span className="muted small">{r.permissions.map((p) => t(catalog.permissions.find((x) => x.id === p)?.label ?? p)).join(', ') || t('Read only')}</span>
              <span className="spacer" />
              <span className="muted small">{t('{n} members', { n: r.memberIds.length })}</span>
              {isOwner && (
                <>
                  <button className="icon-button sm" aria-label={t('Edit {name}', { name: r.name })} onClick={() => open(r)}><Pencil size={14} /></button>
                  <button className="icon-button sm" aria-label={t('Delete {name}', { name: r.name })} onClick={() => setDeleting(r)}><Trash2 size={14} /></button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {isOwner && !editing && <button className="btn btn-soft sm" onClick={() => open('new')}>{t('New role')}</button>}
      {editing && (
        <form className="form role-form" onSubmit={submit}>
          <label className="field">
            <span>{t('Name')}</span>
            <input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label className="field">
            <span>{t('Description')}</span>
            <input value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <fieldset className="permission-grid">
            <legend>{t('Allowed')}</legend>
            {catalog.permissions.map((p) => (
              <label key={p.id} className="toggle">
                <input type="checkbox" checked={permissions.includes(p.id)}
                  onChange={(e) => setPermissions(e.target.checked ? [...permissions, p.id] : permissions.filter((x) => x !== p.id))} />
                {t(p.label)}
              </label>
            ))}
          </fieldset>
          {error && <div className="alert" role="alert">{error}</div>}
          <div className="row-actions">
            <button className="btn btn-primary">{t('Save role')}</button>
            <button type="button" className="btn btn-ghost" onClick={() => setEditing(null)}>{t('Cancel')}</button>
          </div>
        </form>
      )}
      {isOwner && catalog.roles.length > 0 && assignable.length > 0 && (
        <>
          <h3 className="side-title">{t('Who has which role')}</h3>
          <ul className="field-list">
            {assignable.map((m) => (
              <li key={m.id}>
                <span>{m.displayName}</span>
                <span className="spacer" />
                <select value={roleOf(m.id)?.id ?? ''} aria-label={t('Custom role of {name}', { name: m.displayName })}
                  onChange={async (e) => {
                    try {
                      await api.assignRole(projectKey, m.id, e.target.value ? Number(e.target.value) : null);
                      load();
                    } catch (err) {
                      toast((err as ApiError).message, 'error');
                    }
                  }}>
                  <option value="">{t('Full member')}</option>
                  {catalog.roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </li>
            ))}
          </ul>
        </>
      )}
      {deleting && (
        <ConfirmDialog title={t('Delete the role {name}?', { name: deleting.name })} confirmLabel={t('Delete')} danger
          message={t('Its members get full member access again.')}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await api.deleteRole(projectKey, deleting.id);
            setDeleting(null);
            load();
          }} />
      )}
    </section>
  );
}
