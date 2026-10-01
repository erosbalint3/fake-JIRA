import { useEffect, useState, type FormEvent } from 'react';
import { Copy, Globe, KeyRound, LogIn, Users } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { SecurityOverview, SecurityPolicy } from '../../types';
import { t } from '../../i18n';

function CopyField({ label, value }: { label: string; value: string }) {
  const toast = useToast();
  return (
    <div className="copy-field">
      <span className="muted small">{label}</span>
      <code className="mono small">{value}</code>
      <button type="button" className="icon-button sm" aria-label={t('Copy {what}', { what: label })} onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          toast(t('Copied'));
        } catch {
          window.prompt(label, value);
        }
      }}><Copy size={14} /></button>
    </div>
  );
}

/** Company sign-in (SSO, LDAP), SCIM provisioning, session rules and the IP allowlist. */
export function AccessPanel() {
  const toast = useToast();
  const [overview, setOverview] = useState<SecurityOverview | null>(null);
  const [policy, setPolicy] = useState<SecurityPolicy | null>(null);
  const [allowlist, setAllowlist] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [scimToken, setScimToken] = useState<string | null>(null);

  const load = () => api.securityOverview().then((o) => {
    setOverview(o);
    setPolicy(o.policy);
    setAllowlist(o.policy.ipAllowlist.join('\n'));
  }).catch(() => {});
  useEffect(() => {
    load();
  }, []);

  if (!overview || !policy) return null;

  const save = async (event?: FormEvent) => {
    event?.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const saved = await api.saveSecurityPolicy({ ...policy, ipAllowlist: allowlist.split(/[\n,]/).map((s) => s.trim()).filter(Boolean) });
      setPolicy(saved);
      setAllowlist(saved.ipAllowlist.join('\n'));
      toast(t('Access rules saved'));
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors(e.fieldErrors);
        toast(e.message, 'error');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <section className="panel">
        <h2 className="panel-title"><LogIn size={16} /> {t('Company sign-in')}</h2>
        {overview.signIn.length === 0 ? (
          <p className="muted small hint">{t('No single sign-on is set up. Add OpenID Connect, SAML or LDAP settings to the server configuration (see the README), then restart.')}</p>
        ) : (
          <ul className="mini-list">{overview.signIn.map((m) => <li key={m}>{m}</li>)}</ul>
        )}
        <p className="muted small hint">{t('Give these to your identity provider:')}</p>
        <CopyField label={t('SAML metadata')} value={overview.samlMetadataUrl} />
        <CopyField label={t('SAML reply (ACS) URL')} value={overview.samlAcsUrl} />
        <CopyField label={t('OpenID Connect redirect URL')} value={overview.oidcRedirectUrl} />
      </section>

      <section className="panel">
        <h2 className="panel-title"><KeyRound size={16} /> {t('Sign-in rules')}</h2>
        <form className="form" onSubmit={save}>
          <label className="toggle">
            <input type="checkbox" checked={policy.ssoRequired} disabled={!overview.ssoAvailable}
              onChange={(e) => setPolicy({ ...policy, ssoRequired: e.target.checked })} />
            {t('Require single sign-on (admins can still use their password)')}
          </label>
          {errors.ssoRequired && <small className="field-error">{errors.ssoRequired}</small>}
          <div className="policy-grid">
            <label className="inline-field">{t('Sessions last (hours)')}
              <input type="number" min={1} max={2160} className="wip-input" value={policy.sessionHours} aria-invalid={!!errors.sessionHours}
                onChange={(e) => setPolicy({ ...policy, sessionHours: Number(e.target.value) })} />
            </label>
            <label className="inline-field">{t('Sign out after idle (minutes, 0 = never)')}
              <input type="number" min={0} className="wip-input" value={policy.idleMinutes} aria-invalid={!!errors.idleMinutes}
                onChange={(e) => setPolicy({ ...policy, idleMinutes: Number(e.target.value) })} />
            </label>
          </div>
          {(errors.sessionHours || errors.idleMinutes) && <small className="field-error">{errors.sessionHours ?? errors.idleMinutes}</small>}
          <label className="toggle">
            <input type="checkbox" checked={policy.loginAlerts} onChange={(e) => setPolicy({ ...policy, loginAlerts: e.target.checked })} />
            {t('Email people about sign-ins from a new device and repeated wrong passwords')}
          </label>
          <label className="field">
            <span><Globe size={14} aria-hidden /> {t('Allowed networks (one IP address or range per line; empty = anywhere)')}</span>
            <textarea rows={4} className="mono small" value={allowlist} onChange={(e) => setAllowlist(e.target.value)}
              placeholder={'203.0.113.0/24\n2001:db8::/32'} aria-invalid={!!errors.ipAllowlist} />
            <small className="muted">{t('Your address: {ip}. Webhooks, the public portal and SCIM are not affected.', { ip: overview.yourIp })}</small>
            {errors.ipAllowlist && <small className="field-error">{errors.ipAllowlist}</small>}
          </label>
          <div><button className="btn btn-primary" disabled={busy}>{t('Save rules')}</button></div>
        </form>
      </section>

      <section className="panel">
        <h2 className="panel-title"><Users size={16} /> {t('SCIM provisioning')}</h2>
        <p className="muted small hint">{t('Let your identity provider create, update and deactivate accounts and sync groups to teams.')}</p>
        <CopyField label={t('SCIM base URL')} value={overview.scimUrl} />
        {scimToken && (
          <div className="notice">
            <p className="small">{t('Copy the token now; it is not shown again.')}</p>
            <CopyField label={t('SCIM token')} value={scimToken} />
          </div>
        )}
        <div className="row-actions">
          <button className="btn btn-soft sm" onClick={async () => {
            try {
              setScimToken((await api.newScimToken()).token);
              load();
            } catch (e) {
              toast((e as ApiError).message, 'error');
            }
          }}>{overview.scimTokenSet ? t('Replace token') : t('Create token')}</button>
          {overview.scimTokenSet && (
            <button className="btn btn-ghost sm danger" onClick={async () => {
              await api.revokeScimToken();
              setScimToken(null);
              toast(t('SCIM provisioning turned off'));
              load();
            }}>{t('Turn off')}</button>
          )}
        </div>
      </section>
    </>
  );
}
