import { Link } from 'react-router-dom';
import { ListTree, Plus } from 'lucide-react';
import { Avatar } from '../Avatar';
import { PriorityBadge, StatusBadge } from '../Badges';
import type { Task } from '../../types';

export function SubtasksPanel({ subtasks, canEdit, onAdd }: { subtasks: Task[]; canEdit: boolean; onAdd: () => void }) {
  const done = subtasks.filter((t) => t.status === 'DONE').length;
  return (
    <section className="panel">
      <h2 className="panel-title">
        <ListTree size={16} /> Subtasks
        {subtasks.length > 0 && <span className="muted small">{done}/{subtasks.length}</span>}
        <span className="spacer" />
        {canEdit && <button className="btn btn-soft btn-sm" onClick={onAdd}><Plus size={15} /> Add subtask</button>}
      </h2>
      {subtasks.length > 0 && (
        <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={subtasks.length} aria-valuenow={done}
          aria-label="Subtask progress">
          <span style={{ width: `${(done / subtasks.length) * 100}%` }} />
        </div>
      )}
      {subtasks.length === 0 ? <p className="muted">Break this task into smaller steps.</p> : (
        <ul className="mini-list">
          {subtasks.map((t) => (
            <li key={t.id}>
              <PriorityBadge priority={t.priority} compact />
              <span className="task-key">{t.key}</span>
              <Link to={`/tasks/${t.id}`} className="mini-title">{t.title}</Link>
              <StatusBadge status={t.status} />
              {t.assignee ? <Avatar user={t.assignee} size={22} /> : <span className="avatar-empty sm" title="Unassigned" />}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
