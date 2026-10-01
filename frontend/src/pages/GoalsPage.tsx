import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Flag, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { useProjects } from '../projects';
import { Avatar } from '../components/Avatar';
import { ConfirmDialog, Modal } from '../components/Modal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import type { Epic, Goal, GoalHealth, KeyResult, KeyResultInput, KeyResultKind } from '../types';
import { t } from '../i18n';

function quarterOf(date: Date) {
  return `${date.getFullYear()}-Q${Math.floor(date.getMonth() / 3) + 1}`;
}

function shiftQuarter(quarter: string, delta: number) {
  const year = Number(quarter.slice(0, 4));
  const q = Number(quarter.slice(6)) - 1 + delta;
  return `${year + Math.floor(q / 4)}-Q${((q % 4) + 4) % 4 + 1}`;
}

const HEALTH: Record<GoalHealth, string> = {
  none: 'No key results', done: 'Achieved', on_track: 'On track', at_risk: 'At risk', off_track: 'Off track',
};

/** Quarterly objectives with key results that track numbers or the progress of linked epics. */
export function GoalsPage() {
  const toast = useToast();
  const [quarter, setQuarter] = useState(quarterOf(new Date()));
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Goal | 'new' | null>(null);
  const [krFor, setKrFor] = useState<{ goal: Goal; kr: KeyResult | null } | null>(null);
  const [deleting, setDeleting] = useState<Goal | null>(null);

  const load = useCallback(() => {
    setGoals(null);
    api.goals(quarter).then(setGoals).catch((e: ApiError) => setError(e.message));
  }, [quarter]);
  useEffect(load, [load]);
  const replace = (goal: Goal) => setGoals((list) => list?.map((g) => (g.id === goal.id ? goal : g)) ?? null);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t('Workspace')}</span>
          <h1>{t('Goals')}</h1>
          <p className="muted">{t('Objectives for the quarter and the key results that measure them. Link epics and progress updates itself.')}</p>
        </div>
        <div className="header-actions">
          <div className="btn-group">
            <button className="btn btn-ghost btn-sm" onClick={() => setQuarter(shiftQuarter(quarter, -1))} aria-label={t('Previous quarter')}>‹</button>
            <strong className="quarter-label">{quarter.replace('-', ' ')}</strong>
            <button className="btn btn-ghost btn-sm" onClick={() => setQuarter(shiftQuarter(quarter, 1))} aria-label={t('Next quarter')}>›</button>
          </div>
          <button className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={16} /> {t('New goal')}</button>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!goals && !error && <Spinner />}
      {goals && goals.length === 0 && (
        <EmptyState icon={<Flag size={28} />} title={t('No goals for {quarter}', { quarter })}>
          <button className="link" onClick={() => setEditing('new')}>{t('Set the first one')}</button>
        </EmptyState>
      )}
      {goals?.map((goal) => (
        <article key={goal.id} className={`panel goal-card health-${goal.health}`}>
          <header className="goal-head">
            <div>
              <h2>{goal.title}</h2>
              <span className="muted small"><Avatar user={goal.owner} size={18} /> {goal.owner.displayName}
                {goal.shared ? ` · ${t('shared with everyone')}` : ''}</span>
            </div>
            <div className="goal-score">
              <span className={`health-badge health-${goal.health}`}>{t(HEALTH[goal.health])}</span>
              <strong>{goal.percent}%</strong>
            </div>
          </header>
          {goal.description && <p className="goal-description">{goal.description}</p>}
          <div className="goal-progress" title={t('{p}% of the quarter has passed', { p: goal.expected })}>
            <div className="progress"><span style={{ width: `${goal.percent}%` }} /></div>
            <span className="goal-expected" style={{ left: `${goal.expected}%` }} aria-hidden />
          </div>
          <ul className="kr-list">
            {goal.keyResults.map((kr) => (
              <li key={kr.id}>
                <div className="kr-main">
                  <span className="kr-title">{kr.title}</span>
                  <span className="muted small">
                    {kr.kind === 'MANUAL'
                      ? `${kr.current ?? kr.startValue}${kr.unit ?? ''} → ${kr.target}${kr.unit ?? ''}`
                      : kr.epics.map((e) => e.id ? `${e.name} ${e.done}/${e.total}` : `${e.projectKey} ${e.done}/${e.total}`).join(' · ')}
                  </span>
                </div>
                <div className="kr-bar"><div className="progress"><span style={{ width: `${kr.percent}%` }} /></div><span className="small">{kr.percent}%</span></div>
                {goal.canEdit && kr.kind === 'MANUAL' && (
                  <input className="kr-current" type="number" step="any" aria-label={t('Current value of {kr}', { kr: kr.title })}
                    key={`${kr.id}-${kr.current}`} defaultValue={kr.current ?? ''}
                    onBlur={async (e) => {
                      if (e.target.value === '' || Number(e.target.value) === kr.current) return;
                      try {
                        replace(await api.updateKeyResult(kr.id, { title: kr.title, kind: kr.kind, startValue: kr.startValue,
                          target: kr.target, current: Number(e.target.value), unit: kr.unit }));
                      } catch (err) {
                        toast((err as ApiError).message, 'error');
                      }
                    }} />
                )}
                {goal.canEdit && (
                  <span className="kr-actions">
                    <button className="icon-button sm" aria-label={t('Edit {name}', { name: kr.title })} onClick={() => setKrFor({ goal, kr })}><Pencil size={13} /></button>
                    <button className="icon-button sm" aria-label={t('Delete {name}', { name: kr.title })}
                      onClick={async () => replace(await api.deleteKeyResult(kr.id))}><Trash2 size={13} /></button>
                  </span>
                )}
              </li>
            ))}
          </ul>
          {goal.canEdit && (
            <footer className="goal-actions">
              <button className="btn btn-soft btn-sm" onClick={() => setKrFor({ goal, kr: null })}><Plus size={14} /> {t('Key result')}</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setEditing(goal)}><Pencil size={14} /> {t('Edit')}</button>
              <button className="btn btn-ghost btn-sm danger" onClick={() => setDeleting(goal)}><Trash2 size={14} /> {t('Delete')}</button>
            </footer>
          )}
        </article>
      ))}
      {editing && <GoalModal goal={editing === 'new' ? null : editing} quarter={quarter} onClose={() => setEditing(null)}
        onSaved={(goal) => {
          setEditing(null);
          if (goal.quarter !== quarter) setQuarter(goal.quarter);
          else load();
        }} />}
      {krFor && <KeyResultModal goal={krFor.goal} kr={krFor.kr} onClose={() => setKrFor(null)} onSaved={(goal) => {
        setKrFor(null);
        replace(goal);
      }} />}
      {deleting && (
        <ConfirmDialog title={t('Delete {name}?', { name: deleting.title })} confirmLabel={t('Delete')} danger
          message={t('The goal and its key results are removed. Epics and tasks are not affected.')}
          onClose={() => setDeleting(null)} onConfirm={async () => {
            await api.deleteGoal(deleting.id).catch((e: ApiError) => toast(e.message, 'error'));
            setDeleting(null);
            load();
          }} />
      )}
    </div>
  );
}

