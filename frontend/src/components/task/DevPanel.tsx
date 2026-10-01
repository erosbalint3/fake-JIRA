import { useCallback, useEffect, useState } from 'react';
import {
  CircleCheck, CircleDashed, CircleSlash, CircleX, ExternalLink, GitBranch, GitCommitHorizontal, GitMerge, GitPullRequest,
  GitPullRequestClosed, LoaderCircle,
} from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { timeAgo } from '../../format';
import type { BuildState, DevLink, TaskGithub } from '../../types';
import { t } from '../../i18n';

export const BUILD_ICON: Record<BuildState, typeof CircleCheck> = {
  success: CircleCheck, failure: CircleX, running: LoaderCircle, pending: CircleDashed, cancelled: CircleSlash,
};

export const BUILD_LABEL: Record<BuildState, string> = {
  success: 'Passed', failure: 'Failed', running: 'Running', pending: 'Pending', cancelled: 'Cancelled',
};

/** Branches, commits, pull requests and CI results for the task, with buttons to start work on GitHub. */
export function DevPanel({ taskId, links, onChanged }: { taskId: number; links: DevLink[]; onChanged: () => void }) {
  const toast = useToast();
  const [github, setGithub] = useState<TaskGithub | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => {
    api.taskGithub(taskId).then(setGithub).catch(() => setGithub(null));
  }, [taskId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, load, 600);

  const act = async (action: () => Promise<{ url: string }>, message: string) => {
    setBusy(true);
    try {
      const result = await action();
      toast(message);
      onChanged();
      window.open(result.url, '_blank', 'noopener');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const builds = github?.builds ?? [];
  const hasBranch = links.some((l) => l.kind === 'BRANCH');
  const hasPr = links.some((l) => l.kind === 'PULL_REQUEST');
  if (links.length === 0 && builds.length === 0 && !github?.canCreate && !github?.issueUrl) return null;

  return (
    <section className="panel dev-panel">
      <h2 className="panel-title"><GitPullRequest size={16} /> {t('Development')}</h2>
      {github?.issueUrl && (
        <p className="small"><a href={github.issueUrl} target="_blank" rel="noopener noreferrer">
          <ExternalLink size={13} aria-hidden /> {t('Synced with GitHub issue #{n}', { n: github.issueNumber ?? '' })}</a></p>
      )}
      {github?.canCreate && (
        <div className="row-actions dev-actions">
          {!hasBranch && (
            <button className="btn btn-soft btn-sm" disabled={busy}
              onClick={() => act(() => api.createBranch(taskId), t('Branch created on GitHub'))}>
              <GitBranch size={14} /> {t('Create branch')}
            </button>
          )}
          {!hasPr && (
            <button className="btn btn-soft btn-sm" disabled={busy}
              onClick={() => act(() => api.createPullRequest(taskId, true), t('Draft pull request opened'))}>
              <GitPullRequest size={14} /> {t('Open draft pull request')}
            </button>
          )}
          <span className="muted small">{github.repo}</span>
        </div>
      )}
      {builds.length > 0 && (
        <ul className="dev-list builds">
          {builds.map((b) => {
            const Icon = BUILD_ICON[b.state];
            return (
              <li key={`${b.source}-${b.name}`}>
                <Icon size={16} className={`build-icon build-${b.state}`} aria-hidden />
                {b.url ? <a href={b.url} target="_blank" rel="noopener noreferrer">{b.name}</a> : <span>{b.name}</span>}
                <span className={`build-state build-${b.state}`}>{t(BUILD_LABEL[b.state])}</span>
                <span className="muted small">{b.source}{b.ref ? ` · ${b.ref}` : ''} · {timeAgo(b.updatedAt)}</span>
              </li>
            );
          })}
        </ul>
      )}
      {links.length > 0 && (
        <ul className="dev-list">
          {links.map((link) => {
            const Icon = link.kind === 'COMMIT' ? GitCommitHorizontal : link.kind === 'BRANCH' ? GitBranch
              : link.state === 'merged' ? GitMerge : link.state === 'closed' ? GitPullRequestClosed : GitPullRequest;
            return (
              <li key={link.id}>
                <Icon size={16} className={`dev-icon dev-${link.kind === 'PULL_REQUEST' ? link.state ?? 'open' : link.kind.toLowerCase()}`} aria-hidden />
                <a href={link.url} target="_blank" rel="noopener noreferrer">{link.title}</a>
                {link.kind === 'PULL_REQUEST' && link.state && <span className={`dev-state dev-${link.state}`}>{link.state}</span>}
                <span className="muted small">{link.author} · {timeAgo(link.updatedAt)}</span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
