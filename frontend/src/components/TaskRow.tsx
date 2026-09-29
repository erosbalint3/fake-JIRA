import type { HTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Avatar } from './Avatar';
import {
  BlockedBadge, ChecklistProgress, DueBadge, EpicChip, Labels, PointsBadge, PriorityBadge, StatusBadge, SubtaskBadge,
} from './Badges';
import type { Task } from '../types';

interface Props {
  task: Task;
  actions?: ReactNode;
  showProject?: boolean;
  /** Extra attributes for the row, e.g. drag handlers. */
  rowProps?: HTMLAttributes<HTMLLIElement> & { draggable?: boolean };
  className?: string;
  /** Shows a selection checkbox (bulk edit) when provided. */
  selected?: boolean;
  onToggleSelect?: (shiftKey: boolean) => void;
}

/** One line in a task list: priority, key, title, metadata and optional trailing actions. */
export function TaskRow({ task, actions, showProject = false, rowProps, className = '', selected, onToggleSelect }: Props) {
  return (
    <li className={`task-row ${selected ? 'selected' : ''} ${className}`} {...rowProps}>
      {onToggleSelect && (
        <input type="checkbox" className="row-check" checked={!!selected} aria-label={`Select ${task.key}`}
          onClick={(e) => {
            e.stopPropagation();
            onToggleSelect(e.shiftKey);
          }}
          onChange={() => {}} />
      )}
      <Link to={`/tasks/${task.id}`} className="task-row-main">
        <PriorityBadge priority={task.priority} compact />
        <span className="task-key">{task.key}</span>
        <span className="task-title">{task.parent && <span className="muted">↳ </span>}{task.title}</span>
        <EpicChip epic={task.epic} />
        <Labels labels={task.labels} max={2} />
      </Link>
      <div className="task-row-meta">
        {showProject && <span className="muted small hide-sm">{task.projectName}</span>}
        <BlockedBadge blocked={task.blocked} />
        <SubtaskBadge done={task.subtaskDone} total={task.subtaskTotal} />
        <ChecklistProgress done={task.checklistDone} total={task.checklistTotal} />
        {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
        <PointsBadge points={task.storyPoints} />
        <StatusBadge status={task.status} />
        {actions}
        {task.assignee
          ? <Avatar user={task.assignee} />
          : <span className="avatar-empty" title="Unassigned" />}
      </div>
    </li>
  );
}
