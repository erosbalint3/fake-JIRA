import { useEffect, useState } from 'react';
import { api, type PasswordRules } from './api';
import { t } from './i18n';

export interface PasswordRule {
  label: string;
  test: (password: string) => boolean;
}

const DEFAULT: PasswordRules = { minLength: 8, upper: true, digit: true, special: true };
let cached: PasswordRules | null = null;

function toRules(policy: PasswordRules): PasswordRule[] {
  const rules: PasswordRule[] = [
    { label: t('At least {n} characters', { n: policy.minLength }), test: (p) => p.length >= policy.minLength },
  ];
  if (policy.upper) rules.push({ label: t('An uppercase letter'), test: (p) => /\p{Lu}/u.test(p) });
  if (policy.digit) rules.push({ label: t('A number'), test: (p) => /\d/.test(p) });
  if (policy.special) rules.push({ label: t('A special character'), test: (p) => /[^\p{L}\p{N}]/u.test(p) });
  return rules;
}

/** The server's password rules (set by admins), as checks for live feedback in forms. */
export function usePasswordRules(): PasswordRule[] {
  const [policy, setPolicy] = useState<PasswordRules>(cached ?? DEFAULT);
  useEffect(() => {
    if (cached) return;
    api.passwordPolicy().then((p) => {
      cached = p;
      setPolicy(p);
    }).catch(() => {});
  }, []);
  return toRules(policy);
}
