import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { AtSign, Plus, Trash2, UserPlus, Users } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { ConfirmDialog, Modal } from '../components/Modal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import type { Team } from '../types';
import { t } from '../i18n';

/** Teams group people across projects; @handle mentions everyone and membersOf(handle) works in search. */
export function TeamsPage() {
  const { user } = useAuth();
  const toast = useToast();
  const [teams, setTeams] = useState<Team[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Team | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Team | null>(null);
  const [adding, setAdding] = useState<Record<number, string>>({});

  const load = useCallback(() => {
    api.teams().then(setTeams).catch((e: ApiError) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  const run = async (action: () => Promise<unknown>, message: string) => {
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t("Workspace")}</span>
          <h1>{t("Teams")}</h1>
          <p className="muted">{t("Mention a whole team with")} <code>@handle</code> {t("in comments, or search with")} <code>assignee in membersOf(handle)</code>.</p>
        </div>
        <div className="header-actions">
          <button className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={17} /> {t("New team")}</button>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!teams && !error && <Spinner />}
      {teams && teams.length === 0 && (
        <EmptyState icon={<Users size={28} />} title={t("No teams yet")}>
          <button className="link" onClick={() => setEditing('new')}>{t("Create the first team")}</button>
        </EmptyState>
      )}
      <div className="team-grid">
        {teams?.map((team) => (
          <section key={team.id} className="panel team" aria-label={team.name}>
            <header className="team-head">
              <div>
                <h2 className="panel-title">{team.name}</h2>
                <span className="team-handle"><AtSign size={13} />{team.handle}</span>
              </div>
              {team.canEdit && (
                <span>
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(team)}>{t("Edit")}</button>
                  <button className="icon-button" aria-label={t('Delete {name}', { name: team.name })} onClick={() => setDeleting(team)}><Trash2 size={16} /></button>
                </span>
              )}
            </header>
            {team.description && <p className="muted">{team.description}</p>}
            <ul className="team-members">
              {team.members.map((m) => (
                <li key={m.id}>
                  <Avatar user={m} size={24} />
                  <span>{m.displayName}{m.awayUntil && <span className="away-badge" title={t('Away until {date}', { date: m.awayUntil })}>{t("away")}</span>}</span>
                  <span className="spacer" />
                  {(team.canEdit || m.id === user?.id) && (
                    <button className="link small" onClick={() => run(() => api.removeTeamMember(team.id, m.id),
                      m.id === user?.id ? t('You left @{handle}', { handle: team.handle }) : t('{name} removed', { name: m.displayName }))}>
                      {m.id === user?.id ? 'Leave' : 'Remove'}
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {team.canEdit && (
              <form className="inline-form" onSubmit={(e: FormEvent) => {
                e.preventDefault();
                const login = (adding[team.id] ?? '').trim();
                if (!login) return;
                setAdding({ ...adding, [team.id]: '' });
                run(() => api.addTeamMember(team.id, login), t('{name} added to @{handle}', { name: login, handle: team.handle }));
              }}>
                <input placeholder={t("Username or email")} value={adding[team.id] ?? ''} aria-label={t('Add someone to {name}', { name: team.name })}
                  onChange={(e) => setAdding({ ...adding, [team.id]: e.target.value })} />
                <button className="btn btn-soft btn-sm"><UserPlus size={15} /> {t("Add")}</button>
              </form>
            )}
          </section>
        ))}
      </div>
      {editing && <TeamModal team={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={(saved) => {
        setEditing(null);
        toast(editing === 'new' ? t('@{handle} created', { handle: saved.handle }) : t('Team saved'));
        load();
      }} />}
      {deleting && (
        <ConfirmDialog title={t('Delete {name}?', { name: deleting.name })} message={t('@{handle} will stop working in mentions and searches.', { handle: deleting.handle })}
          confirmLabel={t("Delete team")} danger onClose={() => setDeleting(null)}
          onConfirm={() => {
            const t = deleting;
            setDeleting(null);
            run(() => api.deleteTeam(t.id), `${t.name} deleted`);
          }} />
      )}
    </div>
  );
}

function TeamModal({ team, onClose, onSaved }: { team: Team | null; onClose: () => void; onSaved: (team: Team) => void }) {
  const [name, setName] = useState(team?.name ?? '');
  const [handle, setHandle] = useState(team?.handle ?? '');
  const [touched, setTouched] = useState(!!team);
  const [description, setDescription] = useState(team?.description ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      onSaved(team ? await api.updateTeam(team.id, name, handle, description) : await api.createTeam(name, handle, description));
    } catch (e) {
      const err = e as ApiError;
      setErrors(err.fieldErrors ?? {});
      setError(Object.keys(err.fieldErrors ?? {}).length ? '' : err.message);
    }
  };
  return (
    <Modal title={team ? t('Edit {name}', { name: team.name }) : t('New team')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="team-form" disabled={!name.trim() || !handle.trim()}>{team ? 'Save' : 'Create'}</button>
      </>
    }>
      <form id="team-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={name} maxLength={60} autoFocus placeholder={t("Design")} onChange={(e) => {
            setName(e.target.value);
            if (!touched) setHandle(e.target.value.toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40));
          }} />
        </label>
        <label className="field">
          <span>{t("Handle")}</span>
          <input value={handle} maxLength={40} placeholder={t("design")} aria-invalid={!!errors.handle}
            onChange={(e) => {
              setTouched(true);
              setHandle(e.target.value);
            }} />
          {errors.handle ? <span className="field-error">{errors.handle}</span> : <span className="muted small">{t('Mention with @{handle}', { handle: handle || 'handle' })}</span>}
        </label>
        <label className="field">
          <span>{t("Description")} <span className="muted">{t("(optional)")}</span></span>
          <textarea rows={2} maxLength={300} value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