function GoalModal({ goal, quarter, onClose, onSaved }: { goal: Goal | null; quarter: string; onClose: () => void; onSaved: (goal: Goal) => void }) {
  const [title, setTitle] = useState(goal?.title ?? '');
  const [description, setDescription] = useState(goal?.description ?? '');
  const [q, setQ] = useState(goal?.quarter ?? quarter);
  const [shared, setShared] = useState(goal?.shared ?? false);
  const [error, setError] = useState('');
  const quarters = useMemo(() => [-1, 0, 1, 2, 3].map((d) => shiftQuarter(quarterOf(new Date()), d)), []);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = { title: title.trim(), description: description.trim(), quarter: q, shared };
    try {
      onSaved(goal ? await api.updateGoal(goal.id, input) : await api.createGoal(input));
    } catch (e) {
      setError((e as ApiError).message);
    }
  };
  return (
    <Modal title={goal ? t('Edit goal') : t('New goal')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" form="goal-form" disabled={!title.trim()}>{t('Save')}</button>
      </>
    }>
      <form id="goal-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field"><span>{t('Objective')}</span>
          <input value={title} maxLength={120} autoFocus placeholder={t('e.g. Make checkout effortless')} onChange={(e) => setTitle(e.target.value)} /></label>
        <label className="field"><span>{t('Why it matters')} <span className="muted">{t('(optional)')}</span></span>
          <textarea rows={3} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <label className="field"><span>{t('Quarter')}</span>
          <select value={q} onChange={(e) => setQ(e.target.value)}>
            {[...new Set([q, ...quarters])].sort().map((x) => <option key={x} value={x}>{x.replace('-', ' ')}</option>)}
          </select></label>
        <label className="toggle"><input type="checkbox" checked={shared} onChange={(e) => setShared(e.target.checked)} />
          {t('Visible to everyone (otherwise only to members of projects whose epics it tracks)')}</label>
      </form>
    </Modal>
  );
}

