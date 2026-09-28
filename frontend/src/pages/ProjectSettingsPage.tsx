import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Crown, UserMinus, UserPlus } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { ConfirmDialog } from '../components/Modal';
import { Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import type { User } from '../types';

export function ProjectSettingsPage() {
  const { key, project, loading } = useRouteProject();
  const { user } = useAuth();
  const { refresh } = useProjects();
  const toast = useToast();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [login, setLogin] = useState('');
  const [suggestions, setSuggestions] = useState<{ id: number; username: string }[]>([]);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<{ kind: 'remove'; member: User } | { kind: 'leave' } | { kind: 'delete' } | null>(null);
  const [deleteText, setDeleteText] = useState('');
  const debounce = useRef<number>(undefined);

  useEffect(() => {
    if (project) {
      setName(project.name);
      setDescription(project.description);
    }
  }, [project]);

  useEffect(() => {
    window.clearTimeout(debounce.current);
    if (login.trim().length < 2 || login.includes('@')) {
      setSuggestions([]);
      return;
    }
    debounce.current = window.setTimeout(() => {
      api.searchUsers(login.trim()).then(setSuggestions).catch(() => setSuggestions([]));
    }, 200);
  }, [login]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project || !user) return <div className="page"><Spinner /></div>;

  const isOwner = project.owner.id === user.id;
  const memberIds = new Set(project.members.map((m) => m.id));

  const run = async (action: () => Promise<unknown>, message: string) => {
    setBusy(true);
    try {
      await action();
      await refresh();
      toast(message);
      return true;
    } catch (e) {
      toast((e as ApiError).message, 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const save = (event: FormEvent) => {
    event.preventDefault();
    run(() => api.updateProject(key, name, description), 'Project updated');
  };

  const add = async (event?: FormEvent, value = login) => {
    event?.preventDefault();
    if (!value.trim()) return;
    if (await run(() => api.addMember(key, value.trim()), `${value.trim()} added`)) {
      setLogin('');
      setSuggestions([]);
    }
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Project settings</h1>
        </div>
      </header>

      <section className="panel">
        <h2 className="panel-title">Details</h2>
        <form className="form narrow" onSubmit={save}>
          <label className="field">
            <span>Name</span>
            <input value={name} maxLength={80} disabled={!isOwner} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>Key</span>
            <input value={project.key} disabled />
          </label>
          <label className="field">
            <span>Description</span>
            <textarea rows={3} maxLength={1000} value={description} disabled={!isOwner} onChange={(e) => setDescription(e.target.value)} />
          </label>
          {isOwner && <div><button className="btn btn-primary" disabled={busy || !name.trim()}>Save</button></div>}
        </form>
      </section>

      <section className="panel">
        <h2 className="panel-title">Members <span className="count">{project.members.length}</span></h2>
        {isOwner && (
          <form className="add-member" onSubmit={add}>
            <div className="add-member-input">
              <input value={login} placeholder="Username or email" onChange={(e) => setLogin(e.target.value)} aria-label="Username or email" />
              {suggestions.filter((s) => !memberIds.has(s.id)).length > 0 && (
                <ul className="suggest-list">
                  {suggestions.filter((s) => !memberIds.has(s.id)).map((s) => (
                    <li key={s.id}><button type="button" onClick={() => add(undefined, s.username)}>
                      <Avatar name={s.username} size={22} /> {s.username}
                    </button></li>
                  ))}
                </ul>
              )}
            </div>
            <button className="btn btn-soft" disabled={busy || !login.trim()}><UserPlus size={16} /> Add</button>
          </form>
        )}
        <ul className="member-list">
          {project.members.map((member) => (
            <li key={member.id}>
              <Avatar name={member.username} size={32} />
              <div className="member-text">
                <strong>{member.username}{member.id === user.id && <span className="muted"> (you)</span>}</strong>
                <span className="muted small">{member.email}</span>
              </div>
              {member.id === project.owner.id && <span className="owner-badge"><Crown size={13} /> Owner</span>}
              {isOwner && member.id !== project.owner.id && (
                <button className="icon-button" aria-label={`Remove ${member.username}`} title="Remove from project"
                  onClick={() => setConfirm({ kind: 'remove', member })}>
                  <UserMinus size={17} />
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="panel danger-zone">
        <div>
          <h2 className="panel-title">{isOwner ? 'Delete project' : 'Leave project'}</h2>
          <p className="muted">{isOwner
            ? 'Permanently deletes the project with all its tasks, sprints, comments and files.'
            : 'You will lose access to this project. Your tasks here become unassigned.'}</p>
        </div>
        <button className="btn btn-ghost danger" onClick={() => setConfirm(isOwner ? { kind: 'delete' } : { kind: 'leave' })}>
          {isOwner ? 'Delete project' : 'Leave project'}
        </button>
      </section>

      {confirm?.kind === 'remove' && (
        <ConfirmDialog title={`Remove ${confirm.member.username}?`} confirmLabel="Remove" danger busy={busy}
          message="They lose access to this project and their tasks here become unassigned."
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await run(() => api.removeMember(key, confirm.member.id), `${confirm.member.username} removed`);
            setConfirm(null);
          }} />
      )}
      {confirm?.kind === 'leave' && (
        <ConfirmDialog title={`Leave ${project.name}?`} confirmLabel="Leave project" danger busy={busy}
          message="You will need to be added again by the owner to come back."
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            if (await run(() => api.removeMember(key, user.id), `You left ${project.name}`)) navigate('/projects');
          }} />
      )}
      {confirm?.kind === 'delete' && (
        <ConfirmDialog title={`Delete ${project.name}?`} confirmLabel="Delete forever" danger busy={busy || deleteText !== project.key}
          message={`This cannot be undone. Type ${project.key} below to confirm.`}
          onClose={() => {
            setConfirm(null);
            setDeleteText('');
          }}
          onConfirm={async () => {
            if (await run(() => api.deleteProject(key), `${project.name} deleted`)) navigate('/projects');
          }}>
          <input value={deleteText} onChange={(e) => setDeleteText(e.target.value.toUpperCase())}
            placeholder={project.key} aria-label="Type the project key to confirm" />
        </ConfirmDialog>
      )}
    </div>
  );
}
