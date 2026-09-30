import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CheckCircle2, Map as MapIcon, MessageSquare, ScrollText, Send } from 'lucide-react';
import { ApiError } from '../api';
import { publicApi, type NewPortalRequest } from '../publicApi';
import { Logo } from '../components/Logo';
import { Markdown } from '../components/Markdown';
import { Spinner } from '../components/States';
import { formatDay } from '../format';
import type { FormField, PublicChangelog, PublicPortal, PublicRoadmap, PublicTracking } from '../types';
import { t } from '../i18n';

function PublicShell({ title, children, nav }: { title: string; children: React.ReactNode; nav?: React.ReactNode }) {
  useEffect(() => {
    document.title = title;
  }, [title]);
  return (
    <div className="shared-page public-page">
      <header className="shared-header">
        <Logo />
        {nav && <nav className="public-nav" aria-label={t('Public pages')}>{nav}</nav>}
      </header>
      <main className="shared-main" id="main">{children}</main>
    </div>
  );
}

function PublicLinks({ projectKey, portal, roadmap, changelog }: { projectKey: string; portal: boolean; roadmap: boolean; changelog: boolean }) {
  return (
    <>
      {portal && <Link to={`/portal/${projectKey}`}><MessageSquare size={15} aria-hidden /> {t('Get help')}</Link>}
      {roadmap && <Link to={`/public/${projectKey}/roadmap`}><MapIcon size={15} aria-hidden /> {t('Roadmap')}</Link>}
      {changelog && <Link to={`/public/${projectKey}/changelog`}><ScrollText size={15} aria-hidden /> {t('Changelog')}</Link>}
    </>
  );
}

function ErrorPanel({ message }: { message: string }) {
  return <div className="panel shared-error"><h1>{t('Page unavailable')}</h1><p className="muted">{message}</p></div>;
}

// ---- The request form (portal and widget) -------------------------------------------------------------------------

function FieldInput({ field, value, onChange, error }: {
  field: FormField; value: string | boolean | undefined; onChange: (v: string | boolean) => void; error?: string;
}) {
  const id = `field-${field.id}`;
  const label = <span>{field.label}{field.required && <span className="required" aria-hidden> *</span>}</span>;
  const common = { id, 'aria-invalid': !!error, 'aria-describedby': error ? `${id}-error` : field.help ? `${id}-help` : undefined };
  let control;
  switch (field.kind) {
    case 'textarea':
      control = <textarea {...common} rows={4} value={String(value ?? '')} required={field.required} onChange={(e) => onChange(e.target.value)} />;
      break;
    case 'select':
      control = (
        <select {...common} value={String(value ?? '')} required={field.required} onChange={(e) => onChange(e.target.value)}>
          <option value="">{t('Choose…')}</option>
          {(field.options ?? []).map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      );
      break;
    case 'checkbox':
      return (
        <label className="toggle">
          <input type="checkbox" checked={value === true} onChange={(e) => onChange(e.target.checked)} /> {label}
          {error && <small className="field-error" id={`${id}-error`}>{error}</small>}
        </label>
      );
    default:
      control = <input {...common} type={field.kind === 'number' ? 'number' : field.kind === 'date' ? 'date' : field.kind === 'url' ? 'url' : 'text'}
        value={String(value ?? '')} required={field.required} onChange={(e) => onChange(e.target.value)} />;
  }
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {control}
      {field.help && <small className="muted" id={`${id}-help`}>{field.help}</small>}
      {error && <small className="field-error" id={`${id}-error`}>{error}</small>}
    </div>
  );
}

