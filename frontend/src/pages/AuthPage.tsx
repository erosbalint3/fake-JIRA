import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { CheckCircle2, KanbanSquare, Bell, Users } from 'lucide-react';
import { ApiError } from '../api';
import { useAuth } from '../auth';
import { Logo } from '../components/Logo';

type Mode = 'login' | 'register';

const PASSWORD_RULES: [RegExp, string][] = [
  [/.{8,}/, 'At least 8 characters'],
  [/[A-Z]/, 'An uppercase letter'],
  [/\d/, 'A number'],
  [/[^A-Za-z0-9]/, 'A special character'],
];

export function AuthPage({ mode }: { mode: Mode }) {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [fields, setFields] = useState({ login: '', username: '', email: '', password: '', confirm: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof fields) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setFields({ ...fields, [key]: e.target.value });

  const validate = (): Record<string, string> => {
    const found: Record<string, string> = {};
    if (mode === 'login') {
      if (!fields.login.trim()) found.login = 'Enter your username or email';
      if (!fields.password) found.password = 'Enter your password';
      return found;
    }
    if (fields.username.trim().length < 4) found.username = 'Username must be at least 4 characters long';
    if (!/^\S+@\S+\.\S+$/.test(fields.email.trim())) found.email = 'Enter a valid email address';
    if (PASSWORD_RULES.some(([rule]) => !rule.test(fields.password))) found.password = 'Password does not meet the requirements';
    if (fields.confirm !== fields.password) found.confirm = 'Passwords do not match';
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found = validate();
    setErrors(found);
    setFormError('');
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      if (mode === 'login') await login(fields.login.trim(), fields.password);
      else await register(fields.username.trim(), fields.email.trim(), fields.password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/backlog', { replace: true });
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors);
        setFormError(error.message);
      }
      setBusy(false);
    }
  };

  const field = (key: keyof typeof fields, label: string, type = 'text', autoComplete?: string) => (
    <label className="field">
      <span>{label}</span>
      <input
        type={type}
        value={fields[key]}
        onChange={set(key)}
        autoComplete={autoComplete}
        aria-invalid={!!errors[key]}
      />
      {errors[key] && <small className="field-error">{errors[key]}</small>}
    </label>
  );

  return (
    <div className="auth">
      <section className="auth-hero">
        <Logo />
        <div>
          <h1>Plan, pick up and ship work — together.</h1>
          <p>A lightweight task tracker for small teams. Post tasks, grab what you want to work on and move it across your board.</p>
          <ul className="auth-features">
            <li><CheckCircle2 size={18} /> Shared backlog of available tasks</li>
            <li><KanbanSquare size={18} /> Personal drag-and-drop board</li>
            <li><Bell size={18} /> Notifications when your tasks change</li>
            <li><Users size={18} /> Comments for quick collaboration</li>
          </ul>
        </div>
        <span className="auth-foot">FakeJIRA 2.0</span>
      </section>

      <section className="auth-panel">
        <form className="auth-card form" onSubmit={submit} noValidate>
          <div>
            <h2>{mode === 'login' ? 'Welcome back' : 'Create your account'}</h2>
            <p className="muted">
              {mode === 'login' ? 'Sign in to continue to your workspace.' : 'It takes less than a minute.'}
            </p>
          </div>

          {formError && !Object.keys(errors).length && <div className="alert">{formError}</div>}

          {mode === 'login' ? (
            <>
              {field('login', 'Username or email', 'text', 'username')}
              {field('password', 'Password', 'password', 'current-password')}
            </>
          ) : (
            <>
              {field('username', 'Username', 'text', 'username')}
              {field('email', 'Email', 'email', 'email')}
              {field('password', 'Password', 'password', 'new-password')}
              <ul className="rules">
                {PASSWORD_RULES.map(([rule, text]) => (
                  <li key={text} className={rule.test(fields.password) ? 'ok' : ''}>
                    <CheckCircle2 size={14} /> {text}
                  </li>
                ))}
              </ul>
              {field('confirm', 'Confirm password', 'password', 'new-password')}
            </>
          )}

          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </button>

          <p className="muted center">
            {mode === 'login' ? (
              <>New here? <Link to="/register">Create an account</Link></>
            ) : (
              <>Already have an account? <Link to="/login">Sign in</Link></>
            )}
          </p>
        </form>
      </section>
    </div>
  );
}
