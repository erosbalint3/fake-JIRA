import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Copy, KeyRound, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { formatDate, timeAgo } from '../../format';
import { Modal } from '../Modal';
import type { ApiTokenInfo } from '../../types';
import { t } from '../../i18n';

/** Personal access tokens for scripts: "Authorization: Bearer fjt_…". */
export function ApiTokensPanel() {
  const toast = useToast();
  const [tokens, setTokens] = useState<ApiTokenInfo[]>([]);
  const [name, setName] = useState('');
  const [scope, setScope] = useState<'READ' | 'WRITE'>('READ');
  const [days, setDays] = useState('90');
  const [created, setCreated] = useState<string | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.apiTokens().then(setTokens).catch(() => setTokens([]));
  }, []);
  useEffect(load, [load]);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    try {
      const token = await api.createApiToken(name.trim(), scope, days ? Number(days) : null);
      setCreated(token.token);
      setName('');
      load();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><KeyRound size={17} /> {t('API tokens')}</h2>
      <p className="muted small hint">
        {t('For scripts and integrations: send {header} to the same /api endpoints the app uses. Tokens act as you in your projects, but cannot change account, security or admin settings.', { header: 'Authorization: Bearer <token>' })}
      </p>
      {tokens.length > 0 && (
        <ul className="token-list">
          {tokens.map((tok) => (
            <li key={tok.id} className={tok.expired ? 'expired' : ''}>
              <div>
                <strong>{tok.name}</strong> <code className="muted">{tok.prefix}…</code>
                <span className={`chip ${tok.scope === 'WRITE' ? 'warn' : ''}`}>{tok.scope === 'WRITE' ? t('read & write') : t('read only')}</span>
                <div className="muted small">
                  {t('Created {date}', { date: formatDate(tok.createdAt) })} · {tok.lastUsedAt ? t('last used {when}', { when: timeAgo(tok.lastUsedAt) }) : t('never used')}
                  {tok.expiresAt && <> · {t(tok.expired ? 'expired {date}' : 'expires {date}', { date: formatDate(tok.expiresAt) })}</>}
                </div>
              </div>
              <button className="icon-button" aria-label={t('Revoke {name}', { name: tok.name })} onClick={async () => {
                await api.revokeApiToken(tok.id);
                toast(t('{name} revoked', { name: tok.name }));
                load();
              }}><Trash2 size={16} /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="form narrow" onSubmit={create}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid three">
          <label className="field">
            <span>{t('Name')}</span>
            <input value={name} maxLength={60} placeholder={t('Release script')} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Access')}</span>
            <select value={scope} onChange={(e) => setScope(e.target.value as 'READ' | 'WRITE')}>
              <option value="READ">{t('Read only')}</option>
              <option value="WRITE">{t('Read & write')}</option>
            </select>
          </label>
          <label className="field">
            <span>{t('Expires')}</span>
            <select value={days} onChange={(e) => setDays(e.target.value)}>
              <option value="30">{t('in {n} days', { n: 30 })}</option>
              <option value="90">{t('in {n} days', { n: 90 })}</option>
              <option value="365">{t('in a year')}</option>
              <option value="">{t('never')}</option>
            </select>
          </label>
        </div>
        <div><button className="btn btn-soft" disabled={!name.trim()}>{t('Create token')}</button></div>
      </form>
      {created && (
        <Modal title={t('Your new token')} onClose={() => setCreated(null)} footer={<button className="btn btn-primary" onClick={() => setCreated(null)}>{t('Done')}</button>}>
          <p>{t('Copy it now — it will not be shown again.')}</p>
          <div className="copy-field">
            <input readOnly className="mono" value={created} aria-label={t('New API token')} onFocus={(e) => e.target.select()} />
            <button className="icon-button" aria-label={t('Copy token')} onClick={() => navigator.clipboard.writeText(created)
              .then(() => toast(t('Token copied'))).catch(() => toast(t('Could not copy'), 'error'))}><Copy size={16} /></button>
          </div>
          <pre className="payload small">curl -H "Authorization: Bearer {created.slice(0, 12)}…" {window.location.origin}/api/tasks?scope=MINE</pre>
        </Modal>
      )}
    </section>
  );
}
