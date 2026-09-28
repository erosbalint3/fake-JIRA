import type { HTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Avatar } from './Avatar';
import { ChecklistProgress, DueBadge, Labels, PriorityBadge, StatusBadge } from './Badges';
import type { Task } from '../types';

/** One line in a task list: priority, key, title, metadata and optional trailing actions. */
export function TaskRow({ task, actions, showProject = false, rowProps, className = '' }: {
  task: Task;
  actions?: ReactNode;
  showProject?: boolean;
  /** Extra attributes for the row, e.g. drag handlers. */
  rowProps?: HTMLAttributes<HTMLLIElement> & { draggable?: boolean };
  className?: string;
}) {
  return (
    <li className={`task-row ${className}`} {...rowProps}>
      <Link to={`/tasks/${task.id}`} className="task-row-main">
        <PriorityBadge priority={task.priority} compact />
        <span className="task-key">{task.key}</span>
        <span className="task-title">{task.title}</span>
        <Labels labels={task.labels} max={2} />
      </Link>
      <div className="task-row-meta">
        {showProject && <span className="muted small hide-sm">{task.projectName}</span>}
        <ChecklistProgress done={task.checklistDone} total={task.checklistTotal} />
        {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
        <StatusBadge status={task.status} />
        {actions}
        {task.assignee
          ? <Avatar name={task.assignee.username} />
          : <span className="avatar-empty" title="Unassigned" />}
      </div>
    </li>
  );
}
