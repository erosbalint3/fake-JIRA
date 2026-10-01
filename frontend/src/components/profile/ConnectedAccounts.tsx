import { useEffect, useState } from 'react';
import { Link2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { t } from '../../i18n';

/** Connect or disconnect Google/GitHub sign-in (only providers the server has set up). */
export function ConnectedAccounts({ linked, passwordSet, onChange }: {
  linked: string[];
  passwordSet: boolean;
  onChange: () => void;
}) {
  const toast = useToast();
  const [providers, setProviders] = useState<{ id: string; label: string }[]>([]);

  useEffect(() => {
    api.providers().then(setProviders).catch(() => {});
  }, []);

  const shown = providers.filter((p) => !linked.includes(p.id)).concat(
    linked.map((id) => providers.find((p) => p.id === id) ?? { id, label: id === 'github' ? 'GitHub' : 'Google' }));
  if (shown.length === 0) return null;

  return (
    <section className="panel">
      <h2 className="panel-title"><Link2 size={16} /> {t('Connected accounts')}</h2>
      <p className="muted small hint">{t('Sign in with Google or GitHub instead of your password.')}</p>
      <ul className="mini-list">
        {shown.sort((a, b) => a.label.localeCompare(b.label)).map((p) => {
          const isLinked = linked.includes(p.id);
          return (
            <li key={p.id}>
              <div className="invite-text">
                <strong>{p.label}</strong>
                <span className="muted small">{isLinked ? t('Connected') : t('Not connected')}</span>
              </div>
              {isLinked ? (
                <button className="btn btn-ghost btn-sm" disabled={!passwordSet && linked.length === 1}
                  title={!passwordSet && linked.length === 1 ? t('Set a password first') : undefined}
                  onClick={async () => {
                    try {
                      await api.unlinkIdentity(p.id);
                      toast(t('{name} disconnected', { name: p.label }));
                      onChange();
                    } catch (e) {
                      toast((e as ApiError).message, 'error');
                    }
                  }}>{t('Disconnect')}</button>
              ) : (
                <button className="btn btn-soft btn-sm" onClick={async () => {
                  try {
                    window.location.href = (await api.oauthUrl(p.id)).url;
                  } catch (e) {
                    toast((e as ApiError).message, 'error');
                  }
                }}>{t('Connect')}</button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
