import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Copy, KeyRound, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { formatDate, timeAgo } from '../../format';
import { Modal } from '../Modal';
import type { ApiTokenInfo } from '../../types';

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
      <h2 className="panel-title"><KeyRound size={17} /> API tokens</h2>
      <p className="muted small hint">
        For scripts and integrations: send <code>Authorization: Bearer &lt;token&gt;</code> to the same <code>/api</code> endpoints the app uses.
        Tokens act as you in your projects, but cannot change account, security or admin settings.
      </p>
      {tokens.length > 0 && (
        <ul className="token-list">
          {tokens.map((t) => (
            <li key={t.id} className={t.expired ? 'expired' : ''}>
              <div>
                <strong>{t.name}</strong> <code className="muted">{t.prefix}…</code>
                <span className={`chip ${t.scope === 'WRITE' ? 'warn' : ''}`}>{t.scope === 'WRITE' ? 'read & write' : 'read only'}</span>
                <div className="muted small">
                  Created {formatDate(t.createdAt)} · {t.lastUsedAt ? `last used ${timeAgo(t.lastUsedAt)}` : 'never used'}
                  {t.expiresAt && <> · {t.expired ? 'expired' : 'expires'} {formatDate(t.expiresAt)}</>}
                </div>
              </div>
              <button className="icon-button" aria-label={`Revoke ${t.name}`} onClick={async () => {
                await api.revokeApiToken(t.id);
                toast(`${t.name} revoked`);
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
            <span>Name</span>
            <input value={name} maxLength={60} placeholder="Release script" onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="field">
            <span>Access</span>
            <select value={scope} onChange={(e) => setScope(e.target.value as 'READ' | 'WRITE')}>
              <option value="READ">Read only</option>
              <option value="WRITE">Read &amp; write</option>
            </select>
          </label>
          <label className="field">
            <span>Expires</span>
            <select value={days} onChange={(e) => setDays(e.target.value)}>
              <option value="30">in 30 days</option>
              <option value="90">in 90 days</option>
              <option value="365">in a year</option>
              <option value="">never</option>
            </select>
          </label>
        </div>
        <div><button className="btn btn-soft" disabled={!name.trim()}>Create token</button></div>
      </form>
      {created && (
        <Modal title="Your new token" onClose={() => setCreated(null)} footer={<button className="btn btn-primary" onClick={() => setCreated(null)}>Done</button>}>
          <p>Copy it now — it will not be shown again.</p>
          <div className="copy-field">
            <input readOnly className="mono" value={created} aria-label="New API token" onFocus={(e) => e.target.select()} />
            <button className="icon-button" aria-label="Copy token" onClick={() => navigator.clipboard.writeText(created)
              .then(() => toast('Token copied')).catch(() => toast('Could not copy', 'error'))}><Copy size={16} /></button>
          </div>
          <pre className="payload small">curl -H "Authorization: Bearer {created.slice(0, 12)}…" {window.location.origin}/api/tasks?scope=MINE</pre>
        </Modal>
      )}
    </section>
  );
}
