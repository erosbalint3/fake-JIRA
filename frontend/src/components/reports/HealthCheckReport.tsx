import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { HeartPulse, Lock, Plus, Trash2, Unlock } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { formatDay } from '../../format';
import type { HealthCheckDetail, HealthCheckSummary } from '../../types';
import { EmptyState, Spinner } from '../States';
import { Modal } from '../Modal';
import { t } from '../../i18n';

const SCORES = [
  { value: 3, label: 'Green', hint: 'Great' },
  { value: 2, label: 'Amber', hint: 'Some problems' },
  { value: 1, label: 'Red', hint: 'Really bad' },
];
const TRENDS = [
  { value: 1, label: 'Improving', symbol: '↑' },
  { value: 0, label: 'Stable', symbol: '→' },
  { value: -1, label: 'Getting worse', symbol: '↓' },
];

function tone(average: number | null | undefined) {
  if (average == null) return 'none';
  return average >= 2.5 ? 'green' : average >= 1.75 ? 'amber' : 'red';
}

/** Team health checks: anonymous traffic-light votes per area, with the history across checks. */
export function HealthCheckReport({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const toast = useToast();
  const [checks, setChecks] = useState<HealthCheckSummary[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<HealthCheckDetail | null>(null);
  const [creating, setCreating] = useState(false);

  const loadList = useCallback(() => {
    api.healthChecks(projectKey).then((list) => {
      setChecks(list);
      setSelected((current) => (current && list.some((c) => c.id === current) ? current : list[0]?.id ?? null));
    }).catch(() => setChecks([]));
  }, [projectKey]);
  const loadDetail = useCallback(() => {
    if (selected) api.healthCheck(selected).then(setDetail).catch(() => setDetail(null));
    else setDetail(null);
  }, [selected]);
  useEffect(loadList, [loadList]);
  useEffect(loadDetail, [loadDetail]);
  useLiveRefresh((m) => m.type === 'project', () => {
    loadList();
    loadDetail();
  }, 800);

  const act = async (action: Promise<HealthCheckDetail | void>, message: string) => {
    try {
      const result = await action;
      if (result) setDetail(result);
      toast(message);
      loadList();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (!checks) return <Spinner />;
  const history = [...checks].reverse().filter((c) => Object.keys(c.averages).length > 0);
  const categories = Array.from(new Set(history.flatMap((c) => c.categories)));

  return (
    <>
      <section className="panel">
        <div className="panel-title-row">
          <h2 className="panel-title"><HeartPulse size={16} aria-hidden /> {t('Team health check')}</h2>
          <div className="row-actions">
            {checks.length > 0 && (
              <select value={selected ?? ''} onChange={(e) => setSelected(Number(e.target.value))} aria-label={t('Health check')}>
                {checks.map((c) => <option key={c.id} value={c.id}>{c.title} · {formatDay(c.createdAt.slice(0, 10))}{c.closed ? ` (${t('closed')})` : ''}</option>)}
              </select>
            )}
            {canEdit && <button className="btn btn-soft" onClick={() => setCreating(true)}><Plus size={16} /> {t('New health check')}</button>}
          </div>
        </div>
        <p className="muted small hint">{t('Votes are anonymous. Results show once you have voted or the check is closed.')}</p>
        {checks.length === 0 && (
          <EmptyState icon={<HeartPulse size={28} />} title={t('No health checks yet')}>
            {t('Ask the team how things are going: speed, fun, codebase health and more, rated green, amber or red.')}
          </EmptyState>
        )}
        {detail && <CheckView detail={detail} canEdit={canEdit}
          onVote={(votes) => act(api.voteHealthCheck(detail.check.id, votes), t('Thanks, your votes are saved'))}
          onToggle={() => act(api.toggleHealthCheck(detail.check.id), detail.check.closed ? t('Health check reopened') : t('Health check closed'))}
          onDelete={() => act(api.deleteHealthCheck(detail.check.id), t('Health check deleted'))} />}
      </section>

      {history.length > 1 && (
        <section className="panel">
          <h2 className="panel-title">{t('Over time')}</h2>
          <div className="table-scroll">
            <table className="viz-table health-history">
              <thead>
                <tr><th>{t('Area')}</th>{history.map((c) => <th key={c.id}>{c.title}</th>)}</tr>
              </thead>
              <tbody>
                {categories.map((category) => (
                  <tr key={category}>
                    <th scope="row">{category}</th>
                    {history.map((c) => {
                      const avg = c.averages[category];
                      return (
                        <td key={c.id}>
                          <span className={`health-dot ${tone(avg)}`} aria-hidden />
                          <span className="small">{avg == null ? '–' : avg.toFixed(1)}</span>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {creating && <CreateModal onClose={() => setCreating(false)} onCreate={async (title, list) => {
        try {
          const created = await api.createHealthCheck(projectKey, title, list);
          setCreating(false);
          setSelected(created.check.id);
          setDetail(created);
          loadList();
        } catch (e) {
          toast((e as ApiError).message, 'error');
        }
      }} />}
    </>
  );
}

function CheckView({ detail, canEdit, onVote, onToggle, onDelete }: {
  detail: HealthCheckDetail; canEdit: boolean;
  onVote: (votes: { category: string; score: number; trend: number }[]) => void;
  onToggle: () => void; onDelete: () => void;
}) {
  const { check } = detail;
  const [votes, setVotes] = useState<Record<string, { score: number; trend: number }>>({});
  const [editing, setEditing] = useState(detail.mine.length === 0);
  useEffect(() => {
    const mine: Record<string, { score: number; trend: number }> = {};
    detail.mine.forEach((v) => { mine[v.category] = { score: v.score, trend: v.trend }; });
    setVotes(mine);
    setEditing(detail.mine.length === 0);
  }, [detail.check.id, detail.mine]);

  const canVote = canEdit && !check.closed;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onVote(Object.entries(votes).map(([category, v]) => ({ category, ...v })));
    setEditing(false);
  };

  return (
    <div className="health-check">
      <p className="muted">
        {t('{n} of {m} team members voted', { n: check.voters, m: detail.members })}
        {check.closed && ` · ${t('closed')}`}
      </p>
      {canVote && editing && (
        <form onSubmit={submit} className="health-vote-form">
          {check.categories.map((category) => {
            const vote = votes[category];
            return (
              <fieldset key={category} className="health-row">
                <legend>{category}</legend>
                <div className="health-scores" role="radiogroup" aria-label={t('{area}: rating', { area: category })}>
                  {SCORES.map((s) => (
                    <label key={s.value} className={`health-choice ${tone(s.value)} ${vote?.score === s.value ? 'active' : ''}`}>
                      <input type="radio" name={`score-${category}`} checked={vote?.score === s.value}
                        onChange={() => setVotes({ ...votes, [category]: { score: s.value, trend: vote?.trend ?? 0 } })} />
                      {t(s.label)} <span className="muted small">{t(s.hint)}</span>
                    </label>
                  ))}
                </div>
                <select value={vote?.trend ?? 0} disabled={!vote} aria-label={t('{area}: direction', { area: category })}
                  onChange={(e) => setVotes({ ...votes, [category]: { score: vote!.score, trend: Number(e.target.value) } })}>
                  {TRENDS.map((tr) => <option key={tr.value} value={tr.value}>{tr.symbol} {t(tr.label)}</option>)}
                </select>
              </fieldset>
            );
          })}
          <button className="btn btn-primary" type="submit" disabled={Object.keys(votes).length === 0}>{t('Submit my votes')}</button>
        </form>
      )}
      {detail.resultsVisible && !(canVote && editing) && (
        <>
          <table className="viz-table health-results">
            <thead><tr><th>{t('Area')}</th><th>{t('Votes')}</th><th>{t('Average')}</th><th>{t('Direction')}</th></tr></thead>
            <tbody>
              {detail.results.map((r) => {
                const total = r.red + r.amber + r.green;
                return (
                  <tr key={r.category}>
                    <th scope="row">{r.category}</th>
                    <td>
                      <div className="health-bar" role="img"
                        aria-label={t('{g} green, {a} amber, {r} red', { g: r.green, a: r.amber, r: r.red })}>
                        {total > 0 && <>
                          <span className="green" style={{ width: `${(r.green / total) * 100}%` }} />
                          <span className="amber" style={{ width: `${(r.amber / total) * 100}%` }} />
                          <span className="red" style={{ width: `${(r.red / total) * 100}%` }} />
                        </>}
                      </div>
                    </td>
                    <td><span className={`health-dot ${tone(r.average)}`} aria-hidden /> {r.average == null ? '–' : r.average.toFixed(1)}</td>
                    <td className="small">↑ {r.better} · → {r.stable} · ↓ {r.worse}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {canVote && <button className="btn btn-ghost" onClick={() => setEditing(true)}>{t('Change my votes')}</button>}
        </>
      )}
      {!detail.resultsVisible && !canVote && <p className="muted">{t('Results appear when the check is closed.')}</p>}
      {detail.canManage && (
        <div className="row-actions">
          <button className="btn btn-soft" onClick={onToggle}>
            {check.closed ? <><Unlock size={16} /> {t('Reopen')}</> : <><Lock size={16} /> {t('Close and show results')}</>}
          </button>
          <button className="btn btn-ghost danger" onClick={onDelete}><Trash2 size={16} /> {t('Delete')}</button>
        </div>
      )}
    </div>
  );
}

const DEFAULT_AREAS = ['Delivering value', 'Speed', 'Easy to release', 'Health of the codebase', 'Learning', 'Fun',
  'Teamwork', 'Support', 'Mission', 'Suitable process'];

function CreateModal({ onCreate, onClose }: { onCreate: (title: string, categories: string[]) => void; onClose: () => void }) {
  const [title, setTitle] = useState(t('Health check {date}', { date: formatDay(new Date().toISOString().slice(0, 10), true) }));
  const [areas, setAreas] = useState(() => DEFAULT_AREAS.map((a) => t(a)).join('\n'));
  const list = areas.split('\n').map((a) => a.trim()).filter(Boolean);
  return (
    <Modal title={t('New health check')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" type="submit" form="health-form" disabled={!title.trim() || list.length === 0 || list.length > 12}>
          {t('Start')}
        </button>
      </>
    }>
      <form id="health-form" className="form" onSubmit={(e) => {
        e.preventDefault();
        onCreate(title.trim(), list);
      }}>
        <label className="field">
          <span>{t('Title')}</span>
          <input value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('Areas to rate (one per line, up to 12)')}</span>
          <textarea rows={8} value={areas} onChange={(e) => setAreas(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
