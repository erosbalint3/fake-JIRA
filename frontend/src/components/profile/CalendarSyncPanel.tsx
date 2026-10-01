import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CalendarSync, Code2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';
import type { CalendarStatus } from '../../types';
import { t } from '../../i18n';

const MESSAGES: Record<string, [string, 'success' | 'error']> = {
  connected: ['Google Calendar connected', 'success'],
  denied: ['Google Calendar was not connected (access was denied).', 'error'],
  expired: ['That connection attempt expired; please try again.', 'error'],
  failed: ['Google did not return a refresh token; please try again.', 'error'],
};

/** Two-way sync of your tasks' due dates with Google Calendar. */
export function CalendarSyncPanel() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [status, setStatus] = useState<CalendarStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => {
    api.calendarStatus().then(setStatus).catch(() => setStatus(null));
  }, []);
  useEffect(load, [load]);

  useEffect(() => {
    const result = params.get('calendar');
    if (result && MESSAGES[result]) {
      toast(t(MESSAGES[result][0]), MESSAGES[result][1]);
      params.delete('calendar');
      setParams(params, { replace: true });
      document.getElementById('calendar')?.scrollIntoView({ block: 'start' });
    }
  }, [params, setParams, toast]);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    try {
      await action();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
      load();
    }
  };

  if (!status) return null;
  return (
    <section className="panel" id="calendar">
      <h2 className="panel-title"><CalendarSync size={16} aria-hidden /> {t('Google Calendar')}</h2>
      {!status.available ? (
        <p className="muted small hint">{t('Google Calendar sync is not set up on this server (the administrator needs to add Google client credentials).')}</p>
      ) : !status.connected ? (
        <>
          <p className="muted small hint">
            {t('Your open tasks with a due date appear as all-day events. Move an event in Google Calendar and the task’s due date follows.')}
          </p>
          <button className="btn btn-soft" disabled={busy} onClick={() => run(async () => {
            const { url } = await api.connectCalendar();
            window.location.assign(url);
          })}>{t('Connect Google Calendar')}</button>
        </>
      ) : (
        <>
          <p className="small">
            {t('Connected · {n} tasks in your calendar', { n: status.events })}
            {status.lastSyncAt && <span className="muted"> · {t('last synced {when}', { when: timeAgo(status.lastSyncAt) })}</span>}
          </p>
          {status.lastError && <div className="alert" role="alert">{status.lastError}</div>}
          <div className="button-row">
            <button className="btn btn-soft" disabled={busy} onClick={() => run(async () => {
              const r = await api.syncCalendar();
              toast(t('Synced: {c} added, {u} updated, {r} removed, {p} dates taken from the calendar',
                { c: r.created, u: r.updated, r: r.removed, p: r.pulled }));
            })}>{t('Sync now')}</button>
            <button className="btn btn-ghost danger" disabled={busy} onClick={() => run(async () => {
              await api.disconnectCalendar();
              toast(t('Google Calendar disconnected'));
            })}>{t('Disconnect')}</button>
          </div>
        </>
      )}
    </section>
  );
}

/** Where to find the API documentation, the command line tool and automation hooks. */
export function DeveloperPanel() {
  const origin = window.location.origin;
  return (
    <section className="panel" id="developers">
      <h2 className="panel-title"><Code2 size={16} aria-hidden /> {t('API, CLI and automation')}</h2>
      <ul className="plain-list developer-links">
        <li>
          <a href="/api-docs" target="_blank" rel="noreferrer">{t('API reference')}</a>
          <span className="muted small"> — {t('every endpoint, with “Try it out” (use an API token from below)')}</span>
        </li>
        <li>
          <a href="/cli/fakejira" download="fakejira">{t('Command line tool')}</a>
          <span className="muted small"> — {t('Python 3, nothing else to install')}</span>
          <pre className="code-block">{`curl -o fakejira ${origin}/cli/fakejira && chmod +x fakejira
./fakejira login --url ${origin} --token fj_…
./fakejira list
./fakejira move WEB-12 done`}</pre>
        </li>
        <li>
          <strong>Zapier, n8n, Make</strong>
          <span className="muted small"> — {t('subscribe with POST /api/rest-hooks {projectKey, event, targetUrl}; events: task.created, task.updated, task.status_changed, task.assigned, comment.created, task.deleted')}</span>
        </li>
      </ul>
    </section>
  );
}
