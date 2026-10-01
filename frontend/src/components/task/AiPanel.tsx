import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Sparkles } from 'lucide-react';
import { api } from '../../api';
import type { AiEstimate, AiThreadSummary, AiTriage, Task, TaskInput } from '../../types';
import { AI_NOTE, aiErrorMessage, useAiEnabled } from '../Ai';
import { useToast } from '../../toast';
import { t } from '../../i18n';

type Result =
  | { kind: 'summary'; data: AiThreadSummary; since: string | null }
  | { kind: 'estimate'; data: AiEstimate }
  | { kind: 'triage'; data: AiTriage };

const SINCE: Record<string, number> = { day: 1, week: 7 };

function List({ title, items }: { title: string; items: string[] }) {
  if (!items.length) return null;
  return (
    <>
      <h4 className="ai-subtitle">{title}</h4>
      <ul className="ai-list">{items.map((item, i) => <li key={i}>{item}</li>)}</ul>
    </>
  );
}

function label(value: string) {
  return t(value.charAt(0) + value.slice(1).toLowerCase());
}

/** The Claude assistant for one task: summaries, estimates and triage. Suggestions are applied only on request. */
export function AiPanel({ task, canEdit, update, onChanged }: {
  task: Task;
  canEdit: boolean;
  update: (change: Partial<TaskInput>, message?: string) => Promise<unknown>;
  onChanged: () => void;
}) {
  const enabled = useAiEnabled();
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [since, setSince] = useState('day');
  if (!enabled) return null;

  const ask = async (kind: string, work: () => Promise<Result>) => {
    setBusy(kind);
    try {
      setResult(await work());
    } catch (e) {
      toast(aiErrorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const summarize = (withChanges: boolean) => ask(withChanges ? 'changes' : 'summary', async () => {
    const from = withChanges ? new Date(Date.now() - SINCE[since] * 86400000).toISOString() : null;
    return { kind: 'summary', data: await api.aiSummary(task.id, from ?? undefined), since: from };
  });

  const applyEstimate = async (estimate: AiEstimate) => {
    setBusy('apply');
    try {
      await update({ storyPoints: estimate.storyPoints }, t('Estimate applied'));
      if (estimate.estimateHours) {
        await api.schedule(task.id, { startDate: task.startDate ?? null, dueDate: task.dueDate ?? null,
          estimateMinutes: estimate.estimateHours * 60 });
      }
      setResult(null);
      onChanged();
    } catch (e) {
      toast(aiErrorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const applyTriage = async (triage: AiTriage) => {
    setBusy('apply');
    try {
      await update({ type: triage.type, priority: triage.priority,
        labels: [...new Set([...task.labels, ...triage.labels])].slice(0, 10) }, t('Triage applied'));
      if (triage.assignee && triage.assignee.id !== task.assignee?.id) {
        await api.assign(task.id, triage.assignee.id);
      }
      if (triage.duplicateOf) {
        await api.addLink(task.id, 'DUPLICATES', triage.duplicateOf.key);
      }
      setResult(null);
      onChanged();
    } catch (e) {
      toast(aiErrorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="side-section ai-panel">
      <h3 className="side-title"><Sparkles size={15} aria-hidden /> {t('Claude')}</h3>
      <div className="ai-actions">
        <button className="btn btn-ghost btn-sm" onClick={() => summarize(false)} disabled={!!busy}>
          {busy === 'summary' ? t('Reading…') : t('Summarize')}
        </button>
        <span className="ai-since">
          <button className="btn btn-ghost btn-sm" onClick={() => summarize(true)} disabled={!!busy}>
            {busy === 'changes' ? t('Reading…') : t('What changed')}
          </button>
          <select value={since} onChange={(e) => setSince(e.target.value)} aria-label={t('Changes since')}>
            <option value="day">{t('in the last day')}</option>
            <option value="week">{t('in the last week')}</option>
          </select>
        </span>
        <button className="btn btn-ghost btn-sm" onClick={() => ask('estimate', async () => ({ kind: 'estimate', data: await api.aiEstimate(task.id) }))}
          disabled={!!busy}>{busy === 'estimate' ? t('Estimating…') : t('Suggest estimate')}</button>
        {canEdit && (
          <button className="btn btn-ghost btn-sm" onClick={() => ask('triage', async () => ({ kind: 'triage', data: await api.aiTriage(task.id) }))}
            disabled={!!busy}>{busy === 'triage' ? t('Triaging…') : t('Triage')}</button>
        )}
      </div>

      {result && (
        <div className="ai-result" role="status">
          {result.kind === 'summary' && (
            <>
              <p>{result.data.summary}</p>
              {result.since && (result.data.changes.length
                ? <List title={t('What changed')} items={result.data.changes} />
                : <p className="muted small">{t('Nothing changed in that time.')}</p>)}
              <List title={t('Decisions')} items={result.data.decisions} />
              <List title={t('Open questions')} items={result.data.openQuestions} />
            </>
          )}
          {result.kind === 'estimate' && (
            <>
              <p>
                <b>{t('{n} pts', { n: result.data.storyPoints })}</b>
                {result.data.estimateHours ? <> · <b>{t('{n} h', { n: result.data.estimateHours })}</b></> : null}
                {' '}<span className={`ai-confidence ${result.data.confidence}`}>{t(`${result.data.confidence} confidence`)}</span>
              </p>
              <p className="small">{result.data.reasoning}</p>
              {result.data.similar.length > 0 && (
                <>
                  <h4 className="ai-subtitle">{t('Compared with')}</h4>
                  <ul className="ai-list small">
                    {result.data.similar.slice(0, 5).map((s) => (
                      <li key={s.task.id}><Link to={`/tasks/${s.task.id}`}>{s.task.key}</Link> {s.task.title}
                        <span className="muted"> · {s.storyPoints ?? '–'} {t('pts')}</span></li>
                    ))}
                  </ul>
                </>
              )}
              {canEdit && <button className="btn btn-primary btn-sm" disabled={!!busy} onClick={() => applyEstimate(result.data)}>{t('Apply estimate')}</button>}
            </>
          )}
          {result.kind === 'triage' && (
            <>
              <dl className="ai-triage">
                <dt>{t('Type')}</dt><dd>{label(result.data.type)}</dd>
                <dt>{t('Priority')}</dt><dd>{label(result.data.priority)}</dd>
                <dt>{t('Assignee')}</dt><dd>{result.data.assignee?.displayName ?? <span className="muted">{t('No suggestion')}</span>}</dd>
                <dt>{t('Labels')}</dt><dd>{result.data.labels.join(', ') || <span className="muted">{t('None')}</span>}</dd>
                {result.data.duplicateOf && (
                  <><dt>{t('Duplicate of')}</dt><dd><Link to={`/tasks/${result.data.duplicateOf.id}`}>{result.data.duplicateOf.key}</Link> {result.data.duplicateOf.title}</dd></>
                )}
              </dl>
              <p className="small">{result.data.reasoning}</p>
              <button className="btn btn-primary btn-sm" disabled={!!busy} onClick={() => applyTriage(result.data)}>{t('Apply triage')}</button>
            </>
          )}
          <p className="muted small ai-note">{t(AI_NOTE)} <button className="link" onClick={() => setResult(null)}>{t('Dismiss')}</button></p>
        </div>
      )}
    </section>
  );
}
