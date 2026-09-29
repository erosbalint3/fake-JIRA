import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Bot, History, Pencil, Play, Plus, Sparkles, Trash2, X } from 'lucide-react';
import { api, ApiError, type RuleInput } from '../api';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { FqlInput } from '../components/FqlInput';
import { ConfirmDialog, Modal } from '../components/Modal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { timeAgo } from '../format';
import {
  PRIORITIES, PRIORITY_LABEL, STATUSES, STATUS_LABEL, type AutomationRule, type Member, type RuleAction,
  type RuleActionType, type RuleRun, type RuleTrigger,
} from '../types';
import { t } from '../i18n';

const TRIGGERS: { value: RuleTrigger; label: string }[] = [
  { value: 'CREATED', label: 'A task is created' },
  { value: 'UPDATED', label: 'A task is edited' },
  { value: 'STATUS_CHANGED', label: 'The status changes' },
  { value: 'ASSIGNED', label: 'The assignee changes' },
  { value: 'COMMENTED', label: 'Someone comments' },
  { value: 'SCHEDULED', label: 'On a schedule (every few minutes)' },
];

const ACTIONS: { value: RuleActionType; label: string }[] = [
  { value: 'assign', label: 'Assign to' },
  { value: 'set_status', label: 'Set status' },
  { value: 'set_priority', label: 'Set priority' },
  { value: 'add_label', label: 'Add label' },
  { value: 'remove_label', label: 'Remove label' },
  { value: 'set_due_in_days', label: 'Set due date in (days)' },
  { value: 'comment', label: 'Add comment' },
  { value: 'notify', label: 'Notify' },
  { value: 'move_to_active_sprint', label: 'Move to the active sprint' },
];

const ASSIGN_CHOICES = [
  { value: 'least_loaded', label: 'Least busy member' },
  { value: 'round_robin', label: 'Next member in turn (round robin)' },
  { value: 'reporter', label: 'The reporter' },
  { value: 'unassign', label: 'Nobody (unassign)' },
];

const RECIPES: { title: string; description: string; rule: RuleInput }[] = [
  {
    title: 'Triage new bugs',
    description: 'Give every new bug to the least busy teammate and label it.',
    rule: { name: 'Triage new bugs', trigger: 'CREATED', triggerStatus: null, condition: 'type = bug',
      actions: [{ type: 'assign', value: 'least_loaded' }, { type: 'add_label', value: 'triage' }] },
  },
  {
    title: 'Round-robin new work',
    description: 'Unassigned new tasks go to the next person in turn.',
    rule: { name: 'Round-robin new tasks', trigger: 'CREATED', triggerStatus: null, condition: 'assignee is empty',
      actions: [{ type: 'assign', value: 'round_robin' }] },
  },
  {
    title: 'SLA: escalate critical tasks',
    description: 'Critical tasks untouched for 4 hours get flagged and the owner is told.',
    rule: { name: 'Escalate untouched critical tasks', trigger: 'SCHEDULED', triggerStatus: null,
      condition: 'priority = critical AND status = todo AND updated < -4h',
      actions: [{ type: 'add_label', value: 'sla-breach' }, { type: 'notify', value: 'owner, assignee' },
        { type: 'comment', value: 'Heads-up: {{key}} has been waiting for 4 hours. {{assignee}}' }] },
  },
  {
    title: 'Flag stale tasks',
    description: 'To-do tasks without activity for 30 days get a "stale" label and a nudge.',
    rule: { name: 'Flag stale tasks', trigger: 'SCHEDULED', triggerStatus: null, condition: 'status = todo AND updated < -30d',
      actions: [{ type: 'add_label', value: 'stale' },
        { type: 'comment', value: 'No activity for 30 days. Is this still needed? It will be closed in 2 weeks otherwise.' }] },
  },
  {
    title: 'Close stale tasks',
    description: 'Stale tasks still untouched two weeks later are closed.',
    rule: { name: 'Close stale tasks', trigger: 'SCHEDULED', triggerStatus: null,
      condition: 'label = stale AND status = todo AND updated < -14d',
      actions: [{ type: 'set_status', value: 'DONE' }, { type: 'comment', value: 'Closed automatically after being stale.' }] },
  },
  {
    title: 'Tell the reporter when done',
    description: 'Notify whoever reported a task when it is finished.',
    rule: { name: 'Notify reporter on done', trigger: 'STATUS_CHANGED', triggerStatus: 'DONE', condition: '',
      actions: [{ type: 'notify', value: 'reporter' }] },
  },
  {
    title: 'Pull urgent bugs into the sprint',
    description: 'Critical bugs join the active sprint straight away.',
    rule: { name: 'Critical bugs into the sprint', trigger: 'CREATED', triggerStatus: null,
      condition: 'type = bug AND priority = critical', actions: [{ type: 'move_to_active_sprint' }] },
  },
];

