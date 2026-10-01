import { Link } from 'react-router-dom';
import { ListTree, Plus } from 'lucide-react';
import { Avatar } from '../Avatar';
import { PriorityBadge, StatusBadge } from '../Badges';
import type { Task } from '../../types';
import { t } from '../../i18n';

export function SubtasksPanel({ subtasks, canEdit, onAdd }: { subtasks: Task[]; canEdit: boolean; onAdd: () => void }) {
  const done = subtasks.filter((s) => s.status === 'DONE').length;
  return (
    <section className="panel">
      <h2 className="panel-title">
        <ListTree size={16} /> {t('Subtasks')}
        {subtasks.length > 0 && <span className="muted small">{done}/{subtasks.length}</span>}
        <span className="spacer" />
        {canEdit && <button className="btn btn-soft btn-sm" onClick={onAdd}><Plus size={15} /> {t('Add subtask')}</button>}
      </h2>
      {subtasks.length > 0 && (
        <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={subtasks.length} aria-valuenow={done}
          aria-label={t('Subtask progress')}>
          <span style={{ width: `${(done / subtasks.length) * 100}%` }} />
        </div>
      )}
      {subtasks.length === 0 ? <p className="muted">{t('Break this task into smaller steps.')}</p> : (
        <ul className="mini-list">
          {subtasks.map((s) => (
            <li key={s.id}>
              <PriorityBadge priority={s.priority} compact />
              <span className="task-key">{s.key}</span>
              <Link to={`/tasks/${s.id}`} className="mini-title">{s.title}</Link>
              <StatusBadge status={s.status} />
              {s.assignee ? <Avatar user={s.assignee} size={22} /> : <span className="avatar-empty sm" title={t('Unassigned')} />}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
