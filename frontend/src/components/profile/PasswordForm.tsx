import { useState, type FormEvent } from 'react';
import { CheckCircle2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useAuth } from '../../auth';
import { useToast } from '../../toast';
import { usePasswordRules } from '../../passwordRules';

/** Change (or, for Google/GitHub-only accounts, set) the password. Other devices get signed out. */
export function PasswordForm({ hasPassword, onDone }: { hasPassword: boolean; onDone?: () => void }) {
  const { passwordChanged } = useAuth();
  const toast = useToast();
  const rules = usePasswordRules();
  const [values, setValues] = useState({ current: '', next: '', confirm: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (rules.some((rule) => !rule.test(values.next))) {
      setErrors({ next: 'Password does not meet the requirements' });
      return;
    }
    if (values.next !== values.confirm) {
      setErrors({ confirm: 'Passwords do not match' });
      return;
    }
    setBusy(true);
    setErrors({});
    try {
      await api.changePassword(values.current, values.next);
      setValues({ current: '', next: '', confirm: '' });
      passwordChanged();
      toast(hasPassword ? 'Password updated. Your other devices were signed out.' : 'Password set');
      onDone?.();
    } catch (e) {
      const apiError = e as ApiError;
      setErrors(Object.keys(apiError.fieldErrors).length
        ? { current: apiError.fieldErrors.currentPassword, next: apiError.fieldErrors.newPassword }
        : { current: apiError.message });
    } finally {
      setBusy(false);
    }
  };

  const input = (key: keyof typeof values, label: string, autoComplete: string) => (
    <label className="field">
      <span>{label}</span>
      <input type="password" autoComplete={autoComplete} value={values[key]}
        onChange={(e) => setValues({ ...values, [key]: e.target.value })} aria-invalid={!!errors[key]} />
      {errors[key] && <small className="field-error">{errors[key]}</small>}
    </label>
  );

  return (
    <form className="form narrow" onSubmit={submit}>
      {hasPassword && input('current', 'Current password', 'current-password')}
      {input('next', 'New password', 'new-password')}
      <ul className="rules">
        {rules.map((rule) => (
          <li key={rule.label} className={rule.test(values.next) ? 'ok' : ''}><CheckCircle2 size={14} /> {rule.label}</li>
        ))}
      </ul>
      {input('confirm', 'Confirm new password', 'new-password')}
      <div>
        <button className="btn btn-primary" disabled={busy || (hasPassword && !values.current) || !values.next}>
          {hasPassword ? 'Update password' : 'Set password'}
        </button>
      </div>
    </form>
  );
}