function describeAction(a: RuleAction) {
  const label = ACTIONS.find((x) => x.value === a.type)?.label ?? a.type;
  switch (a.type) {
    case 'assign': return `assign to ${ASSIGN_CHOICES.find((c) => c.value === a.value)?.label.toLowerCase() ?? a.value}`;
    case 'set_status': return `set status to ${STATUS_LABEL[a.value as keyof typeof STATUS_LABEL] ?? a.value}`;
    case 'set_priority': return `set priority to ${PRIORITY_LABEL[a.value as keyof typeof PRIORITY_LABEL] ?? a.value}`;
    case 'comment': return 'add a comment';
    case 'move_to_active_sprint': return 'move to the active sprint';
    case 'set_due_in_days': return `make it due in ${a.value} days`;
    default: return `${label.toLowerCase()} ${a.value ?? ''}`.trim();
  }
}

export function AutomationPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const toast = useToast();
  const [rules, setRules] = useState<AutomationRule[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<{ rule: AutomationRule | null; initial: RuleInput } | null>(null);
  const [deleting, setDeleting] = useState<AutomationRule | null>(null);
  const [logFor, setLogFor] = useState<AutomationRule | null>(null);
  const [recipes, setRecipes] = useState(false);

  const load = useCallback(() => {
    if (!project) return;
    api.automations(key).then(setRules).catch((e: ApiError) => setError(e.message));
  }, [key, project]);
  useEffect(load, [load]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const toggle = async (rule: AutomationRule) => {
    try {
      await api.updateAutomation(rule.id, { name: rule.name, trigger: rule.trigger, triggerStatus: rule.triggerStatus,
        condition: rule.condition, actions: rule.actions, enabled: !rule.enabled });
      toast(rule.enabled ? `${rule.name} paused` : `${rule.name} turned on`);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const blank: RuleInput = { name: '', trigger: 'CREATED', triggerStatus: null, condition: '', actions: [{ type: 'assign', value: 'least_loaded' }] };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t("Automation")}</h1>
          <p className="muted">{t("Rules do the busywork: triage, escalate, clean up. Each rule acts with the permissions of the person who created it.")}</p>
        </div>
        {canEdit && (
          <div className="header-actions">
            <button className="btn btn-ghost" onClick={() => setRecipes(true)}><Sparkles size={16} /> {t("From a recipe")}</button>
            <button className="btn btn-primary" onClick={() => setEditing({ rule: null, initial: blank })}><Plus size={17} /> {t("New rule")}</button>
          </div>
        )}
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!rules && !error && <Spinner />}
      {rules && rules.length === 0 && (
        <EmptyState icon={<Bot size={28} />} title={t("No rules yet")}>
          {canEdit ? <button className="link" onClick={() => setRecipes(true)}>{t("Start from a recipe")}</button> : 'Editors can add rules.'}
        </EmptyState>
      )}
      <ul className="rule-list">
        {rules?.map((rule) => (
          <li key={rule.id} className={`rule panel ${rule.enabled ? '' : 'paused'}`}>
            <div className="rule-head">
              <label className="switch" title={rule.enabled ? 'On' : 'Paused'}>
                <input type="checkbox" checked={rule.enabled} disabled={!canEdit} onChange={() => toggle(rule)}
                  aria-label={`${rule.name} enabled`} />
                <span />
              </label>
              <h2>{rule.name}</h2>
              <span className="spacer" />
              <button className="icon-button" aria-label={`Log of ${rule.name}`} title={t("Recent runs")} onClick={() => setLogFor(rule)}>
                <History size={16} /></button>
              {canEdit && rule.trigger === 'SCHEDULED' && (
                <button className="icon-button" aria-label={`Run ${rule.name} now`} title={t("Run now")} onClick={async () => {
                  try {
                    const r = await api.runAutomation(rule.id);
                    toast(r.tasks ? `Acted on ${r.tasks} task${r.tasks === 1 ? '' : 's'}` : 'Nothing new matched');
                    load();
                  } catch (e) {
                    toast((e as ApiError).message, 'error');
                  }
                }}><Play size={16} /></button>
              )}
              {canEdit && (
                <>
                  <button className="icon-button" aria-label={`Edit ${rule.name}`} onClick={() => setEditing({ rule, initial: {
                    name: rule.name, trigger: rule.trigger, triggerStatus: rule.triggerStatus, condition: rule.condition, actions: rule.actions,
                  } })}><Pencil size={16} /></button>
                  <button className="icon-button" aria-label={`Delete ${rule.name}`} onClick={() => setDeleting(rule)}><Trash2 size={16} /></button>
                </>
              )}
            </div>
            <p className="rule-sentence">
              <strong>{t("When")}</strong> {TRIGGERS.find((t) => t.value === rule.trigger)?.label.toLowerCase()}
              {rule.triggerStatus && <> to <em>{STATUS_LABEL[rule.triggerStatus]}</em></>}
              {rule.condition && <> <strong>{t("and")}</strong> <code>{rule.condition}</code></>}
              {' '}<strong>{t("then")}</strong> {rule.actions.map(describeAction).join(', ')}.
            </p>
            <p className="muted small rule-meta">
              <Avatar user={rule.owner} size={16} /> by {rule.owner.displayName}
              {' · '}{rule.runCount ? `ran ${rule.runCount} time${rule.runCount === 1 ? '' : 's'}, last ${timeAgo(rule.lastRunAt!)}` : 'has not run yet'}
              {rule.lastError && <span className="overdue-text"> · last error: {rule.lastError}</span>}
            </p>
          </li>
        ))}
      </ul>

      {recipes && (
        <Modal title={t("Start from a recipe")} onClose={() => setRecipes(false)} footer={<button className="btn btn-ghost" onClick={() => setRecipes(false)}>{t("Close")}</button>}>
          <ul className="recipe-list">
            {RECIPES.map((r) => (
              <li key={r.title}>
                <button className="recipe" onClick={() => {
                  setRecipes(false);
                  setEditing({ rule: null, initial: r.rule });
                }}>
                  <strong>{r.title}</strong>
                  <span className="muted small">{r.description}</span>
                </button>
              </li>
            ))}
          </ul>
        </Modal>
      )}
      {editing && (
        <RuleModal projectKey={key} members={project.members} rule={editing.rule} initial={editing.initial}
          onClose={() => setEditing(null)} onSaved={(name) => {
            setEditing(null);
            toast(`${name} saved`);
            load();
          }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.name}?`} message={t("The rule stops running. Changes it already made stay.")}
          confirmLabel={t("Delete rule")} danger onClose={() => setDeleting(null)}
          onConfirm={async () => {
            const r = deleting;
            setDeleting(null);
            await api.deleteAutomation(r.id).catch(() => {});
            toast(`${r.name} deleted`);
            load();
          }} />
      )}
      {logFor && <RuleLog rule={logFor} onClose={() => setLogFor(null)} />}
    </div>
  );
}

function RuleLog({ rule, onClose }: { rule: AutomationRule; onClose: () => void }) {
  const [runs, setRuns] = useState<RuleRun[] | null>(null);
  useEffect(() => {
    api.automationLog(rule.id).then(setRuns).catch(() => setRuns([]));
  }, [rule.id]);
  return (
    <Modal title={`Recent runs · ${rule.name}`} onClose={onClose} footer={<button className="btn btn-ghost" onClick={onClose}>{t("Close")}</button>}>
      {!runs ? <Spinner /> : runs.length === 0 ? <p className="muted">{t("This rule has not run yet.")}</p> : (
        <ul className="run-list">
          {runs.map((r) => (
            <li key={r.id} className={r.success ? '' : 'failed'}>
              <span className={`run-dot ${r.success ? 'ok' : 'bad'}`} aria-label={r.success ? 'Succeeded' : 'Failed'} />
              <Link to={`/tasks/${r.taskId}`} className="task-key">{r.taskKey}</Link>
              <span className="run-message">{r.message}</span>
              <span className="muted small">{timeAgo(r.ranAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}

function RuleModal({ projectKey, members, rule, initial, onClose, onSaved }: {
  projectKey: string; members: Member[]; rule: AutomationRule | null; initial: RuleInput; onClose: () => void;
  onSaved: (name: string) => void;
}) {
  const [form, setForm] = useState<RuleInput>(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const editors = members.filter((m) => m.role !== 'VIEWER');

  const setAction = (index: number, action: RuleAction) =>
    setForm({ ...form, actions: form.actions.map((a, i) => (i === index ? action : a)) });

  const defaultValue = (type: RuleActionType) => {
    switch (type) {
      case 'assign': return 'least_loaded';
      case 'set_status': return 'DONE';
      case 'set_priority': return 'HIGH';
      case 'set_due_in_days': return '3';
      case 'notify': return 'assignee';
      default: return '';
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    setError('');
    const input = { ...form, name: form.name.trim(), triggerStatus: form.trigger === 'STATUS_CHANGED' ? form.triggerStatus : null };
    try {
      if (rule) await api.updateAutomation(rule.id, { ...input, enabled: rule.enabled });
      else await api.createAutomation(projectKey, input);
      onSaved(input.name);
    } catch (e) {
      const err = e as ApiError;
      setErrors(err.fieldErrors ?? {});
      setError(Object.keys(err.fieldErrors ?? {}).length ? '' : err.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={rule ? `Edit ${rule.name}` : 'New rule'} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="rule-form" disabled={busy || !form.name.trim() || form.actions.length === 0}>{t("Save rule")}</button>
      </>
    }>
      <form id="rule-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={form.name} maxLength={80} autoFocus placeholder={t("Triage new bugs")} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
        <div className="form-grid two">
          <label className="field">
            <span>{t("When")}</span>
            <select value={form.trigger} onChange={(e) => setForm({ ...form, trigger: e.target.value as RuleTrigger })}>
              {TRIGGERS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </label>
          {form.trigger === 'STATUS_CHANGED' && (
            <label className="field">
              <span>{t("To status")}</span>
              <select value={form.triggerStatus ?? ''} onChange={(e) => setForm({ ...form, triggerStatus: (e.target.value || null) as RuleInput['triggerStatus'] })}>
                <option value="">{t("Any status")}</option>
                {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
              </select>
            </label>
          )}
        </div>
        <div className="field">
          <span>{form.trigger === 'SCHEDULED' ? 'For tasks matching' : 'Only if the task matches'} <span className="muted">
            {form.trigger === 'SCHEDULED' ? '(required)' : '(optional)'}</span></span>
          <FqlInput value={form.condition} onChange={(condition) => setForm({ ...form, condition })} onSubmit={() => {}} />
          {errors.condition ? <span className="field-error">{errors.condition}</span> : (
            <span className="muted small">{t("Same language as Search, e.g.")} <code>type = bug AND priority &gt;= high</code>
              {form.trigger === 'SCHEDULED' && <> or <code>status = todo AND updated &lt; -4h</code>. Each task is handled once while it keeps matching.</>}
            </span>
          )}
        </div>
        <fieldset className="field rule-actions">
          <legend>{t("Then")}</legend>
          {form.actions.map((action, index) => (
            <div key={index} className="rule-action">
              <select aria-label={`Action ${index + 1}`} value={action.type}
                onChange={(e) => setAction(index, { type: e.target.value as RuleActionType, value: defaultValue(e.target.value as RuleActionType) })}>
                {ACTIONS.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
              </select>
              <ActionValue action={action} editors={editors} onChange={(value) => setAction(index, { ...action, value })} index={index} />
              <button type="button" className="icon-button" aria-label={`Remove action ${index + 1}`}
                onClick={() => setForm({ ...form, actions: form.actions.filter((_, i) => i !== index) })}><X size={16} /></button>
            </div>
          ))}
          {errors.actions && <span className="field-error">{errors.actions}</span>}
          {form.actions.length < 8 && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setForm({ ...form, actions: [...form.actions, { type: 'add_label', value: '' }] })}>
              <Plus size={15} /> Add action
            </button>
          )}
        </fieldset>
      </form>
    </Modal>
  );
}

function ActionValue({ action, editors, onChange, index }: {
  action: RuleAction; editors: Member[]; onChange: (value: string) => void; index: number;
}) {
  const label = `Value for action ${index + 1}`;
  switch (action.type) {
    case 'assign':
      return (
        <select aria-label={label} value={action.value} onChange={(e) => onChange(e.target.value)}>
          {ASSIGN_CHOICES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          {editors.map((m) => <option key={m.id} value={m.username}>{m.displayName}</option>)}
        </select>
      );
    case 'set_status':
      return (
        <select aria-label={label} value={action.value} onChange={(e) => onChange(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      );
    case 'set_priority':
      return (
        <select aria-label={label} value={action.value} onChange={(e) => onChange(e.target.value)}>
          {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_LABEL[p]}</option>)}
        </select>
      );
    case 'set_due_in_days':
      return <input aria-label={label} type="number" min={0} max={365} value={action.value ?? ''} onChange={(e) => onChange(e.target.value)} />;
    case 'comment':
      return <textarea aria-label={label} rows={2} maxLength={2000} value={action.value ?? ''} placeholder="Use {{key}}, {{title}}, {{assignee}}, {{reporter}}"
        onChange={(e) => onChange(e.target.value)} />;
    case 'notify':
      return <input aria-label={label} value={action.value ?? ''} placeholder={t("assignee, reporter, watchers, owner or usernames")}
        onChange={(e) => onChange(e.target.value)} />;
    case 'move_to_active_sprint':
      return <span className="muted small">{t("No settings")}</span>;
    default:
      return <input aria-label={label} value={action.value ?? ''} maxLength={30} placeholder={t("label")} onChange={(e) => onChange(e.target.value)} />;
  }
}
