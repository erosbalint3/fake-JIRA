import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { Archive, Check, Copy, Download, Link2, Plus, Shield, ShieldOff, Trash2, X } from 'lucide-react';
import { api, ApiError, inviteLink, saveBlob } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { ErrorBanner, Spinner } from '../components/States';
import { fileSize, formatDate, timeAgo } from '../format';
import type { AdminUser, Backup, Invite, RegistrationMode } from '../types';

const MODES: { mode: RegistrationMode; title: string; text: string }[] = [
  { mode: 'OPEN', title: 'Open', text: 'Anyone can create an account.' },
  { mode: 'APPROVAL', title: 'Admin approval', text: 'Anyone can sign up, but an admin approves each account before it can sign in.' },
  { mode: 'INVITE', title: 'Invite only', text: 'Only people with an invite link can sign up.' },
];

/** Workspace administration: who may sign up, pending accounts, admins, invites and backups. */
export function AdminPage() {
  const { admin, user } = useAuth();
  const toast = useToast();
  const [mode, setMode] = useState<RegistrationMode | null>(null);
  const [site, setSite] = useState<{ url: string; configured: boolean } | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [backups, setBackups] = useState<Backup[] | null>(null);
  const [inviteEmail, setInviteEmail] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.admin().then((overview) => {
      setMode(overview.registrationMode);
      setUsers(overview.users);
      setSite({ url: overview.siteUrl, configured: overview.siteUrlConfigured });
    }).catch((e: ApiError) => setError(e.message));
    api.invites().then(setInvites).catch(() => setInvites([]));
    api.backups().then(setBackups).catch(() => setBackups([]));
  }, []);

  useEffect(() => {
    if (admin) load();
  }, [admin, load]);

  if (!admin) return <Navigate to="/" replace />;

  const act = async (action: () => Promise<unknown>, message: string) => {
    setBusy(true);
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast('Invite link copied');
    } catch {
      window.prompt('Copy the invite link:', text);
    }
  };

  const createInvite = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const invite = await api.createInvite(inviteEmail.trim() || null, null);
      setInviteEmail('');
      load();
      await copy(inviteLink(invite.code));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const pending = users.filter((u) => u.status === 'PENDING');
  const active = users.filter((u) => u.status === 'ACTIVE');
  const openInvites = invites.filter((i) => !i.usedAt && new Date(i.expiresAt) > new Date());

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Workspace</span>
          <h1>Admin</h1>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!mode && !error && <Spinner />}
      {mode && (
        <>
          {site && site.url !== window.location.origin && (
            <div className="alert">
              Links in emails point to <b>{site.url}</b>, but you are using {window.location.origin}.
              {site.configured
                ? ' Update APP_BASE_URL in your .env and restart the app.'
                : ' Reload this page to update it, or set APP_BASE_URL in your .env.'}
            </div>
          )}
          <section className="panel">
            <h2 className="panel-title">Sign-up</h2>
            <div className="mode-options" role="radiogroup" aria-label="Who can sign up">
              {MODES.map((option) => (
                <label key={option.mode} className={`mode-option ${mode === option.mode ? 'active' : ''}`}>
                  <input type="radio" name="registration" checked={mode === option.mode} disabled={busy}
                    onChange={() => act(() => api.setRegistrationMode(option.mode), `Sign-up is now: ${option.title.toLowerCase()}`)} />
                  <strong>{option.title}</strong>
                  <span className="muted small">{option.text}</span>
                </label>
              ))}
            </div>
          </section>

          {pending.length > 0 && (
            <section className="panel">
              <h2 className="panel-title">Waiting for approval <span className="count">{pending.length}</span></h2>
              <ul className="member-list">
                {pending.map(({ user: u, createdAt }) => (
                  <li key={u.id}>
                    <Avatar user={u} size={32} />
                    <div className="member-text">
                      <strong>{u.username}</strong>
                      <span className="muted small">{u.email} · signed up {timeAgo(createdAt)}</span>
                    </div>
                    <button className="btn btn-soft sm" disabled={busy}
                      onClick={() => act(() => api.approveUser(u.id), `${u.username} approved`)}><Check size={15} /> Approve</button>
                    <button className="btn btn-ghost sm danger" disabled={busy}
                      onClick={() => act(() => api.rejectUser(u.id), `${u.username} rejected`)}><X size={15} /> Reject</button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className="panel">
            <h2 className="panel-title"><Link2 size={16} /> Invites</h2>
            <p className="muted small hint">Invite links work in every sign-up mode and are valid for 7 days. Project owners can also invite people
              to their project from its settings.</p>
            <form className="inline-form" onSubmit={createInvite}>
              <input type="email" value={inviteEmail} placeholder="Email (optional — binds the invite)" aria-label="Invite email"
                onChange={(e) => setInviteEmail(e.target.value)} />
              <button className="btn btn-soft" disabled={busy}><Plus size={16} /> Create invite</button>
            </form>
            {openInvites.length > 0 && (
              <ul className="mini-list invite-list">
                {openInvites.map((invite) => (
                  <li key={invite.id}>
                    <div className="invite-text">
                      <strong>{invite.email ?? 'Anyone with the link'}{invite.projectKey && <span className="muted"> → {invite.projectKey}</span>}</strong>
                      <span className="muted small">Expires {formatDate(invite.expiresAt)} · by {invite.createdBy}</span>
                    </div>
                    <button className="icon-button sm" aria-label="Copy invite link" title="Copy link" onClick={() => copy(inviteLink(invite.code))}>
                      <Copy size={15} />
                    </button>
                    <button className="icon-button sm" aria-label="Revoke invite" title="Revoke"
                      onClick={() => act(() => api.revokeInvite(invite.id), 'Invite revoked')}>
                      <Trash2 size={15} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <h2 className="panel-title">Users <span className="count">{active.length}</span></h2>
            <ul className="member-list">
              {active.map(({ user: u, admin: isAdmin, createdAt }) => (
                <li key={u.id}>
                  <Avatar user={u} size={32} />
                  <div className="member-text">
                    <strong>{u.displayName}{u.id === user?.id && <span className="muted"> (you)</span>}</strong>
                    <span className="muted small">@{u.username} · {u.email} · joined {formatDate(createdAt)}</span>
                  </div>
                  {isAdmin && <span className="owner-badge"><Shield size={13} /> Admin</span>}
                  <button className="icon-button" disabled={busy}
                    aria-label={isAdmin ? `Remove admin rights from ${u.username}` : `Make ${u.username} an admin`}
                    title={isAdmin ? 'Remove admin rights' : 'Make admin'}
                    onClick={() => act(() => api.setAdmin(u.id, !isAdmin), isAdmin ? `${u.username} is no longer an admin` : `${u.username} is now an admin`)}>
                    {isAdmin ? <ShieldOff size={17} /> : <Shield size={17} />}
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title"><Archive size={16} /> Backups</h2>
              <button className="btn btn-soft sm" disabled={busy}
                onClick={() => act(() => api.backupNow(), 'Backup created')}>Back up now</button>
            </div>
            <p className="muted small hint">A backup of the database and attachments is made every night; the last 14 are kept in the data
              volume. Download one to keep a copy off the server — the README explains how to restore it.</p>
            {!backups ? <Spinner /> : backups.length === 0 ? <p className="muted">No backups yet.</p> : (
              <ul className="mini-list">
                {backups.map((backup) => (
                  <li key={backup.name}>
                    <div className="invite-text">
                      <strong className="mono small">{backup.name}</strong>
                      <span className="muted small">{formatDate(backup.createdAt)} · {fileSize(backup.size)}</span>
                    </div>
                    <button className="icon-button sm" aria-label={`Download ${backup.name}`} title="Download"
                      onClick={async () => {
                        try {
                          saveBlob(await api.backupBlob(backup.name), backup.name);
                        } catch (e) {
                          toast((e as ApiError).message, 'error');
                        }
                      }}>
                      <Download size={15} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}
