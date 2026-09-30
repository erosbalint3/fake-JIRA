import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { Archive, Check, Copy, Download, History, Link2, Plus, Shield, Trash2, X } from 'lucide-react';
import { AccessPanel } from '../components/admin/AccessPanel';
import { DataPanel, HealthPanel } from '../components/admin/DataPanel';
import { Modal } from '../components/Modal';
import { api, ApiError, inviteLink, saveBlob } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { ErrorBanner, Spinner } from '../components/States';
import { ActionMenu } from '../components/ActionMenu';
import { ConfirmDialog } from '../components/Modal';
import type { RestoreStatus } from '../types';
import { SystemPanel } from '../components/admin/SystemPanel';
import { AuditLogPanel } from '../components/admin/AuditLogPanel';
import { PasswordPolicyPanel } from '../components/admin/PasswordPolicyPanel';
import type { User } from '../types';
import { fileSize, formatDate, timeAgo } from '../format';
import type { AdminUser, Backup, Invite, RegistrationMode } from '../types';
import { t } from '../i18n';

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
  const [tab, setTab] = useState<'people' | 'access' | 'security' | 'backups' | 'system'>('people');
  const [restoreStatus, setRestoreStatus] = useState<RestoreStatus | null>(null);
  const [restoring, setRestoring] = useState<string | null>(null);
  const [restorePassword, setRestorePassword] = useState('');
  const [restoreError, setRestoreError] = useState('');
  const [confirm, setConfirm] = useState<{ kind: 'delete' | 'reset2fa'; user: User } | null>(null);

  const load = useCallback(() => {
    api.admin().then((overview) => {
      setMode(overview.registrationMode);
      setUsers(overview.users);
      setSite({ url: overview.siteUrl, configured: overview.siteUrlConfigured });
    }).catch((e: ApiError) => setError(e.message));
    api.invites().then(setInvites).catch(() => setInvites([]));
    api.backups().then(setBackups).catch(() => setBackups([]));
    api.restoreStatus().then(setRestoreStatus).catch(() => setRestoreStatus(null));
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
      toast(t("Invite link copied"));
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
  const suspended = users.filter((u) => u.status === 'SUSPENDED');
  const openInvites = invites.filter((i) => !i.usedAt && new Date(i.expiresAt) > new Date());

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t("Workspace")}</span>
          <h1>{t("Admin")}</h1>
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
          <nav className="tabs" role="tablist" aria-label={t("Admin sections")}>
            {([['people', 'People & sign-up'], ['access', 'Sign-in & access'], ['security', 'Security'], ['backups', 'Data & backups'], ['system', 'System']] as const).map(([id, label]) => (
              <button key={id} role="tab" aria-selected={tab === id} className={`tab ${tab === id ? 'active' : ''}`}
                onClick={() => setTab(id)}>{t(label)}</button>
            ))}
          </nav>
          {tab === 'people' && (
          <>
          <section className="panel">
            <h2 className="panel-title">{t("Sign-up")}</h2>
            <div className="mode-options" role="radiogroup" aria-label={t("Who can sign up")}>
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
              <h2 className="panel-title">{t("Waiting for approval")} <span className="count">{pending.length}</span></h2>
              <ul className="member-list">
                {pending.map(({ user: u, createdAt }) => (
                  <li key={u.id}>
                    <Avatar user={u} size={32} />
                    <div className="member-text">
                      <strong>{u.username}</strong>
                      <span className="muted small">{u.email} · signed up {timeAgo(createdAt)}</span>
                    </div>
                    <button className="btn btn-soft sm" disabled={busy}
                      onClick={() => act(() => api.approveUser(u.id), `${u.username} approved`)}><Check size={15} /> {t("Approve")}</button>
                    <button className="btn btn-ghost sm danger" disabled={busy}
                      onClick={() => act(() => api.rejectUser(u.id), `${u.username} rejected`)}><X size={15} /> {t("Reject")}</button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section className="panel">
            <h2 className="panel-title"><Link2 size={16} /> {t("Invites")}</h2>
            <p className="muted small hint">Invite links work in every sign-up mode and are valid for 7 days. Project owners can also invite people
              to their project from its settings.</p>
            <form className="inline-form" onSubmit={createInvite}>
              <input type="email" value={inviteEmail} placeholder={t("Email (optional — binds the invite)")} aria-label={t("Invite email")}
                onChange={(e) => setInviteEmail(e.target.value)} />
              <button className="btn btn-soft" disabled={busy}><Plus size={16} /> {t("Create invite")}</button>
            </form>
            {openInvites.length > 0 && (
              <ul className="mini-list invite-list">
                {openInvites.map((invite) => (
                  <li key={invite.id}>
                    <div className="invite-text">
                      <strong>{invite.email ?? 'Anyone with the link'}{invite.projectKey && <span className="muted"> → {invite.projectKey}</span>}</strong>
                      <span className="muted small">Expires {formatDate(invite.expiresAt)} · by {invite.createdBy}</span>
                    </div>
                    <button className="icon-button sm" aria-label={t("Copy invite link")} title={t("Copy link")} onClick={() => copy(inviteLink(invite.code))}>
                      <Copy size={15} />
                    </button>
                    <button className="icon-button sm" aria-label={t("Revoke invite")} title={t("Revoke")}
                      onClick={() => act(() => api.revokeInvite(invite.id), 'Invite revoked')}>
                      <Trash2 size={15} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <h2 className="panel-title">{t("Users")} <span className="count">{active.length}</span></h2>
            <ul className="member-list">
              {active.map(({ user: u, admin: isAdmin, createdAt, twoFactor, mustChangePassword }) => (
                <li key={u.id}>
                  <Avatar user={u} size={32} />
                  <div className="member-text">
                    <strong>{u.displayName}{u.id === user?.id && <span className="muted"> {t("(you)")}</span>}</strong>
                    <span className="muted small">@{u.username} · {u.email} · joined {formatDate(createdAt)}</span>
                  </div>
                  {twoFactor && <span className="tag-2fa" title={t("Two-step verification is on")}>{t("2FA")}</span>}
                  {mustChangePassword && <span className="tag-warn">{t("Must change password")}</span>}
                  {isAdmin && <span className="owner-badge"><Shield size={13} /> {t("Admin")}</span>}
                  <ActionMenu label={`Actions for ${u.username}`} actions={[
                    { label: isAdmin ? 'Remove admin rights' : 'Make admin',
                      onSelect: () => act(() => api.setAdmin(u.id, !isAdmin), isAdmin ? `${u.username} is no longer an admin` : `${u.username} is now an admin`) },
                    { label: 'Require password change', hidden: mustChangePassword,
                      onSelect: () => act(() => api.requirePasswordChange(u.id), `${u.username} must choose a new password`) },
                    { label: 'Turn off two-step verification', hidden: !twoFactor,
                      onSelect: () => setConfirm({ kind: 'reset2fa', user: u }) },
                    { label: 'Sign out everywhere',
                      onSelect: () => act(() => api.signOutUser(u.id), `${u.username} was signed out everywhere`) },
                    { label: t('Deactivate'), hidden: u.id === user?.id,
                      onSelect: () => act(() => api.suspendUser(u.id), t('{name} was deactivated', { name: u.username })) },
                    { label: 'Delete account…', danger: true, hidden: u.id === user?.id,
                      onSelect: () => setConfirm({ kind: 'delete', user: u }) },
                  ]} />
                </li>
              ))}
            </ul>
          </section>

          {suspended.length > 0 && (
            <section className="panel">
              <h2 className="panel-title">{t('Deactivated')} <span className="count">{suspended.length}</span></h2>
              <ul className="member-list">
                {suspended.map(({ user: u }) => (
                  <li key={u.id}>
                    <Avatar user={u} size={32} />
                    <div className="member-text">
                      <strong>{u.displayName}</strong>
                      <span className="muted small">@{u.username} · {u.email}</span>
                    </div>
                    <button className="btn btn-soft sm" disabled={busy}
                      onClick={() => act(() => api.reactivateUser(u.id), t('{name} can sign in again', { name: u.username }))}>{t('Reactivate')}</button>
                  </li>
                ))}
              </ul>
            </section>
          )}
          </>
          )}
          {tab === 'access' && <AccessPanel />}
          {tab === 'system' && (
          <>
            <HealthPanel />
            <SystemPanel />
          </>
          )}
          {tab === 'security' && (
          <>
            <PasswordPolicyPanel />
            <AuditLogPanel />
          </>
          )}
          {tab === 'backups' && (
          <>
          <section className="panel">
            <div className="panel-head">
              <h2 className="panel-title"><Archive size={16} /> {t("Backups")}</h2>
              <button className="btn btn-soft sm" disabled={busy}
                onClick={() => act(() => api.backupNow(), 'Backup created')}>{t("Back up now")}</button>
            </div>
            <p className="muted small hint">A backup of the database and attachments is made every night; the last 14 are kept in the data
              volume. Download one to keep a copy off the server — the README explains how to restore it.</p>
            {!backups ? <Spinner /> : backups.length === 0 ? <p className="muted">{t("No backups yet.")}</p> : (
              <ul className="mini-list">
                {backups.map((backup) => (
                  <li key={backup.name}>
                    <div className="invite-text">
                      <strong className="mono small">{backup.name}</strong>
                      <span className="muted small">{formatDate(backup.createdAt)} · {fileSize(backup.size)}</span>
                    </div>
                    <button className="icon-button sm" aria-label={`Download ${backup.name}`} title={t("Download")}
                      onClick={async () => {
                        try {
                          saveBlob(await api.backupBlob(backup.name), backup.name);
                        } catch (e) {
                          toast((e as ApiError).message, 'error');
                        }
                      }}>
                      <Download size={15} />
                    </button>
                    {restoreStatus?.supported && (
                      <button className="icon-button sm" aria-label={t('Restore {name}', { name: backup.name })} title={t('Restore')}
                        onClick={() => {
                          setRestoring(backup.name);
                          setRestorePassword('');
                          setRestoreError('');
                        }}>
                        <History size={15} />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {restoreStatus && !restoreStatus.supported && restoreStatus.reason && <p className="muted small">{restoreStatus.reason}</p>}
            {restoreStatus && restoreStatus.lastResult.length > 0 && (
              <p className={restoreStatus.lastResult[0] === 'ok' ? 'muted small' : 'small field-error'}>
                {restoreStatus.lastResult[0] === 'ok'
                  ? t('Last restore: {name} at {when}', { name: restoreStatus.lastResult[1] ?? '', when: restoreStatus.lastResult[2] ?? '' })
                  : t('The last restore failed: {why}', { why: restoreStatus.lastResult[3] ?? '' })}
              </p>
            )}
          </section>
          <DataPanel />
          </>
          )}
        </>
      )}
      {restoring && (
        <Modal title={t('Restore {name}?', { name: restoring })} onClose={() => setRestoring(null)} footer={
          <>
            <button className="btn btn-ghost" onClick={() => setRestoring(null)}>{t('Cancel')}</button>
            <button className="btn btn-danger" disabled={busy || !restorePassword} onClick={async () => {
              setBusy(true);
              setRestoreError('');
              try {
                const result = await api.restoreBackup(restoring, restorePassword);
                toast(result.message);
                setRestoring(null);
              } catch (e) {
                setRestoreError((e as ApiError).message);
              } finally {
                setBusy(false);
              }
            }}>{t('Restore and restart')}</button>
          </>
        }>
          <p>{t('Everything changed since this backup — tasks, comments, files, accounts — is replaced by the backup. A backup of the current state is made first, and the old data is kept on the server.')}</p>
          <p className="muted small">{t('The server restarts; this takes about a minute. Everyone is signed out.')}</p>
          <label className="field">
            <span>{t('Your password')}</span>
            <input type="password" autoComplete="current-password" value={restorePassword} onChange={(e) => setRestorePassword(e.target.value)} />
          </label>
          {restoreError && <div className="alert" role="alert">{restoreError}</div>}
        </Modal>
      )}
      {confirm?.kind === 'delete' && (
        <ConfirmDialog title={`Delete ${confirm.user.username}'s account?`} confirmLabel={t("Delete account")} danger busy={busy}
          message={t("Projects they own alone are deleted, they leave other projects, and their personal data is erased. Comments and history stay as “Deleted user”. This cannot be undone.")}
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await act(() => api.deleteUserAccount(confirm.user.id), `${confirm.user.username}'s account was deleted`);
            setConfirm(null);
          }} />
      )}
      {confirm?.kind === 'reset2fa' && (
        <ConfirmDialog title={`Turn off two-step verification for ${confirm.user.username}?`} confirmLabel={t("Turn off")} busy={busy}
          message={t("Use this when they lost their phone and recovery codes. They can sign in with their password alone and set it up again.")}
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await act(() => api.resetTwoFactor(confirm.user.id), 'Two-step verification turned off');
            setConfirm(null);
          }} />
      )}
    </div>
  );
}
