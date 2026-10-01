import { useEffect, useState, type FormEvent } from 'react';
import { Copy, Sparkles } from 'lucide-react';
import { api, ApiError } from '../api';
import type { AiNotes, AiProposedTask, AiStatus, AiTaskDraft } from '../types';
import { Markdown } from './Markdown';
import { Modal } from './Modal';
import { useToast } from '../toast';
import { t } from '../i18n';

let statusPromise: Promise<AiStatus> | null = null;

/** Whether the server has the Claude assistant set up (asked once per page load). */
export function useAiEnabled() {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    let active = true;
    statusPromise ??= api.aiStatus().catch(() => ({ enabled: false, model: null }));
    statusPromise.then((status) => active && setEnabled(status.enabled));
    return () => {
      active = false;
    };
  }, []);
  return enabled;
}

export function aiErrorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : t('Claude could not answer. Try again.');
}

export const AI_NOTE = 'Written by Claude. Check it before using it.';

/** "Write with Claude": turns a sentence into a full task in the task form. */
export function AiDraftBox({ projectKey, onDraft }: { projectKey: string; onDraft: (draft: AiTaskDraft) => void }) {
  const [open, setOpen] = useState(false);
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (!open) {
    return (
      <button type="button" className="btn btn-ghost btn-sm ai-trigger" onClick={() => setOpen(true)} disabled={!projectKey}>
        <Sparkles size={14} aria-hidden /> {t('Write with Claude')}
      </button>
    );
  }
  const draft = async () => {
    if (!prompt.trim()) return;
    setBusy(true);
    setError('');
    try {
      onDraft(await api.aiDraftTask(projectKey, prompt));
      setOpen(false);
      setPrompt('');
    } catch (e) {
      setError(aiErrorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="ai-box">
      <label className="field">
        <span><Sparkles size={14} aria-hidden /> {t('Describe the task in a sentence or two')}</span>
        <textarea rows={2} value={prompt} maxLength={4000} autoFocus
          placeholder={t('e.g. customers want to pay with Apple Pay at checkout')}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              draft();
            }
          }} />
      </label>
      {error && <p className="field-error" role="alert">{error}</p>}
      <div className="row-actions">
        <button type="button" className="btn btn-primary btn-sm" onClick={draft} disabled={busy || !prompt.trim()}>
          {busy ? t('Writing…') : t('Write the task')}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>{t('Cancel')}</button>
        <span className="muted small">{t('Claude fills in the form; you review it before saving.')}</span>
      </div>
    </div>
  );
}

/** Shows a document Claude drafted (sprint review, release notes) with copy and optional "use" actions. */
export function AiNotesModal({ title, load, useLabel, onUse, onClose }: {
  title: string;
  load: () => Promise<AiNotes>;
  useLabel?: string;
  onUse?: (markdown: string) => Promise<void> | void;
  onClose: () => void;
}) {
  const toast = useToast();
  const [markdown, setMarkdown] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const generate = () => {
    setMarkdown(null);
    setError('');
    load().then((notes) => setMarkdown(notes.markdown)).catch((e) => setError(aiErrorMessage(e)));
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(generate, []);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(markdown ?? '');
      toast(t('Copied'), 'success');
    } catch {
      toast(t('Could not copy'), 'error');
    }
  };
  return (
    <Modal title={title} onClose={onClose} wide footer={
      <>
        <button className="btn btn-ghost" onClick={generate} disabled={markdown === null && !error}>{t('Write again')}</button>
        <button className="btn btn-ghost" onClick={copy} disabled={!markdown}><Copy size={14} aria-hidden /> {t('Copy Markdown')}</button>
        {onUse && (
          <button className="btn btn-primary" disabled={!markdown || busy} onClick={async () => {
            setBusy(true);
            try {
              await onUse(markdown ?? '');
              onClose();
            } catch (e) {
              toast(aiErrorMessage(e), 'error');
            } finally {
              setBusy(false);
            }
          }}>{useLabel}</button>
        )}
      </>
    }>
      {error && <div className="alert" role="alert">{error}</div>}
      {markdown === null && !error && <p className="muted ai-thinking" role="status"><Sparkles size={14} aria-hidden /> {t('Claude is writing…')}</p>}
      {markdown !== null && (
        <>
          <p className="muted small">{t(AI_NOTE)} <button className="link" onClick={() => setEditing(!editing)}>
            {editing ? t('Preview') : t('Edit')}</button></p>
          {editing
            ? <textarea className="ai-notes-editor" rows={16} value={markdown} onChange={(e) => setMarkdown(e.target.value)}
                aria-label={t('Draft')} />
            : <div className="ai-notes"><Markdown>{markdown}</Markdown></div>}
        </>
      )}
    </Modal>
  );
}

