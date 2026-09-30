import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Bookmark, Download, FileSpreadsheet, FileText, Link2, Mail, Search as SearchIcon, Sparkles } from 'lucide-react';
import { aiErrorMessage, useAiEnabled } from '../components/Ai';
import { ScheduleReportModal } from '../components/reports/ScheduleReportModal';
import { api, ApiError, saveBlob } from '../api';
import { useToast } from '../toast';
import { useProjects } from '../projects';
import { Avatar } from '../components/Avatar';
import { DueBadge, PointsBadge, PriorityBadge, StatusBadge, TypeIcon } from '../components/Badges';
import { FqlInput } from '../components/FqlInput';
import { FILTERS_CHANGED } from '../components/Layout';
import { Modal } from '../components/Modal';
import { EmptyState, Spinner } from '../components/States';
import { PRIORITY_ORDER, STATUSES, type SearchResult, type Task } from '../types';
import { t } from '../i18n';

const EXAMPLES = [
  { label: 'My open work', q: 'assignee = me AND status != done ORDER BY priority DESC' },
  { label: 'Due this week', q: 'due <= endOfWeek AND status != done ORDER BY due' },
  { label: 'Overdue', q: 'due < today AND status != done ORDER BY due' },
  { label: 'Unassigned bugs', q: 'type = bug AND assignee is empty AND status != done' },
  { label: 'Updated today', q: 'updated >= today ORDER BY updated DESC' },
  { label: 'Done this month', q: 'resolved >= startOfMonth ORDER BY resolved DESC' },
];

type SortKey = 'key' | 'title' | 'status' | 'priority' | 'assignee' | 'due' | 'points';

