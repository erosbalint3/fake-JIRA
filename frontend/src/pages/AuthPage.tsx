import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { CheckCircle2, Clock, KanbanSquare, Bell, ShieldCheck, Users } from 'lucide-react';
import { api, ApiError } from '../api';
import type { RegistrationMode } from '../types';
import { useAuth } from '../auth';
import { Logo } from '../components/Logo';
import { ProviderButtons } from '../components/ProviderButtons';
import { usePasswordRules } from '../passwordRules';
import { t } from '../i18n';

type Mode = 'login' | 'register';


export function AuthPage({ mode }: { mode: Mode }) {
  const { login, register, verifyCode } = useAuth();
  const location = useLocation();
  const passwordRules = usePasswordRules();
  // Google/GitHub sign-in hands over the 2FA step here.
  const [challenge, setChallenge] = useState<string | null>(
    () => (location.state as { challenge?: string } | null)?.challenge ?? null);
  const [code, setCode] = useState('');
  const navigate = useNavigate();
  const [fields, setFields] = useState({ login: '', username: '', email: '', password: '', confirm: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);
  const [params] = useSearchParams();
  const inviteCode = params.get('invite') ?? '';
  const [info, setInfo] = useState<{ valid: boolean; email: string | null; projectName: string | null;
    registrationMode: RegistrationMode } | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    api.inviteInfo(inviteCode).then((next) => {
      setInfo(next);
      if (next.valid && next.email) setFields((f) => ({ ...f, email: f.email || next.email! }));
    }).catch(() => setInfo(null));
  }, [inviteCode]);

  const inviteOnly = info?.registrationMode === 'INVITE' && !info.valid;

  const set = (key: keyof typeof fields) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setFields({ ...fields, [key]: e.target.value });

  const validate = (): Record<string, string> => {
    const found: Record<string, string> = {};
    if (mode === 'login') {
      if (!fields.login.trim()) found.login = t('Enter your username or email');
      if (!fields.password) found.password = t('Enter your password');
      return found;
    }
    if (fields.username.trim().length < 4) found.username = t('Username must be at least 4 characters long');
    if (!/^\S+@\S+\.\S+$/.test(fields.email.trim())) found.email = t('Enter a valid email address');
    if (passwordRules.some((rule) => !rule.test(fields.password))) found.password = t('Password does not meet the requirements');
    if (fields.confirm !== fields.password) found.confirm = t('Passwords do not match');
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
      if (mode === 'login') {
        const result = await login(fields.login.trim(), fields.password);
        if (result.kind === 'code') {
          setChallenge(result.challenge);
          setBusy(false);
          return;
        }
      } else if (await register(fields.username.trim(), fields.email.trim(), fields.password, inviteCode || undefined)) {
        setPending(true);
        setBusy(false);
        return;
      }
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors);
        setFormError(error.message);
      }
      setBusy(false);
    }
  };

  const submitCode = async (event: FormEvent) => {
    event.preventDefault();
    if (!challenge || !code.trim()) return;
    setBusy(true);
    setFormError('');
    try {
      await verifyCode(challenge, code.trim());
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from && from !== '/login' ? from : '/', { replace: true });
    } catch (error) {
      if (error instanceof ApiError) {
        setFormError(error.fieldErrors.code ?? error.message);
        // An expired or exhausted step means starting over.
        if (error.status === 401) setChallenge(null);
      }
      setBusy(false);
    }
  };

  const field = (key: keyof typeof fields, label: string, type = 'text', autoComplete?: string) => (
    <label className="field">
      <span>{t(label)}</span>
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
          <h1>{t("Plan, pick up and ship work — together.")}</h1>
          <p>{t("A lightweight task tracker for small teams. Post tasks, grab what you want to work on and move it across your board.")}</p>
          <ul className="auth-features">
            <li><Users size={18} /> {t("Projects with their own members and task keys")}</li>
            <li><KanbanSquare size={18} /> {t("Sprints, drag-and-drop boards and burndown charts")}</li>
            <li><CheckCircle2 size={18} /> {t("Checklists, labels, due dates and attachments")}</li>
            <li><Bell size={18} /> {t("Live updates, @mentions and email notifications")}</li>
          </ul>
        </div>
        <span className="auth-foot">{t("FakeJIRA 5.0")}</span>
      </section>

      <section className="auth-panel">
        {pending ? (
          <div className="auth-card form">
            <Clock size={32} className="auth-icon" />
            <h2>{t("Almost there")}</h2>
            <p className="muted">
              {t("Your account was created and is waiting for an administrator to approve it. You can sign in as soon as it's approved.")}
            </p>
            <Link to="/login" className="btn btn-primary btn-block">{t("Back to sign in")}</Link>
          </div>
        ) : challenge ? (
          <form className="auth-card form" onSubmit={submitCode} noValidate>
            <ShieldCheck size={32} className="auth-icon" />
            <div>
              <h2>{t("Two-step verification")}</h2>
              <p className="muted">{t("Enter the 6-digit code from your authenticator app, or one of your recovery codes.")}</p>
            </div>
            {formError && <div className="alert">{formError}</div>}
            <label className="field">
              <span>{t("Code")}</span>
              <input value={code} onChange={(e) => setCode(e.target.value)} autoFocus inputMode="text"
                autoComplete="one-time-code" placeholder="123 456" className="code-input" maxLength={12} />
            </label>
            <button className="btn btn-primary btn-block" disabled={busy || !code.trim()}>
              {busy ? 'Checking…' : 'Verify'}
            </button>
            <button type="button" className="link small center" onClick={() => {
              setChallenge(null);
              setCode('');
              setFormError('');
            }}>{t("Back to sign in")}</button>
          </form>
        ) : mode === 'register' && inviteOnly ? (
          <div className="auth-card form">
            <h2>{t("Sign-up is invite-only")}</h2>
            <p className="muted">
              {inviteCode
                ? t('This invite link is invalid, already used or expired. Ask for a new one.')
                : t('Ask an administrator or a project owner to send you an invite link.')}
            </p>
            <p className="muted center">{t("Already have an account?")} <Link to="/login">{t("Sign in")}</Link></p>
          </div>
        ) : (
        <form className="auth-card form" onSubmit={submit} noValidate>
          <div>
            <h2>{mode === 'login' ? t('Welcome back') : t('Create your account')}</h2>
            <p className="muted">
              {mode === 'login' ? t('Sign in to continue to your workspace.') : t('It takes less than a minute.')}
            </p>
          </div>

          {mode === 'register' && info?.valid && (
            <div className="notice">
              {info.projectName ? t("You've been invited to {project}. Create your account to get started.", { project: info.projectName }) : t("You've been invited. Create your account to get started.")}
            </div>
          )}
          {mode === 'register' && !info?.valid && info?.registrationMode === 'APPROVAL' && (
            <div className="notice">{t("New accounts are reviewed by an administrator before they can sign in.")}</div>
          )}
          {formError && !Object.keys(errors).length && <div className="alert">{formError}</div>}

          {mode === 'login' ? (
            <>
              {field('login', 'Username or email', 'text', 'username')}
              {field('password', 'Password', 'password', 'current-password')}
              <Link to="/forgot-password" className="small forgot-link">{t("Forgot password?")}</Link>
            </>
          ) : (
            <>
              {field('username', 'Username', 'text', 'username')}
              {field('email', 'Email', 'email', 'email')}
              {field('password', 'Password', 'password', 'new-password')}
              <ul className="rules">
                {passwordRules.map((rule) => (
                  <li key={rule.label} className={rule.test(fields.password) ? 'ok' : ''}>
                    <CheckCircle2 size={14} /> {rule.label}
                  </li>
                ))}
              </ul>
              {field('confirm', 'Confirm password', 'password', 'new-password')}
            </>
          )}

          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? t('Please wait…') : mode === 'login' ? t('Sign in') : t('Create account')}
          </button>
          <ProviderButtons invite={inviteCode} onError={setFormError}
            verb={mode === 'login' ? 'Continue' : 'Sign up'} />

          <p className="muted center">
            {mode === 'login' ? (
              info?.registrationMode === 'INVITE'
                ? <>{t('New here? Ask for an invite link to join.')}</>
                : <>{t('New here?')} <Link to="/register">{t("Create an account")}</Link></>
            ) : (
              <>{t('Already have an account?')} <Link to="/login">{t("Sign in")}</Link></>
            )}
          </p>
        </form>
        )}
      </section>
    </div>
  );
}
