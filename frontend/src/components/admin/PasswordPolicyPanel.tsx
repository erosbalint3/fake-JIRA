import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { api, ApiError, type PasswordRules } from '../../api';
import { useToast } from '../../toast';

/** Minimum length and required character kinds for new passwords. */
export function PasswordPolicyPanel() {
  const toast = useToast();
  const [rules, setRules] = useState<PasswordRules | null>(null);

  useEffect(() => {
    api.adminPasswordPolicy().then(setRules).catch(() => {});
  }, []);

  if (!rules) return null;
  const save = async (next: PasswordRules) => {
    setRules(next);
    try {
      setRules(await api.setPasswordPolicy(next));
      toast('Password rules saved');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><KeyRound size={16} /> Password rules</h2>
      <p className="muted small hint">Apply to new passwords. Use “Require password change” on a user to make them pick a new one.</p>
      <div className="policy-grid">
        <label className="inline-field">Minimum length
          <input type="number" min={8} max={64} value={rules.minLength} className="wip-input"
            onChange={(e) => setRules({ ...rules, minLength: Number(e.target.value) })}
            onBlur={() => save({ ...rules, minLength: Math.max(8, Math.min(64, rules.minLength || 8)) })} />
        </label>
        {([['upper', 'Uppercase letter'], ['digit', 'Number'], ['special', 'Special character']] as const).map(([key, label]) => (
          <label key={key} className="toggle">
            <input type="checkbox" checked={rules[key]} onChange={(e) => save({ ...rules, [key]: e.target.checked })} /> {label}
          </label>
        ))}
      </div>
    </section>
  );
}