/** Advanced search with the query language (FQL). */
export function SearchPage() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const initial = params.get('q') ?? 'assignee = me AND status != done ORDER BY priority DESC';
  const [text, setText] = useState(initial);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [error, setError] = useState<{ message: string; position: number | null } | null>(null);
  const [loading, setLoading] = useState(false);
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean } | null>(null);
  const [saving, setSaving] = useState(false);
  const [scheduling, setScheduling] = useState(false);

  const run = useCallback(async (q: string) => {
    setLoading(true);
    try {
      setResult(await api.search(q));
      setError(null);
    } catch (e) {
      const err = e as ApiError;
      const position = err.fieldErrors?.position;
      setError({ message: err.message, position: position !== undefined ? Number(position) : null });
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const current = params.get('q');
  useEffect(() => {
    const q = current ?? initial;
    setText(q);
    run(q);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, run]);

  const submit = (event?: FormEvent) => {
    event?.preventDefault();
    setSort(null);
    if (text === current) run(text);
    else setParams({ q: text }, { replace: false });
  };

  const rows = useMemo(() => {
    if (!result) return [];
    if (!sort) return result.tasks;
    const value = (t: Task): string | number => {
      switch (sort.key) {
        case 'key': return `${t.projectKey}-${String(t.key.split('-')[1]).padStart(8, '0')}`;
        case 'title': return t.title.toLowerCase();
        case 'status': return STATUSES.indexOf(t.status);
        case 'priority': return -PRIORITY_ORDER[t.priority];
        case 'assignee': return t.assignee?.displayName.toLowerCase() ?? '~';
        case 'due': return t.dueDate ?? '9999';
        default: return t.storyPoints ?? -1;
      }
    };
    return [...result.tasks].sort((a, b) => {
      const x = value(a);
      const y = value(b);
      const cmp = x < y ? -1 : x > y ? 1 : 0;
      return sort.desc ? -cmp : cmp;
    });
  }, [result, sort]);

  const header = (key: SortKey, label: string, className = '') => (
    <th className={className} aria-sort={sort?.key === key ? (sort.desc ? 'descending' : 'ascending') : undefined}>
      <button className="th-sort" onClick={() => setSort(sort?.key === key ? { key, desc: !sort.desc } : { key, desc: false })}>
        {label}{sort?.key === key ? (sort.desc ? ' ↓' : ' ↑') : ''}
      </button>
    </th>
  );

  const exportCsv = () => {
    const cell = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
    const lines = [['Key', 'Type', 'Title', 'Status', 'Priority', 'Assignee', 'Due', 'Points'].join(',')];
    rows.forEach((t) => lines.push([t.key, t.type, t.title, t.status, t.priority, t.assignee?.username ?? '', t.dueDate ?? '',
      t.storyPoints?.toString() ?? ''].map(cell).join(',')));
    const blob = new Blob([`﻿${lines.join('\r\n')}`], { type: 'text/csv' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'search-results.csv';
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const download = async (format: 'xlsx' | 'pdf') => {
    try {
      saveBlob(await api.exportSearch(params.get('q') ?? text, format), `fakejira-tasks.${format}`);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/search?q=${encodeURIComponent(text)}`);
      toast(t("Link copied"));
    } catch {
      toast(t("Could not copy the link"), 'error');
    }
  };

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t("Search")}</span>
          <h1>{t("Find tasks")}</h1>
          <p className="muted">{t("Query across all your projects. Press Tab to complete a field or value, Enter to search.")}</p>
        </div>
      </header>
      <form className="fql-form" onSubmit={submit}>
        <FqlInput value={text} onChange={setText} onSubmit={() => submit()} errorAt={error?.position ?? null} />
        <button className="btn btn-primary" disabled={loading}><SearchIcon size={16} /> {t("Search")}</button>
      </form>
      {error && <div className="alert fql-error" role="alert">{error.message}</div>}
      <AskClaude onQuery={(q) => {
        setText(q);
        setSort(null);
        setParams({ q }, { replace: false });
      }} />
      <div className="chip-row fql-examples">
        {EXAMPLES.map((e) => (
          <button key={e.label} className="chip" onClick={() => setParams({ q: e.q })}>{e.label}</button>
        ))}
        <details className="fql-help">
          <summary className="link small">{t("Syntax help")}</summary>
          <div className="fql-help-body small">
            <p><code>field operator value</code> {t("joined with")} <code>AND</code>, <code>OR</code>, <code>NOT</code> and parentheses,
              then optionally <code>ORDER BY field [ASC|DESC]</code>.</p>
            <p>Operators: <code>=</code> <code>!=</code> <code>~</code> {t("(contains)")} <code>&gt;</code> <code>&lt;</code>
              <code>in (a, b)</code> <code>not in (…)</code> <code>is empty</code>.</p>
            <p>Dates: <code>2026-10-01</code>, <code>today</code>, <code>-7d</code>, <code>+2w</code>, <code>startOfWeek</code>,
              <code>endOfMonth</code>. People: <code>me</code>, a username, <code>membersOf(team)</code>.</p>
            <p>Example: <code>project = WEB AND (type = bug OR priority &gt;= high) AND text ~ "checkout" ORDER BY due</code></p>
          </div>
        </details>
      </div>

      {loading && !result && <Spinner />}
      {result && (
        <section className="panel search-results">
          <div className="panel-head">
            <h2 className="panel-title">
              {result.total} task{result.total === 1 ? '' : 's'}
              {result.truncated && <span className="muted small"> · showing the first {result.tasks.length}</span>}
            </h2>
            <div className="header-actions">
              <button className="btn btn-ghost btn-sm" onClick={copyLink}><Link2 size={15} /> {t("Copy link")}</button>
              <button className="btn btn-ghost btn-sm" onClick={exportCsv} disabled={!rows.length}><Download size={15} /> {t("CSV")}</button>
              <button className="btn btn-ghost btn-sm" onClick={() => download('xlsx')} disabled={!rows.length}><FileSpreadsheet size={15} /> {t('Excel')}</button>
              <button className="btn btn-ghost btn-sm" onClick={() => download('pdf')} disabled={!rows.length}><FileText size={15} /> {t('PDF')}</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setScheduling(true)}><Mail size={15} /> {t('Email me')}</button>
              <button className="btn btn-soft btn-sm" onClick={() => setSaving(true)}><Bookmark size={15} /> {t("Save filter")}</button>
            </div>
          </div>
          {rows.length === 0 ? (
            <EmptyState icon={<SearchIcon size={28} />} title={t("No tasks match")}>{t("Try a broader query.")}</EmptyState>
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    {header('key', 'Key')}
                    {header('title', 'Title')}
                    {header('status', 'Status')}
                    {header('priority', 'Priority')}
                    {header('assignee', 'Assignee')}
                    {header('due', 'Due')}
                    {header('points', 'Pts', 'num')}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((t) => (
                    <tr key={t.id}>
                      <td className="nowrap"><TypeIcon type={t.type} /> <span className="task-key">{t.key}</span></td>
                      <td><Link to={`/tasks/${t.id}`}>{t.title}</Link></td>
                      <td><StatusBadge status={t.status} /></td>
                      <td><PriorityBadge priority={t.priority} /></td>
                      <td>{t.assignee ? <span className="person"><Avatar user={t.assignee} size={20} /> {t.assignee.displayName}</span>
                        : <span className="muted">—</span>}</td>
                      <td>{t.dueDate ? <DueBadge date={t.dueDate} done={t.status === 'DONE'} /> : <span className="muted">—</span>}</td>
                      <td className="num"><PointsBadge points={t.storyPoints} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
      {saving && <SaveSearchModal query={text} onClose={() => setSaving(false)} onSaved={(name) => {
        setSaving(false);
        toast(`Filter "${name}" saved`);
        window.dispatchEvent(new Event(FILTERS_CHANGED));
      }} />}
      {scheduling && <ScheduleReportModal kind="filter" target={params.get('q') ?? text} defaultTitle=""
        onClose={() => setScheduling(false)} />}
    </div>
  );
}

function SaveSearchModal({ query, onClose, onSaved }: { query: string; onClose: () => void; onSaved: (name: string) => void }) {
  const { projects } = useProjects();
  const mentioned = /\bproject\s*=\s*"?([A-Za-z][A-Za-z0-9]*)/i.exec(query)?.[1]?.toUpperCase();
  const [project, setProject] = useState(mentioned && projects?.some((p) => p.key === mentioned) ? mentioned : projects?.[0]?.key ?? '');
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await api.saveFilter(project, name.trim(), `fql=${encodeURIComponent(query)}`, shared);
      onSaved(name.trim());
    } catch (e) {
      setError((e as ApiError).message);
    }
  };
  return (
    <Modal title={t("Save filter")} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="save-search" disabled={!name.trim() || !project}>{t("Save")}</button>
      </>
    }>
      <form id="save-search" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={name} maxLength={60} autoFocus placeholder={t("Overdue bugs")} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>{t("Show in project")}</span>
          <select value={project} onChange={(e) => setProject(e.target.value)}>
            {(projects ?? []).map((p) => <option key={p.key} value={p.key}>{p.key} · {p.name}</option>)}
          </select>
        </label>
        <label className="toggle">
          <input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} /> Share with the project
        </label>
        <p className="muted small">{t("Saved filters appear in the sidebar and the command palette.")}</p>
      </form>
    </Modal>
  );
}

/** "Ask Claude": a plain-language question becomes an FQL query, which is shown and run. */
function AskClaude({ onQuery }: { onQuery: (fql: string) => void }) {
  const enabled = useAiEnabled();
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ text: string; error: boolean } | null>(null);
  if (!enabled) return null;
  const ask = async (event: FormEvent) => {
    event.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    setNote(null);
    try {
      const answer = await api.aiFql(question);
      onQuery(answer.fql);
      setNote({ text: answer.explanation, error: false });
    } catch (e) {
      setNote({ text: aiErrorMessage(e), error: true });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="ai-ask" onSubmit={ask}>
      <Sparkles size={16} aria-hidden className="ai-ask-icon" />
      <input value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={1000}
        placeholder={t('Or ask in plain words, e.g. “bugs assigned to me that are overdue”')} aria-label={t('Ask Claude to write the query')} />
      <button className="btn btn-ghost btn-sm" disabled={busy || !question.trim()}>{busy ? t('Thinking…') : t('Ask Claude')}</button>
      {note && <p className={note.error ? 'field-error ai-ask-note' : 'muted small ai-ask-note'} role="status">{note.text}</p>}
    </form>
  );
}