function KeyResultModal({ goal, kr, onClose, onSaved }: { goal: Goal; kr: KeyResult | null; onClose: () => void; onSaved: (goal: Goal) => void }) {
  const { projects } = useProjects();
  const [title, setTitle] = useState(kr?.title ?? '');
  const [kind, setKind] = useState<KeyResultKind>(kr?.kind ?? 'EPICS');
  const [start, setStart] = useState(kr?.startValue?.toString() ?? '0');
  const [target, setTarget] = useState(kr?.target?.toString() ?? '');
  const [current, setCurrent] = useState(kr?.current?.toString() ?? '');
  const [unit, setUnit] = useState(kr?.unit ?? '');
  const [epics, setEpics] = useState<(Epic & { projectKey: string })[]>([]);
  const [picked, setPicked] = useState<number[]>(kr?.epics.map((e) => e.id).filter((id): id is number => id !== null) ?? []);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all((projects ?? []).map((p) => api.epics(p.key).then((list) => list.map((e) => ({ ...e, projectKey: p.key })))
      .catch(() => []))).then((lists) => setEpics(lists.flat()));
  }, [projects]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input: KeyResultInput = kind === 'MANUAL'
      ? { title: title.trim(), kind, startValue: Number(start || 0), target: target === '' ? null : Number(target),
        current: current === '' ? null : Number(current), unit: unit.trim() || null }
      : { title: title.trim(), kind, epicIds: picked };
    try {
      onSaved(kr ? await api.updateKeyResult(kr.id, input) : await api.addKeyResult(goal.id, input));
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <Modal title={kr ? t('Edit key result') : t('New key result')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" form="kr-form" disabled={!title.trim()}>{t('Save')}</button>
      </>
    }>
      <form id="kr-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field"><span>{t('Key result')}</span>
          <input value={title} maxLength={160} autoFocus placeholder={t('e.g. Checkout conversion from 2% to 4%')} onChange={(e) => setTitle(e.target.value)} /></label>
        <div className="segmented" role="radiogroup" aria-label={t('Measured by')}>
          {(['EPICS', 'MANUAL'] as KeyResultKind[]).map((k) => (
            <label key={k} className={kind === k ? 'active' : ''}>
              <input type="radio" name="kind" checked={kind === k} onChange={() => setKind(k)} />
              {k === 'EPICS' ? t('Progress of epics') : t('A number I update')}
            </label>
          ))}
        </div>
        {kind === 'MANUAL' ? (
          <div className="form-grid two">
            <label className="field"><span>{t('Start')}</span><input type="number" step="any" value={start} onChange={(e) => setStart(e.target.value)} /></label>
            <label className="field"><span>{t('Target')}</span><input type="number" step="any" required value={target} onChange={(e) => setTarget(e.target.value)} /></label>
            <label className="field"><span>{t('Current')}</span><input type="number" step="any" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
            <label className="field"><span>{t('Unit')} <span className="muted">{t('(optional)')}</span></span>
              <input value={unit} maxLength={20} placeholder="%" onChange={(e) => setUnit(e.target.value)} /></label>
          </div>
        ) : (
          <fieldset className="field epic-picks">
            <legend>{t('Epics')}</legend>
            {epics.length === 0 && <p className="muted small">{t('No epics in your projects yet.')} <Link to="/projects">{t('Projects')}</Link></p>}
            {epics.map((e) => (
              <label key={e.id} className="toggle">
                <input type="checkbox" checked={picked.includes(e.id)}
                  onChange={(ev) => setPicked(ev.target.checked ? [...picked, e.id] : picked.filter((x) => x !== e.id))} />
                <span className="muted small">{e.projectKey}</span> {e.name}
                <span className="muted small"> · {e.doneCount}/{e.taskCount}</span>
              </label>
            ))}
          </fieldset>
        )}
      </form>
    </Modal>
  );
}
