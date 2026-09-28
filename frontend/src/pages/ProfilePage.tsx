import { useEffect, useState, type FormEvent } from 'react';
import { CheckCircle2, Clock, ListChecks, Mail, PenSquare } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { ErrorBanner, Spinner } from '../components/States';
import { formatDate } from '../format';
import type { Profile } from '../types';

export function ProfilePage() {
  const { logout } = useAuth();
  const toast = useToast();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState('');
  const [passwords, setPasswords] = useState({ current: '', next: '', confirm: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.profile().then(setProfile).catch((e: ApiError) => setError(e.message));
  }, []);

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    if (passwords.next !== passwords.confirm) {
      setErrors({ confirm: 'Passwords do not match' });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      await api.changePassword(passwords.current, passwords.next);
      setPasswords({ current: '', next: '', confirm: '' });
      toast('Password updated');
    } catch (e) {
      const apiError = e as ApiError;
      setErrors(Object.keys(apiError.fieldErrors).length
        ? { current: apiError.fieldErrors.currentPassword, next: apiError.fieldErrors.newPassword }
        : { current: apiError.message });
    } finally {
      setBusy(false);
    }
  };

  if (error) return <div className="page"><ErrorBanner message={error} /></div>;
  if (!profile) return <div className="page"><Spinner /></div>;

  const stats = [
    { label: 'Assigned to me', value: profile.stats.assigned, icon: ListChecks },
    { label: 'In progress', value: profile.stats.inProgress, icon: Clock },
    { label: 'Completed', value: profile.stats.done, icon: CheckCircle2 },
    { label: 'Reported', value: profile.stats.reported, icon: PenSquare },
  ];

  const input = (key: keyof typeof passwords, label: string, autoComplete: string) => (
    <label className="field">
      <span>{label}</span>
      <input
        type="password"
        autoComplete={autoComplete}
        value={passwords[key]}
        onChange={(e) => setPasswords({ ...passwords, [key]: e.target.value })}
        aria-invalid={!!errors[key]}
      />
      {errors[key] && <small className="field-error">{errors[key]}</small>}
    </label>
  );

  return (
    <div className="page">
      <section className="profile-hero panel">
        <Avatar name={profile.user.username} size={72} />
        <div>
          <h1>{profile.user.username}</h1>
          <p className="muted">{profile.user.email}</p>
          <p className="muted small">Member since {formatDate(profile.memberSince)}</p>
        </div>
      </section>

      <div className="stats">
        {stats.map(({ label, value, icon: Icon }) => (
          <div key={label} className="stat panel">
            <Icon size={20} />
            <strong>{value}</strong>
            <span className="muted">{label}</span>
          </div>
        ))}
      </div>

      <section className="panel">
        <h2 className="panel-title"><Mail size={16} /> Email notifications</h2>
        <label className="toggle">
          <input
            type="checkbox"
            checked={profile.emailNotifications}
            disabled={!profile.emailAvailable || busy}
            onChange={async (e) => {
              const enabled = e.target.checked;
              try {
                setProfile(await api.updateSettings(enabled));
                toast(enabled ? 'Email notifications on' : 'Email notifications off');
              } catch (err) {
                toast((err as ApiError).message, 'error');
              }
            }}
          />
          Email me when someone assigns, mentions or updates my tasks
        </label>
        {!profile.emailAvailable && (
          <p className="muted small hint">Email is not set up on this server yet. Ask the administrator to configure SMTP.</p>
        )}
      </section>

      <section className="panel">
        <h2 className="panel-title">Change password</h2>
        <form className="form narrow" onSubmit={changePassword}>
          {input('current', 'Current password', 'current-password')}
          {input('next', 'New password', 'new-password')}
          {input('confirm', 'Confirm new password', 'new-password')}
          <div>
            <button className="btn btn-primary" disabled={busy || !passwords.current || !passwords.next}>
              Update password
            </button>
          </div>
        </form>
      </section>

      <section className="panel danger-zone">
        <div>
          <h2 className="panel-title">Sign out</h2>
          <p className="muted">End your session on this device.</p>
        </div>
        <button className="btn btn-ghost danger" onClick={logout}>Log out</button>
      </section>
    </div>
  );
}
