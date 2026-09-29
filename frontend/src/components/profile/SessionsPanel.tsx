import { useCallback, useEffect, useState } from 'react';
import { Laptop, LogOut, Smartphone } from 'lucide-react';
import { api, ApiError, type SessionInfo } from '../../api';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';

/** "Chrome on Windows" from a user-agent string. */
export function describeDevice(agent: string | null) {
  if (!agent) return { name: 'Unknown device', mobile: false };
  const browser = /Edg\//.test(agent) ? 'Edge' : /OPR\//.test(agent) ? 'Opera' : /Firefox\//.test(agent) ? 'Firefox'
    : /Chrome\//.test(agent) ? 'Chrome' : /Safari\//.test(agent) ? 'Safari' : /curl|python|okhttp|Java/i.test(agent) ? 'Script' : 'Browser';
  const os = /iPhone|iPad/.test(agent) ? 'iOS' : /Android/.test(agent) ? 'Android' : /Windows/.test(agent) ? 'Windows'
    : /Mac OS X/.test(agent) ? 'macOS' : /Linux/.test(agent) ? 'Linux' : '';
  return { name: os ? `${browser} on ${os}` : browser, mobile: /Mobile|iPhone|Android/.test(agent) };
}

const METHOD: Record<string, string> = { password: 'password', google: 'Google', github: 'GitHub' };

/** Devices signed in to this account; any can be signed out. */
export function SessionsPanel() {
  const toast = useToast();
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);

  const load = useCallback(() => {
    api.sessions().then(setSessions).catch(() => setSessions([]));
  }, []);
  useEffect(load, [load]);

  const others = (sessions ?? []).filter((s) => !s.current).length;

  return (
    <section className="panel">
      <div className="panel-head">
        <h2 className="panel-title"><Laptop size={16} /> Where you're signed in</h2>
        {others > 0 && (
          <button className="btn btn-ghost btn-sm" onClick={async () => {
            const { signedOut } = await api.signOutOthers();
            toast(`Signed out ${signedOut} other device${signedOut === 1 ? '' : 's'}`);
            load();
          }}>Sign out all others</button>
        )}
      </div>
      {!sessions ? null : (
        <ul className="mini-list session-list">
          {sessions.map((s) => {
            const device = describeDevice(s.device);
            const Icon = device.mobile ? Smartphone : Laptop;
            return (
              <li key={s.id}>
                <Icon size={18} className="muted" aria-hidden />
                <div className="invite-text">
                  <strong>{device.name}{s.current && <span className="current-badge">This device</span>}</strong>
                  <span className="muted small">
                    {s.ip ?? 'unknown IP'} · via {METHOD[s.method] ?? s.method} · active {timeAgo(s.lastSeenAt)} · signed in {timeAgo(s.createdAt)}
                  </span>
                </div>
                {!s.current && (
                  <button className="icon-button sm" aria-label={`Sign out ${device.name}`} title="Sign out"
                    onClick={async () => {
                      try {
                        await api.revokeSession(s.id);
                        load();
                      } catch (e) {
                        toast((e as ApiError).message, 'error');
                      }
                    }}>
                    <LogOut size={15} />
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
