import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowDown, ArrowUp, Copy, Crown, Download, GitBranch, Link2, Plus, RefreshCw, Trash2, Upload, UserMinus, UserPlus,
} from 'lucide-react';
import { api, ApiError, githubWebhookUrl, inviteLink, saveBlob, type ColumnInput } from '../api';
import { useAuth } from '../auth';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { ConfirmDialog } from '../components/Modal';
import { ChatHooksSection } from '../components/ChatHooksSection';
import { RecurringSection } from '../components/settings/RecurringSection';
import { TemplatesSection } from '../components/settings/TemplatesSection';
import { Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDate } from '../format';
import {
  STATUSES, STATUS_LABEL, type BoardColumn, type GithubSettings, type ImportResult, type Invite, type Member, type Project,
  type Role, type Status,
} from '../types';

const ROLE_LABEL: Record<Role, string> = { OWNER: 'Owner', MEMBER: 'Member', VIEWER: 'Viewer' };

async function copy(text: string, toast: (message: string) => void) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Copied to clipboard');
  } catch {
    window.prompt('Copy this:', text);
  }
}

export function ProjectSettingsPage() {
  const { key, project, loading, canEdit, isOwner } = useRouteProject();
  const { user } = useAuth();
  const { refresh } = useProjects();
  const toast = useToast();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<{ kind: 'remove' | 'transfer'; member: Member } | { kind: 'leave' } | { kind: 'delete' } | null>(null);
  const [deleteText, setDeleteText] = useState('');

  useEffect(() => {
    if (project) {
      setName(project.name);
      setDescription(project.description);
    }
  }, [project]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project || !user) return <div className="page"><Spinner /></div>;

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
        <h2 className="panel-title">Workflow and look</h2>
        <div className="form narrow">
          <div className="segmented" role="radiogroup" aria-label="Planning mode">
            {([false, true] as const).map((kanban) => (
              <label key={String(kanban)} className={project.kanban === kanban ? 'active' : ''}>
                <input type="radio" name="mode" checked={project.kanban === kanban} disabled={!isOwner || busy}
                  onChange={() => run(() => api.updateProject(key, project.name, project.description, { kanban }),
                    kanban ? 'Switched to Kanban' : 'Switched to Scrum')} />
                {kanban ? 'Kanban' : 'Scrum'}
              </label>
            ))}
          </div>
          <p className="muted small">{project.kanban
            ? 'Kanban: no sprints. The board shows all work continuously; use WIP limits and the flow reports.'
            : 'Scrum: plan work in time-boxed sprints with burndown and velocity reports.'}</p>
          <div className="field">
            <span>Accent colour</span>
            <div className="color-row">
              {['', '#2a78d6', '#0e7c66', '#7c3aed', '#c2410c', '#be185d', '#475569'].map((color) => (
                <button key={color || 'default'} type="button" disabled={!isOwner || busy}
                  className={`color-swatch ${(project.color ?? '') === color ? 'picked' : ''}`}
                  style={color ? { background: color } : undefined}
                  aria-label={color ? `Accent ${color}` : 'Default accent'} aria-pressed={(project.color ?? '') === color}
                  onClick={() => run(() => api.updateProject(key, project.name, project.description, { color }), 'Colour updated')}>
                  {!color && 'A'}
                </button>
              ))}
              <input type="color" aria-label="Custom accent colour" disabled={!isOwner || busy} value={project.color ?? '#2a78d6'}
                onChange={(e) => run(() => api.updateProject(key, project.name, project.description, { color: e.target.value }), 'Colour updated')} />
            </div>
            <span className="muted small">Used for the project's accent while you work in it.</span>
          </div>
        </div>
      </section>

      <MembersSection project={project} isOwner={isOwner} busy={busy} run={run}
        onRemove={(member) => setConfirm({ kind: 'remove', member })}
        onTransfer={(member) => setConfirm({ kind: 'transfer', member })} />
      {isOwner && <InvitesSection project={project} />}
      <ColumnsSection project={project} canEdit={canEdit} />
      <TemplatesSection projectKey={project.key} canEdit={canEdit} />
      <RecurringSection projectKey={project.key} members={project.members} canEdit={canEdit} />
      {isOwner && <GithubSection project={project} onChange={refresh} />}
      {isOwner && <ChatHooksSection projectKey={project.key} />}
      <CsvSection project={project} canEdit={canEdit} />
      {canEdit && <EmailInSection projectKey={project.key} />}

      <section className="panel danger-zone">
        <div>
          <h2 className="panel-title">{isOwner ? 'Delete project' : 'Leave project'}</h2>
          <p className="muted">{isOwner
            ? 'Permanently deletes the project with all its tasks, sprints, epics, comments and files.'
            : 'You will lose access to this project. Your tasks here become unassigned.'}</p>
        </div>
        <button className="btn btn-ghost danger" onClick={() => setConfirm(isOwner ? { kind: 'delete' } : { kind: 'leave' })}>
          {isOwner ? 'Delete project' : 'Leave project'}
        </button>
      </section>

      {confirm?.kind === 'remove' && (
        <ConfirmDialog title={`Remove ${confirm.member.displayName}?`} confirmLabel="Remove" danger busy={busy}
          message="They lose access to this project and their tasks here become unassigned."
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await run(() => api.removeMember(key, confirm.member.id), `${confirm.member.displayName} removed`);
            setConfirm(null);
          }} />
      )}
      {confirm?.kind === 'transfer' && (
        <ConfirmDialog title={`Make ${confirm.member.displayName} the owner?`} confirmLabel="Hand over" busy={busy}
          message="They get full control of the project and its settings. You stay a member."
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await run(() => api.transferOwnership(key, confirm.member.id), `${confirm.member.displayName} now owns ${project.name}`);
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

interface MembersProps {
  project: Project;
  isOwner: boolean;
  busy: boolean;
  run: (action: () => Promise<unknown>, message: string) => Promise<boolean>;
  onRemove: (member: Member) => void;
  onTransfer: (member: Member) => void;
}

function MembersSection({ project, isOwner, busy, run, onRemove, onTransfer }: MembersProps) {
  const { user } = useAuth();
  const [login, setLogin] = useState('');
  const [role, setRole] = useState<Role>('MEMBER');
  const [suggestions, setSuggestions] = useState<{ id: number; username: string }[]>([]);
  const debounce = useRef<number>(undefined);

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

  const memberIds = new Set(project.members.map((m) => m.id));
  const open = suggestions.filter((s) => !memberIds.has(s.id));

  const add = async (event?: FormEvent, value = login) => {
    event?.preventDefault();
    if (!value.trim()) return;
    if (await run(() => api.addMember(project.key, value.trim(), role), `${value.trim()} added as ${ROLE_LABEL[role].toLowerCase()}`)) {
      setLogin('');
      setSuggestions([]);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title">Members <span className="count">{project.members.length}</span></h2>
      <p className="muted small hint">Members plan and work on tasks. Viewers can see everything and watch tasks, but not change them or comment.</p>
      {isOwner && (
        <form className="add-member" onSubmit={add}>
          <div className="add-member-input">
            <input value={login} placeholder="Username or email" onChange={(e) => setLogin(e.target.value)} aria-label="Username or email" />
            {open.length > 0 && (
              <ul className="suggest-list">
                {open.map((s) => (
                  <li key={s.id}><button type="button" onClick={() => add(undefined, s.username)}>
                    <Avatar name={s.username} size={22} /> {s.username}
                  </button></li>
                ))}
              </ul>
            )}
          </div>
          <select value={role} onChange={(e) => setRole(e.target.value as Role)} aria-label="Role for the new member">
            <option value="MEMBER">Member</option>
            <option value="VIEWER">Viewer</option>
          </select>
          <button className="btn btn-soft" disabled={busy || !login.trim()}><UserPlus size={16} /> Add</button>
        </form>
      )}
      <ul className="member-list">
        {project.members.map((member) => (
          <li key={member.id}>
            <Avatar user={member} size={32} />
            <div className="member-text">
              <strong>{member.displayName}{member.id === user?.id && <span className="muted"> (you)</span>}
                {member.awayUntil && <span className="away-badge" title={`Away until ${member.awayUntil}`}>away</span>}</strong>
              <span className="muted small">@{member.username} · {member.email}</span>
            </div>
            {member.role === 'OWNER' ? (
              <span className="owner-badge"><Crown size={13} /> Owner</span>
            ) : isOwner ? (
              <>
                <select className="role-select" value={member.role} aria-label={`Role of ${member.displayName}`}
                  onChange={(e) => run(() => api.setRole(project.key, member.id, e.target.value as Role),
                    `${member.displayName} is now a ${ROLE_LABEL[e.target.value as Role].toLowerCase()}`)}>
                  <option value="MEMBER">Member</option>
                  <option value="VIEWER">Viewer</option>
                </select>
                <button className="icon-button" aria-label={`Make ${member.displayName} the owner`} title="Hand over ownership"
                  onClick={() => onTransfer(member)}>
                  <Crown size={17} />
                </button>
                <button className="icon-button" aria-label={`Remove ${member.displayName}`} title="Remove from project"
                  onClick={() => onRemove(member)}>
                  <UserMinus size={17} />
                </button>
              </>
            ) : (
              <span className={`role-badge role-${member.role.toLowerCase()}`}>{ROLE_LABEL[member.role]}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function InvitesSection({ project }: { project: Project }) {
  const toast = useToast();
  const [invites, setInvites] = useState<Invite[] | null>(null);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.invites().then((all) => setInvites(all.filter((i) => i.projectKey === project.key))).catch(() => setInvites([]));
  }, [project.key]);
  useEffect(load, [load]);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const invite = await api.createInvite(email.trim() || null, project.key);
      setEmail('');
      load();
      await copy(inviteLink(invite.code), toast);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const pending = (invites ?? []).filter((i) => !i.usedAt && new Date(i.expiresAt) > new Date());

  return (
    <section className="panel">
      <h2 className="panel-title"><Link2 size={16} /> Invite links</h2>
      <p className="muted small hint">
        Invite people who don't have an account yet. They join {project.name} as members when they sign up. Links are valid for 7 days.
      </p>
      <form className="inline-form" onSubmit={create}>
        <input type="email" value={email} placeholder="Email (optional — binds the invite)" aria-label="Invite email"
          onChange={(e) => setEmail(e.target.value)} />
        <button className="btn btn-soft" disabled={busy}><Plus size={16} /> Create link</button>
      </form>
      {pending.length > 0 && (
        <ul className="mini-list invite-list">
          {pending.map((invite) => (
            <li key={invite.id}>
              <div className="invite-text">
                <strong>{invite.email ?? 'Anyone with the link'}</strong>
                <span className="muted small">Expires {formatDate(invite.expiresAt)} · by {invite.createdBy}</span>
              </div>
              <button className="icon-button sm" aria-label="Copy invite link" title="Copy link" onClick={() => copy(inviteLink(invite.code), toast)}>
                <Copy size={15} />
              </button>
              <button className="icon-button sm" aria-label="Revoke invite" title="Revoke"
                onClick={async () => {
                  await api.revokeInvite(invite.id);
                  load();
                }}>
                <Trash2 size={15} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ColumnsSection({ project, canEdit }: { project: Project; canEdit: boolean }) {
  const toast = useToast();
  const [columns, setColumns] = useState<BoardColumn[] | null>(null);
  const [draft, setDraft] = useState<ColumnInput>({ name: '', status: 'IN_PROGRESS', wipLimit: null });

  useEffect(() => {
    api.columns(project.key).then(setColumns).catch(() => setColumns([]));
  }, [project.key]);

  const apply = async (action: () => Promise<BoardColumn[]>) => {
    try {
      setColumns(await action());
      return true;
    } catch (e) {
      toast((e as ApiError).message, 'error');
      return false;
    }
  };

  const update = (column: BoardColumn, change: Partial<ColumnInput>) =>
    apply(() => api.updateColumn(column.id, { name: column.name, status: column.status, wipLimit: column.wipLimit, ...change }));

  const add = async (event: FormEvent) => {
    event.preventDefault();
    if (!draft.name.trim()) return;
    if (await apply(() => api.addColumn(project.key, { ...draft, name: draft.name.trim() }))) {
      setDraft({ name: '', status: draft.status, wipLimit: null });
      toast('Column added');
    }
  };

  const limit = (value: string) => (value === '' ? null : Math.max(1, Math.min(99, Number(value))));

  return (
    <section className="panel">
      <h2 className="panel-title">Board columns</h2>
      <p className="muted small hint">
        Each column maps to a status. Split a status into several columns (e.g. “QA” and “Code review” for In review) and set
        work-in-progress limits — the column turns red when it holds more cards.
      </p>
      {!columns ? <Spinner /> : (
        <ul className="column-editor">
          {columns.map((column, index) => (
            <li key={column.id}>
              <span className={`status-dot status-${column.status.toLowerCase()}`} aria-hidden />
              {canEdit ? (
                <>
                  <input className="column-name" defaultValue={column.name} maxLength={40} aria-label="Column name"
                    onBlur={(e) => e.target.value.trim() && e.target.value.trim() !== column.name && update(column, { name: e.target.value.trim() })} />
                  <select value={column.status} aria-label={`Status of ${column.name}`}
                    onChange={(e) => update(column, { status: e.target.value as Status })}>
                    {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
                  </select>
                  <input className="wip-input" type="number" min={1} max={99} placeholder="No limit" defaultValue={column.wipLimit ?? ''}
                    aria-label={`WIP limit of ${column.name}`}
                    onBlur={(e) => limit(e.target.value) !== column.wipLimit && update(column, { wipLimit: limit(e.target.value) })} />
                  <button className="icon-button sm" disabled={index === 0} aria-label={`Move ${column.name} left`}
                    onClick={() => apply(() => api.moveColumn(column.id, -1))}><ArrowUp size={15} /></button>
                  <button className="icon-button sm" disabled={index === columns.length - 1} aria-label={`Move ${column.name} right`}
                    onClick={() => apply(() => api.moveColumn(column.id, 1))}><ArrowDown size={15} /></button>
                  <button className="icon-button sm" aria-label={`Delete ${column.name}`}
                    onClick={() => apply(() => api.deleteColumn(column.id))}><Trash2 size={15} /></button>
                </>
              ) : (
                <>
                  <strong className="column-name">{column.name}</strong>
                  <span className="muted small">{STATUS_LABEL[column.status]}{column.wipLimit ? ` · limit ${column.wipLimit}` : ''}</span>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && columns && columns.length < 12 && (
        <form className="inline-form column-add" onSubmit={add}>
          <input value={draft.name} maxLength={40} placeholder="New column name" aria-label="New column name"
            onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          <select value={draft.status} aria-label="New column status" onChange={(e) => setDraft({ ...draft, status: e.target.value as Status })}>
            {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
          <input className="wip-input" type="number" min={1} max={99} placeholder="WIP limit" value={draft.wipLimit ?? ''}
            aria-label="New column WIP limit" onChange={(e) => setDraft({ ...draft, wipLimit: limit(e.target.value) })} />
          <button className="btn btn-soft" disabled={!draft.name.trim()}><Plus size={16} /> Add column</button>
        </form>
      )}
    </section>
  );
}

function GithubSection({ project, onChange }: { project: Project; onChange: () => Promise<unknown> }) {
  const toast = useToast();
  const [settings, setSettings] = useState<GithubSettings | null>(null);
  const [confirmOff, setConfirmOff] = useState(false);

  useEffect(() => {
    api.github(project.key).then(setSettings).catch(() => setSettings(null));
  }, [project.key]);

  const act = async (action: () => Promise<GithubSettings | void>, message: string) => {
    try {
      const next = await action();
      setSettings(next ?? await api.github(project.key));
      await onChange();
      toast(message);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><GitBranch size={16} /> GitHub</h2>
      <p className="muted small hint">
        Mention task keys like <code>{project.key}-12</code> in commit messages, branch names or pull request titles and they show up on
        the task. Optionally move tasks to Done when their pull request is merged.
      </p>
      {!settings ? <Spinner /> : !settings.enabled ? (
        <button className="btn btn-soft" onClick={() => act(() => api.enableGithub(project.key), 'GitHub integration enabled')}>
          <GitBranch size={16} /> Connect a repository
        </button>
      ) : (
        <div className="github-setup">
          <ol className="steps small">
            <li>In your GitHub repository open <b>Settings → Webhooks → Add webhook</b>.</li>
            <li>Paste the payload URL and secret below and choose content type <b>application/json</b>.</li>
            <li>Select <b>Let me select individual events</b> → <b>Pushes</b> and <b>Pull requests</b>.</li>
          </ol>
          <label className="field">
            <span>Payload URL</span>
            <div className="copy-field">
              <input readOnly value={githubWebhookUrl(project.key)} onFocus={(e) => e.target.select()} />
              <button type="button" className="icon-button" aria-label="Copy payload URL" onClick={() => copy(githubWebhookUrl(project.key), toast)}>
                <Copy size={16} />
              </button>
            </div>
          </label>
          <label className="field">
            <span>Secret</span>
            <div className="copy-field">
              <input readOnly value={settings.secret ?? ''} onFocus={(e) => e.target.select()} className="mono" />
              <button type="button" className="icon-button" aria-label="Copy secret" onClick={() => copy(settings.secret ?? '', toast)}>
                <Copy size={16} />
              </button>
            </div>
          </label>
          <label className="toggle">
            <input type="checkbox" checked={settings.autoDone}
              onChange={(e) => act(() => api.setGithubAutoDone(project.key, e.target.checked),
                e.target.checked ? 'Merged pull requests now complete their tasks' : 'Automatic completion turned off')} />
            Move tasks to Done when a pull request mentioning them is merged
          </label>
          <div className="button-row">
            <button className="btn btn-ghost" onClick={() => act(() => api.enableGithub(project.key), 'New secret generated — update the webhook')}>
              <RefreshCw size={15} /> Rotate secret
            </button>
            <button className="btn btn-ghost danger" onClick={() => setConfirmOff(true)}>Disconnect</button>
          </div>
        </div>
      )}
      {confirmOff && (
        <ConfirmDialog title="Disconnect GitHub?" confirmLabel="Disconnect" danger
          message="Webhook deliveries will be rejected. Links already shown on tasks stay."
          onClose={() => setConfirmOff(false)}
          onConfirm={async () => {
            await act(() => api.disableGithub(project.key), 'GitHub disconnected');
            setConfirmOff(false);
          }} />
      )}
    </section>
  );
}

function CsvSection({ project, canEdit }: { project: Project; canEdit: boolean }) {
  const toast = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);

  const exportCsv = async () => {
    try {
      saveBlob(await api.exportCsv(project.key), `${project.key.toLowerCase()}-tasks.csv`);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const importCsv = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setResult(null);
    try {
      const outcome = await api.importCsv(project.key, file);
      setResult(outcome);
      toast(`${outcome.created} task${outcome.created === 1 ? '' : 's'} imported`);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title">Import &amp; export</h2>
      <p className="muted small hint">
        Export every task as a CSV for spreadsheets. Import creates one task per row — columns: <code>Title</code> (required),
        Description, Status, Priority, Assignee, Labels (separated by ;), Due date, Story points, Epic.
      </p>
      <div className="button-row">
        <button className="btn btn-soft" onClick={exportCsv}><Download size={16} /> Export CSV</button>
        {canEdit && (
          <>
            <button className="btn btn-soft" disabled={busy} onClick={() => fileRef.current?.click()}>
              <Upload size={16} /> {busy ? 'Importing…' : 'Import CSV'}
            </button>
            <input ref={fileRef} type="file" accept=".csv,text/csv" hidden onChange={(e) => {
              importCsv(e.target.files?.[0]);
              e.target.value = '';
            }} />
          </>
        )}
      </div>
      {result && (
        <div className="import-result">
          <p><b>{result.created}</b> task{result.created === 1 ? '' : 's'} created
            {result.keys.length > 0 && <span className="muted"> ({result.keys.slice(0, 6).join(', ')}{result.keys.length > 6 ? '…' : ''})</span>}.
          </p>
          {result.errors.length > 0 && (
            <ul className="import-errors">
              {result.errors.slice(0, 20).map((error) => (
                <li key={`${error.row}-${error.message}`}>Row {error.row}: {error.message}</li>
              ))}
              {result.errors.length > 20 && <li className="muted">…and {result.errors.length - 20} more</li>}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}

/** When inbound mail is configured: the address that turns emails into tasks. */
function EmailInSection({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [address, setAddress] = useState<string | null>(null);
  useEffect(() => {
    api.projectEmail(projectKey).then((r) => setAddress(r.address)).catch(() => setAddress(null));
  }, [projectKey]);
  if (!address) return null;
  return (
    <section className="panel">
      <h2 className="panel-title">Create tasks by email</h2>
      <p className="muted small hint">Members can email this address: the subject becomes the title and the text the description.
        Replying to a notification email adds a comment.</p>
      <div className="copy-field">
        <input readOnly value={address} aria-label="Project email address" onFocus={(e) => e.target.select()} />
        <button className="btn btn-soft btn-sm" onClick={() => navigator.clipboard.writeText(address)
          .then(() => toast('Address copied')).catch(() => toast('Could not copy', 'error'))}>Copy</button>
      </div>
    </section>
  );
}
