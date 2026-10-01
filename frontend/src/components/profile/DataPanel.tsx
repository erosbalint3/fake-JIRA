import { useState, type FormEvent } from 'react';
import { Download, Trash2 } from 'lucide-react';
import { api, ApiError, saveBlob } from '../../api';
import { useAuth } from '../../auth';
import { useToast } from '../../toast';
import { Modal } from '../Modal';
import type { Profile } from '../../types';
import { t } from '../../i18n';

/** Download your data, or delete your account. */
export function DataPanel({ profile }: { profile: Profile }) {
  const toast = useToast();
  const { logout } = useAuth();
  const [deleting, setDeleting] = useState(false);

  return (
    <section className="panel">
      <h2 className="panel-title">{t('Your data')}</h2>
      <p className="muted small hint">
        {t('Download everything FakeJIRA stores about you (profile, projects, tasks, comments, time logs, notifications) as JSON.')}
      </p>
      <div className="button-row">
        <button className="btn btn-soft" onClick={async () => {
          try {
            saveBlob(await api.exportData(), `fakejira-${profile.user.username}.json`);
          } catch (e) {
            toast((e as ApiError).message, 'error');
          }
        }}><Download size={16} /> {t('Download my data')}</button>
        <button className="btn btn-ghost danger" onClick={() => setDeleting(true)}><Trash2 size={16} /> {t('Delete account')}</button>
      </div>
      {deleting && <DeleteDialog profile={profile} onClose={() => setDeleting(false)} onDeleted={() => {
        toast(t('Your account was deleted'));
        logout();
      }} />}
    </section>
  );
}

function DeleteDialog({ profile, onClose, onDeleted }: { profile: Profile; onClose: () => void; onDeleted: () => void }) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.deleteAccount(password, code.trim());
      onDeleted();
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors.password ?? err.fieldErrors.code ?? err.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={t('Delete your account?')} onClose={onClose}
      footer={<>
        <button type="button" className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button type="submit" form="delete-account" className="btn btn-danger"
          disabled={busy || confirm !== profile.user.username}>{t('Delete forever')}</button>
      </>}>
      <form id="delete-account" className="form" onSubmit={submit}>
        <p className="muted small">
          {t('Projects you own alone are deleted. You leave other projects and your tasks there become unassigned. Your comments and history stay, shown as “Deleted user”. Hand over projects you share first (Project settings → Members). This cannot be undone.')}
        </p>
        {error && <div className="alert">{error}</div>}
        {profile.passwordSet && (
          <label className="field">
            <span>{t('Password')}</span>
            <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
        )}
        {profile.twoFactorEnabled && (
          <label className="field">
            <span>{t('Code from your authenticator app')}</span>
            <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" className="code-input" />
          </label>
        )}
        <label className="field">
          <span>{t('Type {name} to confirm', { name: profile.user.username })}</span>
          <input value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="off" />
        </label>
      </form>
    </Modal>
  );
}