function RequestForm({ portal, channel, onDone }: {
  portal: PublicPortal; channel: 'portal' | 'widget'; onDone: (result: { token: string; reference: string }) => void;
}) {
  const [typeId, setTypeId] = useState<number | null>(portal.requestTypes[0]?.id ?? null);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [summary, setSummary] = useState('');
  const [description, setDescription] = useState('');
  const [answers, setAnswers] = useState<Record<string, string | boolean>>({});
  const [website, setWebsite] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [similar, setSimilar] = useState<{ title: string; kind: 'roadmap' | 'changelog'; status: string }[]>([]);
  const type = portal.requestTypes.find((r) => r.id === typeId) ?? null;

  useEffect(() => {
    if (summary.trim().length < 6 || (!portal.roadmap && !portal.changelog)) {
      setSimilar([]);
      return;
    }
    const timer = window.setTimeout(() => {
      publicApi.similar(portal.projectKey, summary).then(setSimilar).catch(() => setSimilar([]));
    }, 400);
    return () => window.clearTimeout(timer);
  }, [summary, portal]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    setMessage('');
    const request: NewPortalRequest = { requestTypeId: typeId, name, email, summary, description, answers, channel, website };
    try {
      onDone(await publicApi.submit(portal.projectKey, request));
    } catch (e) {
      const err = e as ApiError;
      setErrors(err.fieldErrors ?? {});
      setMessage(Object.keys(err.fieldErrors ?? {}).length ? t('Please check the highlighted fields.') : err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form portal-form" onSubmit={submit} noValidate>
      {message && <div className="alert" role="alert">{message}</div>}
      {portal.requestTypes.length > 1 && (
        <fieldset className="portal-types">
          <legend>{t('What do you need?')}</legend>
          {portal.requestTypes.map((r) => (
            <label key={r.id} className={`portal-type ${typeId === r.id ? 'active' : ''}`}>
              <input type="radio" name="request-type" checked={typeId === r.id} onChange={() => setTypeId(r.id)} />
              <strong>{r.name}</strong>
              {r.description && <span className="muted small">{r.description}</span>}
            </label>
          ))}
        </fieldset>
      )}
      <div className="form-grid two">
        <label className="field">
          <span>{t('Your name')} <span className="required" aria-hidden>*</span></span>
          <input value={name} maxLength={80} autoComplete="name" onChange={(e) => setName(e.target.value)} aria-invalid={!!errors.name} />
          {errors.name && <small className="field-error">{errors.name}</small>}
        </label>
        <label className="field">
          <span>{t('Email')} <span className="required" aria-hidden>*</span></span>
          <input type="email" value={email} maxLength={254} autoComplete="email" onChange={(e) => setEmail(e.target.value)} aria-invalid={!!errors.email} />
          {errors.email && <small className="field-error">{errors.email}</small>}
        </label>
      </div>
      <label className="field">
        <span>{t('Summary')} <span className="required" aria-hidden>*</span></span>
        <input value={summary} maxLength={120} onChange={(e) => setSummary(e.target.value)} aria-invalid={!!errors.summary} />
        {errors.summary && <small className="field-error">{errors.summary}</small>}
      </label>
      {similar.length > 0 && (
        <div className="alert info similar-public" role="status">
          <strong>{t('This might already be known:')}</strong>
          <ul>
            {similar.map((s) => (
              <li key={s.title}>{s.title} <span className="muted small">
                · {s.kind === 'roadmap' ? t('on the roadmap ({stage})', { stage: t(s.status) }) : t('shipped in {version}', { version: s.status })}
              </span></li>
            ))}
          </ul>
        </div>
      )}
      <label className="field">
        <span>{t('Details')}</span>
        <textarea rows={channel === 'widget' ? 3 : 5} value={description} maxLength={3000} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {type?.fields.map((field) => (
        <FieldInput key={field.id} field={field} value={answers[field.id]} error={errors[`answers.${field.id}`]}
          onChange={(v) => setAnswers({ ...answers, [field.id]: v })} />
      ))}
      <label className="honeypot" aria-hidden>
        Website <input tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} />
      </label>
      <button className="btn btn-primary" disabled={busy}><Send size={16} /> {busy ? t('Sending…') : t('Send request')}</button>
    </form>
  );
}

// ---- Pages -------------------------------------------------------------------------------------------------------

export function PortalPage() {
  const { key = '' } = useParams();
  const [portal, setPortal] = useState<PublicPortal | null>(null);
  const [error, setError] = useState('');
  const [done, setDone] = useState<{ token: string; reference: string } | null>(null);
  useEffect(() => {
    publicApi.portal(key).then(setPortal).catch((e: ApiError) => setError(e.message));
  }, [key]);

  return (
    <PublicShell title={portal ? `${portal.projectName} · ${t('Help')}` : 'FakeJIRA'}
      nav={portal && <PublicLinks projectKey={portal.projectKey} portal={false} roadmap={portal.roadmap} changelog={portal.changelog} />}>
      {error && <ErrorPanel message={error} />}
      {!portal && !error && <Spinner />}
      {portal && !done && (
        <article className="panel portal-panel">
          <span className="eyebrow">{portal.projectName}</span>
          <h1>{t('How can we help?')}</h1>
          {portal.intro && <div className="portal-intro"><Markdown>{portal.intro}</Markdown></div>}
          <RequestForm portal={portal} channel="portal" onDone={setDone} />
        </article>
      )}
      {portal && done && (
        <article className="panel portal-panel portal-done">
          <CheckCircle2 size={36} className="good-text" aria-hidden />
          <h1>{t('Thanks, we got your request')}</h1>
          <p>{t('Your reference is {ref}. Keep this link to follow it and talk to the team:', { ref: done.reference })}</p>
          <p><Link to={`/portal/requests/${done.token}`} className="btn btn-primary">{t('Follow your request')}</Link></p>
          <p className="muted small">{t('If the team has email set up, you will also get updates by email.')}</p>
        </article>
      )}
    </PublicShell>
  );
}

export function TrackingPage() {
  const { token = '' } = useParams();
  const [data, setData] = useState<PublicTracking | null>(null);
  const [error, setError] = useState('');
  const [reply, setReply] = useState('');
  const [sendError, setSendError] = useState('');
  useEffect(() => {
    publicApi.tracking(token).then(setData).catch((e: ApiError) => setError(e.message));
  }, [token]);

  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (!reply.trim()) return;
    setSendError('');
    try {
      setData(await publicApi.reply(token, reply.trim()));
      setReply('');
    } catch (e) {
      setSendError((e as ApiError).message);
    }
  };

  return (
    <PublicShell title={data ? `${data.reference} · ${data.title}` : 'FakeJIRA'}>
      {error && <ErrorPanel message={error} />}
      {!data && !error && <Spinner />}
      {data && (
        <article className="panel portal-panel">
          <span className="eyebrow">{data.projectName} · {data.reference}</span>
          <h1>{data.title}</h1>
          <p className="tracking-meta">
            <span className={`tracking-status ${data.resolved ? 'resolved' : ''}`}>{t(data.status)}</span>
            <span className="muted small">{data.requestType ? `${data.requestType} · ` : ''}{t('sent {date}', { date: formatDay(data.createdAt.slice(0, 10), true) })}</span>
          </p>
          <h2 className="subheading">{t('Conversation')}</h2>
          {data.messages.length === 0 && <p className="muted">{t('No messages yet. The team will reply here.')}</p>}
          <ol className="conversation">
            {data.messages.map((m, i) => (
              <li key={i} className={m.fromRequester ? 'mine' : 'theirs'}>
                <span className="small muted">{m.author} · {new Date(m.createdAt).toLocaleString()}</span>
                <div className="bubble"><Markdown>{m.body}</Markdown></div>
              </li>
            ))}
          </ol>
          <form className="form" onSubmit={send}>
            <label className="field">
              <span>{t('Add a message')}</span>
              <textarea rows={3} maxLength={5000} value={reply} onChange={(e) => setReply(e.target.value)} />
            </label>
            {sendError && <small className="field-error" role="alert">{sendError}</small>}
            <button className="btn btn-primary" disabled={!reply.trim()}><Send size={16} /> {t('Send')}</button>
          </form>
        </article>
      )}
    </PublicShell>
  );
}

