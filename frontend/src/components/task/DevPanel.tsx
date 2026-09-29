import { GitCommitHorizontal, GitMerge, GitPullRequest, GitPullRequestClosed } from 'lucide-react';
import { timeAgo } from '../../format';
import type { DevLink } from '../../types';

/** Commits and pull requests from GitHub that mention this task. */
export function DevPanel({ links }: { links: DevLink[] }) {
  if (links.length === 0) return null;
  return (
    <section className="panel">
      <h2 className="panel-title"><GitPullRequest size={16} /> Development</h2>
      <ul className="dev-list">
        {links.map((link) => {
          const Icon = link.kind === 'COMMIT' ? GitCommitHorizontal
            : link.state === 'merged' ? GitMerge : link.state === 'closed' ? GitPullRequestClosed : GitPullRequest;
          return (
            <li key={link.id}>
              <Icon size={16} className={`dev-icon dev-${link.state ?? 'commit'}`} aria-hidden />
              <a href={link.url} target="_blank" rel="noopener noreferrer">{link.title}</a>
              {link.state && <span className={`dev-state dev-${link.state}`}>{link.state}</span>}
              <span className="muted small">{link.author} · {timeAgo(link.updatedAt)}</span>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
