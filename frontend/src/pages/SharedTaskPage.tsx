import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { CheckSquare, Square } from 'lucide-react';
import { api, ApiError } from '../api';
import { Logo } from '../components/Logo';
import { Markdown } from '../components/Markdown';
import { PointsBadge, PriorityBadge, StatusBadge, TypeIcon } from '../components/Badges';
import { Spinner } from '../components/States';
import { formatDate, formatDay } from '../format';
import type { PublicTask } from '../types';
import { t } from '../i18n';

/** A task someone shared with a public link: read-only, no sign-in. */
export function SharedTaskPage() {
  const { token } = useParams();
  const [task, setTask] = useState<PublicTask | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api.publicTask(token ?? '').then((t) => {
      setTask(t);
      document.title = `${t.key} · ${t.title}`;
    }).catch((e: ApiError) => setError(e.message));
  }, [token]);

  return (
    <div className="shared-page">
      <header className="shared-header">
        <Logo />
        <span className="muted small">{t('Shared task · read-only')}</span>
      </header>
      <main className="shared-main">
        {error && <div className="panel shared-error"><h1>{t('Link unavailable')}</h1><p className="muted">{error}</p></div>}
        {!task && !error && <Spinner />}
        {task && (
          <article className="panel shared-task">
            <span className="eyebrow">{task.projectName} · {task.key}</span>
            <h1><TypeIcon type={task.type} /> {task.title}</h1>
            <div className="shared-meta">
              <StatusBadge status={task.status} />
              <PriorityBadge priority={task.priority} />
              <PointsBadge points={task.storyPoints} />
              {task.assignee && <span className="muted small">{t('Assigned to {name}', { name: task.assignee })}</span>}
              {task.dueDate && <span className="muted small">{t('Due {date}', { date: formatDay(task.dueDate, true) })}</span>}
            </div>
            {task.labels.length > 0 && (
              <div className="chip-row">{task.labels.map((l) => <span key={l} className="label-chip">{l}</span>)}</div>
            )}
            {task.description ? <div className="shared-description"><Markdown>{task.description}</Markdown></div>
              : <p className="muted">{t('No description.')}</p>}
            {task.checklist.length > 0 && (
              <section>
                <h2>{t('Checklist')} ({task.checklist.filter((i) => i.done).length}/{task.checklist.length})</h2>
                <ul className="shared-checklist">
                  {task.checklist.map((item, i) => (
                    <li key={i} className={item.done ? 'done' : ''}>
                      {item.done ? <CheckSquare size={16} /> : <Square size={16} />} {item.text}
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {task.subtasks.length > 0 && (
              <section>
                <h2>{t('Subtasks')}</h2>
                <ul className="review-list">
                  {task.subtasks.map((s) => <li key={s.key}><span className="task-key">{s.key}</span> {s.title} <span className="spacer" /><StatusBadge status={s.status} /></li>)}
                </ul>
              </section>
            )}
            {task.comments.length > 0 && (
              <section>
                <h2>{t('Comments')}</h2>
                <ul className="shared-comments">
                  {task.comments.map((c, i) => (
                    <li key={i}>
                      <strong>{c.author}</strong> <span className="muted small">{formatDate(c.createdAt)}</span>
                      <Markdown>{c.body}</Markdown>
                    </li>
                  ))}
                </ul>
              </section>
            )}
            <p className="muted small shared-foot">
              {t('Last updated {date}', { date: formatDate(task.updatedAt) })}{task.sharedUntil ? ` · ${t('this link works until {date}', { date: formatDate(task.sharedUntil) })}` : ''}
            </p>
          </article>
        )}
      </main>
    </div>
  );
}
