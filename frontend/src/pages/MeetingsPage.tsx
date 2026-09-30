import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Gavel, NotebookPen, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { MeetingNotes } from '../components/MeetingNotes';
import { EmptyState, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDate } from '../format';
import type { DecisionEntry, Sprint } from '../types';
import { t } from '../i18n';

/** Meeting notes and the decision log of a project. */
export function MeetingsPage() {
  const { key, project, loading, canEdit, isOwner } = useRouteProject();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'decisions' ? 'decisions' : 'notes';
  const [sprints, setSprints] = useState<Sprint[]>([]);
  useEffect(() => {
    if (project) api.sprints(key).then(setSprints).catch(() => {});
  }, [key, project]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t('Meetings & decisions')}</h1>
          <p className="muted">{t('Notes from stand-ups and planning, and a log of what the team decided and why.')}</p>
        </div>
      </header>
      <nav className="tabs" role="tablist" aria-label={t('Meetings & decisions')}>
        <button role="tab" aria-selected={tab === 'notes'} className={`tab ${tab === 'notes' ? 'active' : ''}`}
          onClick={() => setParams({}, { replace: true })}><NotebookPen size={15} /> {t('Meeting notes')}</button>
        <button role="tab" aria-selected={tab === 'decisions'} className={`tab ${tab === 'decisions' ? 'active' : ''}`}
          onClick={() => setParams({ tab: 'decisions' }, { replace: true })}><Gavel size={15} /> {t('Decisions')}</button>
      </nav>
      {tab === 'notes'
        ? <MeetingNotes projectKey={key} projectId={project.id} members={project.members} canEdit={canEdit}
            sprints={sprints.filter((s) => s.state !== 'COMPLETED')} />
        : <DecisionLog projectKey={key} projectId={project.id} canEdit={canEdit} isOwner={isOwner} />}
    </div>
  );
}

function DecisionLog({ projectKey, projectId, canEdit, isOwner }: { projectKey: string; projectId: number; canEdit: boolean; isOwner: boolean }) {
  const toast = useToast();
  const { user } = useAuth();
  const [decisions, setDecisions] = useState<DecisionEntry[] | null>(null);
  const [text, setText] = useState('');
  const [context, setContext] = useState('');
  const load = useCallback(() => {
    api.decisions(projectKey).then(setDecisions).catch(() => setDecisions([]));
  }, [projectKey]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === projectId, load, 800);

  const record = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await api.recordDecision(projectKey, text.trim(), context.trim());
      setText('');
      setContext('');
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <div className="decisions">
      {canEdit && (
        <form className="panel form decision-form" onSubmit={record}>
          <label className="field"><span>{t('Decision')}</span>
            <input value={text} maxLength={500} placeholder={t('e.g. We release on Tuesdays')} onChange={(e) => setText(e.target.value)} /></label>
          <label className="field"><span>{t('Why')} <span className="muted">{t('(optional)')}</span></span>
            <textarea rows={2} maxLength={2000} value={context} onChange={(e) => setContext(e.target.value)} /></label>
          <div><button className="btn btn-primary btn-sm" disabled={!text.trim()}>{t('Record decision')}</button></div>
        </form>
      )}
      {!decisions && <Spinner />}
      {decisions && decisions.length === 0 && (
        <EmptyState icon={<Gavel size={28} />} title={t('No decisions yet')}>
          {t('Record them here, or close a poll on a task to log its result.')}
        </EmptyState>
      )}
      <ul className="decision-list">
        {decisions?.map((d) => (
          <li key={d.id} className="panel">
            <div className="decision-main">
              <strong>{d.text}</strong>
              {d.context && <p className="muted small">{d.context}</p>}
              <span className="muted small"><Avatar user={d.decidedBy} size={16} /> {d.decidedBy.displayName} · {formatDate(d.decidedAt)}
                {d.task && <> · <Link to={`/tasks/${d.task.id}`}>{d.task.key}</Link></>}</span>
            </div>
            {canEdit && (isOwner || d.decidedBy.id === user?.id) && (
              <button className="icon-button" aria-label={t('Remove decision')} onClick={async () => {
                await api.deleteDecision(d.id).catch((e: ApiError) => toast(e.message, 'error'));
                load();
              }}><Trash2 size={15} /></button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