const STAGES: { id: 'now' | 'next' | 'later' | 'done'; label: string }[] = [
  { id: 'now', label: 'Now' }, { id: 'next', label: 'Next' }, { id: 'later', label: 'Later' }, { id: 'done', label: 'Done' },
];

export function PublicRoadmapPage() {
  const { key = '' } = useParams();
  const [data, setData] = useState<PublicRoadmap | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    publicApi.roadmap(key).then(setData).catch((e: ApiError) => setError(e.message));
  }, [key]);
  return (
    <PublicShell title={data ? `${data.projectName} · ${t('Roadmap')}` : 'FakeJIRA'}
      nav={data && <PublicLinks projectKey={data.projectKey} portal={data.portal} roadmap={false} changelog={false} />}>
      {error && <ErrorPanel message={error} />}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <span className="eyebrow">{data.projectName}</span>
          <h1>{t('Roadmap')}</h1>
          {data.items.length === 0 && <p className="muted">{t('Nothing on the roadmap yet.')}</p>}
          <div className="public-roadmap">
            {STAGES.map((stage) => {
              const items = data.items.filter((i) => i.stage === stage.id);
              return (
                <section key={stage.id} className="public-stage">
                  <h2>{t(stage.label)} <span className="count muted-count">{items.length}</span></h2>
                  {items.map((item) => (
                    <article key={item.name} className={`panel roadmap-card epic-color-${item.colorIndex % 8}`}>
                      <h3>{item.name}</h3>
                      {item.description && <p className="small">{item.description}</p>}
                      <div className="progress"><span style={{ width: `${item.percentDone}%` }} /></div>
                      <span className="muted small">
                        {t('{n}% done', { n: item.percentDone })}
                        {item.dueDate && ` · ${t('target {date}', { date: formatDay(item.dueDate, true) })}`}
                      </span>
                    </article>
                  ))}
                </section>
              );
            })}
          </div>
        </>
      )}
    </PublicShell>
  );
}

