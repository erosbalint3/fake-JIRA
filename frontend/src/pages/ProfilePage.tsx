import { useEffect, useRef, useState, type FormEvent } from 'react';
import { BellRing, Camera, CheckCircle2, Clock, ListChecks, Mail, PenSquare, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { currentSubscription, disablePush, enablePush, pushSupported } from '../push';
import { useToast } from '../toast';
import { Avatar } from '../components/Avatar';
import { ErrorBanner, Spinner } from '../components/States';
import { ConnectedAccounts } from '../components/profile/ConnectedAccounts';
import { AwayPanel } from '../components/profile/AwayPanel';
import { DataPanel } from '../components/profile/DataPanel';
import { PasswordForm } from '../components/profile/PasswordForm';
import { SessionsPanel } from '../components/profile/SessionsPanel';
import { TwoFactorPanel, useLinkedToast } from '../components/profile/TwoFactorPanel';
import { useLocation } from 'react-router-dom';
import { formatDate } from '../format';
import { EMAIL_FREQUENCY_LABEL, type EmailFrequency, type Profile } from '../types';

export function ProfilePage() {
  const { logout, updateUser } = useAuth();
  const toast = useToast();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);
  const [pushOn, setPushOn] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const location = useLocation();
  useLinkedToast((location.state as { linked?: string } | null)?.linked);
  const reload = () => {
    api.profile().then(setProfile).catch(() => {});
  };

  useEffect(() => {
    api.profile().then((p) => {
      setProfile(p);
      setDisplayName(p.user.displayName === p.user.username ? '' : p.user.displayName);
    }).catch((e: ApiError) => setError(e.message));
    currentSubscription().then((s) => setPushOn(!!s)).catch(() => {});
  }, []);

  const apply = (next: Profile) => {
    setProfile(next);
    updateUser(next.user);
  };

  const saveName = async (event: FormEvent) => {
    event.preventDefault();
    try {
      apply(await api.updateSettings({ displayName }));
      toast('Display name saved');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const uploadAvatar = async (file: File | undefined) => {
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      toast('Choose an image up to 2 MB', 'error');
      return;
    }
    try {
      const user = await api.uploadAvatar(file);
      updateUser(user);
      setProfile((p) => (p ? { ...p, user } : p));
      toast('Profile picture updated');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const removeAvatar = async () => {
    const user = await api.removeAvatar();
    updateUser(user);
    setProfile((p) => (p ? { ...p, user } : p));
  };

  const togglePush = async () => {
    setBusy(true);
    try {
      if (pushOn) {
        await disablePush();
        setPushOn(false);
        toast('Push notifications turned off for this device');
      } else {
        await enablePush();
        setPushOn(true);
        const result = await api.pushTest();
        toast(result.delivered ? 'Push notifications are on — check for a test notification' : 'Push notifications are on');
      }
      setProfile(await api.profile());
    } catch (e) {
      toast((e as Error).message, 'error');
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

  return (
    <div className="page">
      <section className="profile-hero panel">
        <div className="avatar-edit">
          <Avatar user={profile.user} size={80} />
          <button className="avatar-edit-button" onClick={() => fileRef.current?.click()} aria-label="Change profile picture"
            title="Change profile picture">
            <Camera size={15} />
          </button>
          <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
            onChange={(e) => {
              uploadAvatar(e.target.files?.[0]);
              e.target.value = '';
            }} />
        </div>
        <div className="profile-hero-text">
          <h1>{profile.user.displayName}</h1>
          <p className="muted">@{profile.user.username} · {profile.user.email}</p>
          <p className="muted small">Member since {formatDate(profile.memberSince)}{profile.admin ? ' · Admin' : ''}</p>
          {profile.user.avatarUrl && (
            <button className="link small" onClick={removeAvatar}><Trash2 size={13} /> Remove photo</button>
          )}
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
        <h2 className="panel-title">Display name</h2>
        <form className="inline-form" onSubmit={saveName}>
          <input value={displayName} maxLength={60} placeholder={profile.user.username} aria-label="Display name"
            onChange={(e) => setDisplayName(e.target.value)} />
          <button className="btn btn-soft">Save</button>
        </form>
        <p className="muted small hint">Shown instead of your username. Mentions still use @{profile.user.username}.</p>
      </section>

      <section className="panel">
        <h2 className="panel-title"><Mail size={16} /> Email notifications</h2>
        <div className="inline-form">
          <select value={profile.emailFrequency} disabled={!profile.emailAvailable} aria-label="Email frequency"
            onChange={async (e) => {
              try {
                apply(await api.updateSettings({ emailFrequency: e.target.value as EmailFrequency }));
                toast('Email preference saved');
              } catch (err) {
                toast((err as ApiError).message, 'error');
              }
            }}>
            {(Object.keys(EMAIL_FREQUENCY_LABEL) as EmailFrequency[]).map((f) => (
              <option key={f} value={f}>{EMAIL_FREQUENCY_LABEL[f]}</option>
            ))}
          </select>
        </div>
        <p className="muted small hint">
          {profile.emailAvailable
            ? 'Get an email when someone assigns, mentions or updates your tasks — right away, or bundled into one digest.'
            : 'Email is not set up on this server yet. Ask the administrator to configure SMTP.'}
        </p>
      </section>

      <section className="panel">
        <h2 className="panel-title"><BellRing size={16} /> Push notifications</h2>
        {pushSupported() ? (
          <>
            <label className="toggle">
              <input type="checkbox" checked={pushOn} disabled={busy} onChange={togglePush} />
              Notify me on this device, even when FakeJIRA is closed
            </label>
            <p className="muted small hint">
              {profile.pushDevices > 0 ? `Enabled on ${profile.pushDevices} device${profile.pushDevices === 1 ? '' : 's'}. ` : ''}
              On phones, first add FakeJIRA to your home screen (Share → Add to Home Screen on iOS).
            </p>
          </>
        ) : (
          <p className="muted">This browser does not support push notifications.</p>
        )}
      </section>

      <h2 className="section-heading" id="security">Security</h2>
      <section className="panel">
        <h2 className="panel-title">{profile.passwordSet ? 'Change password' : 'Set a password'}</h2>
        {!profile.passwordSet && (
          <p className="muted small hint">You sign in with Google or GitHub. Set a password to also sign in with your username.</p>
        )}
        <PasswordForm hasPassword={profile.passwordSet} onDone={reload} />
      </section>
      <AwayPanel profile={profile} onChange={reload} />
      <TwoFactorPanel profile={profile} onChange={reload} />
      <ConnectedAccounts linked={profile.identities} passwordSet={profile.passwordSet} onChange={reload} />
      <SessionsPanel />
      <DataPanel profile={profile} />

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
