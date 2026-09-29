import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { CheckCircle2, MailCheck } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { Logo } from '../components/Logo';
import { usePasswordRules } from '../passwordRules';


function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="auth-simple">
      <Link to="/login" className="auth-simple-logo"><Logo /></Link>
      <div className="auth-card panel">{children}</div>
    </div>
  );
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.forgotPassword(email.trim());
      setSent(true);
    } catch (e) {
      const apiError = e as ApiError;
      setError(apiError.fieldErrors.email ?? apiError.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell>
      {sent ? (
        <div className="form center">
          <div className="empty-icon"><MailCheck size={26} /></div>
          <h2>Check your inbox</h2>
          <p className="muted">If an account uses <b>{email}</b>, we sent a link to reset its password. It is valid for one hour.</p>
          <Link to="/login">Back to sign in</Link>
        </div>
      ) : (
        <form className="form" onSubmit={submit} noValidate>
          <div>
            <h2>Forgot your password?</h2>
            <p className="muted">Enter your account email and we will send you a reset link.</p>
          </div>
          {error && <div className="alert">{error}</div>}
          <label className="field">
            <span>Email</span>
            <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <button className="btn btn-primary btn-block" disabled={busy || !email.trim()}>Send reset link</button>
          <p className="muted center"><Link to="/login">Back to sign in</Link></p>
        </form>
      )}
    </Shell>
  );
}

export function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const navigate = useNavigate();
  const rules = usePasswordRules();
  const toast = useToast();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (password !== confirm) {
      setError('Passwords do not match');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api.resetPassword(token, password);
      toast('Password changed. You can sign in now.');
      navigate('/login', { replace: true });
    } catch (e) {
      const apiError = e as ApiError;
      setError(apiError.fieldErrors.newPassword ?? apiError.message);
      setBusy(false);
    }
  };

  if (!token) {
    return (
      <Shell>
        <div className="form center">
          <h2>Invalid link</h2>
          <p className="muted">This reset link is incomplete. <Link to="/forgot-password">Request a new one</Link>.</p>
        </div>
      </Shell>
    );
  }

  return (
    <Shell>
      <form className="form" onSubmit={submit} noValidate>
        <div>
          <h2>Choose a new password</h2>
          <p className="muted">After saving, sign in with your new password.</p>
        </div>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>New password</span>
          <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <ul className="rules">
          {rules.map((rule) => (
            <li key={rule.label} className={rule.test(password) ? 'ok' : ''}><CheckCircle2 size={14} /> {rule.label}</li>
          ))}
        </ul>
        <label className="field">
          <span>Confirm new password</span>
          <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </label>
        <button className="btn btn-primary btn-block" disabled={busy || !password}>Save password</button>
      </form>
    </Shell>
  );
}