export function PublicChangelogPage() {
  const { key = '' } = useParams();
  const [data, setData] = useState<PublicChangelog | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    publicApi.changelog(key).then(setData).catch((e: ApiError) => setError(e.message));
  }, [key]);
  const list = (title: string, items: string[]) => items.length > 0 && (
    <>
      <h3>{title}</h3>
      <ul>{items.map((i) => <li key={i}>{i}</li>)}</ul>
    </>
  );
  return (
    <PublicShell title={data ? `${data.projectName} · ${t('Changelog')}` : 'FakeJIRA'}
      nav={data && <PublicLinks projectKey={data.projectKey} portal={data.portal} roadmap={false} changelog={false} />}>
      {error && <ErrorPanel message={error} />}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <span className="eyebrow">{data.projectName}</span>
          <h1>{t('Changelog')}</h1>
          {data.releases.length === 0 && <p className="muted">{t('No releases yet.')}</p>}
          {data.releases.map((r) => (
            <article key={r.version} className="panel changelog-entry">
              <h2>{r.version} {r.date && <span className="muted small">{formatDay(r.date, true)}</span>}</h2>
              {r.description && <p>{r.description}</p>}
              {list(t('New'), r.features)}
              {list(t('Fixed'), r.fixes)}
              {list(t('Improved'), r.other)}
            </article>
          ))}
        </>
      )}
    </PublicShell>
  );
}

/** The feedback widget's page, shown inside an iframe on other sites. */
export function EmbedPage() {
  const { key = '' } = useParams();
  const [portal, setPortal] = useState<PublicPortal | null>(null);
  const [error, setError] = useState('');
  const [done, setDone] = useState<{ token: string; reference: string } | null>(null);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    document.documentElement.classList.add('embedded');
    publicApi.portal(key).then(setPortal).catch((e: ApiError) => setError(e.message));
    return () => document.documentElement.classList.remove('embedded');
  }, [key]);
  const close = () => window.parent?.postMessage({ type: 'fakejira-widget-close' }, '*');
  return (
    <div className="embed-page" ref={root}>
      <header className="embed-header">
        <strong>{portal ? t('Feedback for {name}', { name: portal.projectName }) : t('Feedback')}</strong>
        <button className="icon-button" onClick={close} aria-label={t('Close')}>×</button>
      </header>
      <main className="embed-main" id="main">
        {error && <p className="muted">{error}</p>}
        {!portal && !error && <Spinner />}
        {portal && !done && <RequestForm portal={portal} channel="widget" onDone={setDone} />}
        {portal && done && (
          <div className="portal-done">
            <CheckCircle2 size={32} className="good-text" aria-hidden />
            <p><strong>{t('Thanks! We got it.')}</strong></p>
            <p className="small">{t('Reference {ref}.', { ref: done.reference })}{' '}
              <a href={`/portal/requests/${done.token}`} target="_blank" rel="noreferrer">{t('Follow your request')}</a></p>
          </div>
        )}
      </main>
    </div>
  );
}
