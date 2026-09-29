import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity as ActivityIcon, MessageSquare } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useProjects } from '../projects';
import { Avatar } from '../components/Avatar';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { formatDate, timeAgo } from '../format';
import type { FeedItem } from '../types';

const PAGE = 40;

/** Everything happening across your projects, newest first. */
export function ActivityPage() {
  const { projects } = useProjects();
  const [project, setProject] = useState('');
  const [person, setPerson] = useState('');
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [more, setMore] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.feed({ project: project || undefined, user: person || undefined, limit: PAGE })
      .then((list) => {
        setItems(list);
        setMore(list.length === PAGE);
      })
      .catch((e: ApiError) => setError(e.message));
  }, [project, person]);
  useEffect(() => {
    setItems(null);
    load();
  }, [load]);
  useLiveRefresh((m) => m.type === 'task', load, 1500);

  const loadMore = () => {
    const last = items?.[items.length - 1];
    if (!last) return;
    api.feed({ project: project || undefined, user: person || undefined, before: last.createdAt, limit: PAGE })
      .then((list) => {
        setItems((current) => [...(current ?? []), ...list.filter((i) => !current?.some((c) => c.kind === i.kind && c.id === i.id))]);
        setMore(list.length === PAGE);
      })
      .catch((e: ApiError) => setError(e.message));
  };

  const people = new Map<string, string>();
  (projects ?? []).forEach((p) => p.members.forEach((m) => people.set(m.username, m.displayName)));

  let lastDay = '';
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Activity</span>
          <h1>What's happening</h1>
        </div>
        <div className="header-actions">
          <select value={project} onChange={(e) => setProject(e.target.value)} aria-label="Project">
            <option value="">All projects</option>
            {(projects ?? []).map((p) => <option key={p.key} value={p.key}>{p.key} · {p.name}</option>)}
          </select>
          <select value={person} onChange={(e) => setPerson(e.target.value)} aria-label="Person">
            <option value="">Everyone</option>
            {[...people.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([u, name]) => <option key={u} value={u}>{name}</option>)}
          </select>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!items && !error && <Spinner />}
      {items && items.length === 0 && <EmptyState icon={<ActivityIcon size={28} />} title="No activity yet">Changes and comments show up here.</EmptyState>}
      {items && items.length > 0 && (
        <ol className="feed panel">
          {items.map((item) => {
            const day = new Date(item.createdAt).toDateString();
            const heading = day !== lastDay ? formatDate(item.createdAt) : null;
            lastDay = day;
            return (
              <li key={`${item.kind}-${item.id}`}>
                {heading && <h2 className="feed-day">{heading}</h2>}
                <div className="feed-item">
                  <Avatar user={item.actor} size={30} />
                  <div className="feed-body">
                    <p>
                      <strong>{item.actor.displayName}</strong> {item.message} on{' '}
                      <Link to={`/tasks/${item.task.id}`}><span className="task-key">{item.task.key}</span> {item.task.title}</Link>
                    </p>
                    {item.body && <blockquote className="feed-comment"><MessageSquare size={13} /> {item.body}</blockquote>}
                    <span className="muted small" title={new Date(item.createdAt).toLocaleString()}>{timeAgo(item.createdAt)}</span>
                  </div>
                </div>
              </li>
            );
          })}
        </ol>
      )}
      {more && <div className="load-more"><button className="btn btn-ghost" onClick={loadMore}>Load older activity</button></div>}
    </div>
  );
}