/** Splits an epic into proposed tasks; the checked ones are created in the epic. */
export function AiSplitEpicModal({ epicId, epicName, projectKey, onCreated, onClose }: {
  epicId: number;
  epicName: string;
  projectKey: string;
  onCreated: (count: number) => void;
  onClose: () => void;
}) {
  const [guidance, setGuidance] = useState('');
  const [proposed, setProposed] = useState<AiProposedTask[] | null>(null);
  const [chosen, setChosen] = useState<boolean[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const split = async (event?: FormEvent) => {
    event?.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await api.aiSplitEpic(epicId, guidance);
      setProposed(result.tasks);
      setChosen(result.tasks.map(() => true));
    } catch (e) {
      setError(aiErrorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    if (!proposed) return;
    setBusy(true);
    setError('');
    let created = 0;
    try {
      for (let i = 0; i < proposed.length; i++) {
        if (!chosen[i]) continue;
        const p = proposed[i];
        await api.createTask({
          projectKey, title: p.title, description: p.description, priority: p.priority, type: p.type,
          storyPoints: p.storyPoints || null, epicId, dueDate: null, labels: [], assigneeId: null, sprintId: null,
        });
        created++;
      }
      onCreated(created);
      onClose();
    } catch (e) {
      setError(aiErrorMessage(e));
      if (created) onCreated(created);
    } finally {
      setBusy(false);
    }
  };

  const count = chosen.filter(Boolean).length;
  return (
    <Modal title={t('Split “{name}” with Claude', { name: epicName })} onClose={onClose} wide footer={
      proposed ? (
        <>
          <button className="btn btn-ghost" onClick={() => split()} disabled={busy}>{t('Suggest again')}</button>
          <button className="btn btn-primary" onClick={create} disabled={busy || !count}>
            {busy ? t('Creating…') : t('Create {count} tasks', { count })}
          </button>
        </>
      ) : undefined
    }>
      <form className="form" onSubmit={split}>
        <label className="field">
          <span>{t('Anything Claude should know? (optional)')}</span>
          <textarea rows={2} maxLength={2000} value={guidance} onChange={(e) => setGuidance(e.target.value)}
            placeholder={t('e.g. web only for now, we already have the API')} />
        </label>
        {!proposed && (
          <button type="submit" className="btn btn-primary" disabled={busy}>
            <Sparkles size={14} aria-hidden /> {busy ? t('Claude is thinking…') : t('Suggest tasks')}
          </button>
        )}
      </form>
      {error && <div className="alert" role="alert">{error}</div>}
      {proposed && (
        <>
          <p className="muted small">{t(AI_NOTE)}</p>
          {proposed.length === 0 && <p className="muted">{t('Claude found nothing more to add.')}</p>}
          <ul className="ai-proposals">
            {proposed.map((p, i) => (
              <li key={i}>
                <label className="check">
                  <input type="checkbox" checked={chosen[i] ?? false}
                    onChange={(e) => setChosen(chosen.map((c, j) => (j === i ? e.target.checked : c)))} />
                  <span>
                    <b>{p.title}</b>
                    <span className="muted small"> · {t(p.type.charAt(0) + p.type.slice(1).toLowerCase())} · {t(p.priority.charAt(0) + p.priority.slice(1).toLowerCase())}
                      {p.storyPoints ? ` · ${t('{n} pts', { n: p.storyPoints })}` : ''}</span>
                  </span>
                </label>
                {p.description && <details><summary className="small">{t('Description')}</summary><Markdown>{p.description}</Markdown></details>}
              </li>
            ))}
          </ul>
        </>
      )}
    </Modal>
  );
}
