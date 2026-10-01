import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Heart, Trophy } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { Avatar } from '../components/Avatar';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { timeAgo } from '../format';
import type { KudosWall } from '../types';
import { t } from '../i18n';

/** Thank-yous across your projects, and who got the most this month. */
export function KudosPage() {
  const [wall, setWall] = useState<KudosWall | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => {
    api.kudosWall().then(setWall).catch((e: ApiError) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task', load, 2000);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{t('Workspace')}</span>
          <h1>{t('Kudos')}</h1>
          <p className="muted">{t('Thank a teammate from any finished task. Here is what people appreciated lately.')}</p>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!wall && !error && <Spinner />}
      {wall && wall.recent.length === 0 && (
        <EmptyState icon={<Heart size={28} />} title={t('No kudos yet')}>
          {t('Open a finished task and thank the people who did it.')}
        </EmptyState>
      )}
      {wall && wall.recent.length > 0 && (
        <div className="kudos-layout">
          <ul className="kudos-feed">
            {wall.recent.map((k) => (
              <li key={k.id} className="panel kudos-card">
                <span className="kudos-emoji big" aria-hidden>{k.emoji}</span>
                <div>
                  <p><strong>{k.from.displayName}</strong> {t('thanked')} <strong>{k.to.displayName}</strong></p>
                  <p className="kudos-message">“{k.message}”</p>
                  <span className="muted small">{k.task && <><Link to={`/tasks/${k.task.id}`}>{k.task.key}</Link> {k.task.title} · </>}{timeAgo(k.createdAt)}</span>
                </div>
              </li>
            ))}
          </ul>
          <aside className="panel kudos-leaders">
            <h2 className="panel-title"><Trophy size={16} /> {t('Most thanked, last 30 days')}</h2>
            <ol>
              {wall.thisMonth.map((l) => (
                <li key={l.user.id}><Avatar user={l.user} size={24} /> {l.user.displayName} <span className="count">{l.count}</span></li>
              ))}
            </ol>
          </aside>
        </div>
      )}
    </div>
  );
}
