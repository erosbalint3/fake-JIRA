import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Copy, KeyRound, ListTree, Send, Trash2, Webhook } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';
import { Modal } from '../Modal';
import { Spinner } from '../States';
import type { OutgoingWebhook, WebhookDelivery } from '../../types';

const EVENTS = [
  { value: 'task.created', label: 'Task created' },
  { value: 'task.updated', label: 'Task edited' },
  { value: 'task.status_changed', label: 'Status changed' },
  { value: 'task.assigned', label: 'Assignee changed' },
  { value: 'comment.created', label: 'Comment added' },
  { value: 'task.deleted', label: 'Task deleted' },
];

/** Outgoing webhooks: signed JSON POSTs for your own services (owner only). */
export function WebhooksSection({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [hooks, setHooks] = useState<OutgoingWebhook[] | null>(null);
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [secret, setSecret] = useState<{ url: string; secret: string } | null>(null);
  const [log, setLog] = useState<OutgoingWebhook | null>(null);

  const load = useCallback(() => {
    api.webhooks(projectKey).then(setHooks).catch(() => setHooks([]));
  }, [projectKey]);
  useEffect(load, [load]);

  const add = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    try {
      const created = await api.createWebhook(projectKey, url.trim(), events);
      setUrl('');
      setEvents([]);
      setSecret({ url: created.url, secret: created.secret ?? '' });
      load();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  const run = async (action: () => Promise<unknown>, message: string) => {
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><Webhook size={16} /> Outgoing webhooks</h2>
      <p className="muted small hint">
        FakeJIRA POSTs JSON to your URL when tasks change. Each request carries <code>X-FakeJIRA-Event</code> and
        <code> X-FakeJIRA-Signature: sha256=…</code> (HMAC of the body with the webhook's secret). Failed deliveries are retried twice.
      </p>
      {!hooks ? <Spinner /> : hooks.length > 0 && (
        <ul className="hook-list">
          {hooks.map((h) => (
            <li key={h.id}>
              <label className="switch" title={h.enabled ? 'On' : 'Paused'}>
                <input type="checkbox" checked={h.enabled} aria-label={`Webhook ${h.url} enabled`}
                  onChange={() => run(() => api.updateWebhook(h.id, h.url, h.events, !h.enabled), h.enabled ? 'Webhook paused' : 'Webhook on')} />
                <span />
              </label>
              <div className="hook-main">
                <code className="hook-url">{h.url}</code>
                <span className="muted small">
                  {h.events.length ? h.events.join(', ') : 'all events'}
                  {h.lastDeliveryAt && <> · last {timeAgo(h.lastDeliveryAt)}{' '}
                    <span className={h.lastError ? 'overdue-text' : 'ok-text'}>{h.lastError ?? `HTTP ${h.lastStatus}`}</span></>}
                </span>
              </div>
              <button className="icon-button" title="Send a test ping" aria-label={`Test ${h.url}`}
                onClick={async () => {
                  try {
                    const d = await api.testWebhook(h.id);
                    toast(d.error ? `Ping failed: ${d.error}` : `Ping delivered (HTTP ${d.status})`, d.error ? 'error' : 'success');
                    load();
                  } catch (e) {
                    toast((e as ApiError).message, 'error');
                  }
                }}><Send size={16} /></button>
              <button className="icon-button" title="Recent deliveries" aria-label={`Deliveries of ${h.url}`} onClick={() => setLog(h)}><ListTree size={16} /></button>
              <button className="icon-button" title="New secret" aria-label={`New secret for ${h.url}`}
                onClick={async () => {
                  const r = await api.rotateWebhookSecret(h.id);
                  setSecret({ url: r.url, secret: r.secret ?? '' });
                }}><KeyRound size={16} /></button>
              <button className="icon-button" aria-label={`Delete ${h.url}`} onClick={() => run(() => api.deleteWebhook(h.id), 'Webhook deleted')}>
                <Trash2 size={16} /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="form hook-form" onSubmit={add}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>Payload URL</span>
          <input type="url" placeholder="https://example.com/fakejira-hook" value={url} onChange={(e) => setUrl(e.target.value)} />
        </label>
        <fieldset className="field event-picks">
          <legend>Events <span className="muted">(none ticked = all)</span></legend>
          {EVENTS.map((ev) => (
            <label key={ev.value} className="toggle small">
              <input type="checkbox" checked={events.includes(ev.value)}
                onChange={(e) => setEvents(e.target.checked ? [...events, ev.value] : events.filter((x) => x !== ev.value))} />
              {ev.label}
            </label>
          ))}
        </fieldset>
        <div><button className="btn btn-soft" disabled={!url.trim()}>Add webhook</button></div>
      </form>
      {secret && (
        <Modal title="Webhook secret" onClose={() => setSecret(null)} footer={<button className="btn btn-primary" onClick={() => setSecret(null)}>Done</button>}>
          <p>Use this secret to verify <code>X-FakeJIRA-Signature</code> on requests to <code>{secret.url}</code>. It is shown only now.</p>
          <div className="copy-field">
            <input readOnly className="mono" value={secret.secret} aria-label="Webhook secret" onFocus={(e) => e.target.select()} />
            <button className="icon-button" aria-label="Copy secret" onClick={() => navigator.clipboard.writeText(secret.secret)
              .then(() => toast('Secret copied')).catch(() => toast('Could not copy', 'error'))}><Copy size={16} /></button>
          </div>
        </Modal>
      )}
      {log && <DeliveryLog hook={log} onClose={() => setLog(null)} />}
    </section>
  );
}

function DeliveryLog({ hook, onClose }: { hook: OutgoingWebhook; onClose: () => void }) {
  const [items, setItems] = useState<WebhookDelivery[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  useEffect(() => {
    api.webhookDeliveries(hook.id).then(setItems).catch(() => setItems([]));
  }, [hook.id]);
  return (
    <Modal title="Recent deliveries" onClose={onClose} footer={<button className="btn btn-ghost" onClick={onClose}>Close</button>}>
      {!items ? <Spinner /> : items.length === 0 ? <p className="muted">Nothing sent yet.</p> : (
        <ul className="run-list">
          {items.map((d) => (
            <li key={d.id} className={d.error ? 'failed' : ''}>
              <span className={`run-dot ${d.error ? 'bad' : 'ok'}`} />
              <code>{d.event}</code>
              <span className="run-message">{d.error ?? `HTTP ${d.status}`} · {d.durationMs} ms{d.attempts > 1 ? ` · ${d.attempts} attempts` : ''}</span>
              <span className="muted small">{timeAgo(d.sentAt)}</span>
              <button className="link small" onClick={() => setOpen(open === d.id ? null : d.id)}>{open === d.id ? 'Hide' : 'Payload'}</button>
              {open === d.id && <pre className="payload">{JSON.stringify(JSON.parse(d.payload), null, 2)}</pre>}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
