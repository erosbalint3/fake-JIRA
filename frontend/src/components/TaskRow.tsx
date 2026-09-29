import { useState, type HTMLAttributes, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Pencil } from 'lucide-react';
import { Avatar } from './Avatar';
import {
  BlockedBadge, ChecklistProgress, DueBadge, EpicChip, Labels, PointsBadge, PriorityBadge, StatusBadge, SubtaskBadge, TypeIcon,
} from './Badges';
import type { Member, Task } from '../types';

/** Handlers that make the row editable in place (backlog). */
export interface InlineEdit {
  members: Member[];
  onRename: (task: Task, title: string) => void;
  onPoints: (task: Task, points: number | null) => void;
  onAssign: (task: Task, userId: number | null) => void;
}

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
  inline?: InlineEdit;
}

/** One line in a task list: priority, key, title, metadata and optional trailing actions. */
export function TaskRow({ task, actions, showProject = false, rowProps, className = '', selected, onToggleSelect, inline }: Props) {
  const [renaming, setRenaming] = useState(false);
  const [title, setTitle] = useState(task.title);

  const finishRename = (save: boolean) => {
    setRenaming(false);
    if (save && inline && title.trim() && title.trim() !== task.title) inline.onRename(task, title.trim());
    else setTitle(task.title);
  };

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
      {renaming ? (
        <div className="task-row-main editing">
          <TypeIcon type={task.type} />
          <span className="task-key">{task.key}</span>
          <input className="inline-title" value={title} maxLength={120} autoFocus aria-label={`Title of ${task.key}`}
            onChange={(e) => setTitle(e.target.value)}
            onBlur={() => finishRename(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') finishRename(true);
              if (e.key === 'Escape') {
                e.stopPropagation();
                finishRename(false);
              }
            }} />
        </div>
      ) : (
        <Link to={`/tasks/${task.id}`} className="task-row-main">
          <TypeIcon type={task.type} />
          <PriorityBadge priority={task.priority} compact />
          <span className="task-key">{task.key}</span>
          <span className="task-title">{task.parent && <span className="muted">↳ </span>}{task.title}</span>
          <EpicChip epic={task.epic} />
          <Labels labels={task.labels} max={2} />
        </Link>
      )}
      <div className="task-row-meta">
        {inline && !renaming && (
          <button className="icon-button sm row-edit" aria-label={`Rename ${task.key}`} title="Rename"
            onClick={() => {
              setTitle(task.title);
              setRenaming(true);
            }}><Pencil size={13} /></button>
        )}
        {showProject && <span className="muted small hide-sm">{task.projectName}</span>}
        <BlockedBadge blocked={task.blocked} />
        <SubtaskBadge done={task.subtaskDone} total={task.subtaskTotal} />
        <ChecklistProgress done={task.checklistDone} total={task.checklistTotal} />
        {task.dueDate && <DueBadge date={task.dueDate} done={task.status === 'DONE'} />}
        {inline ? (
          <input className="inline-points" type="number" min={0} max={100} placeholder="–" defaultValue={task.storyPoints ?? ''}
            key={`${task.id}-${task.storyPoints}`} aria-label={`Story points of ${task.key}`} title="Story points"
            onBlur={(e) => {
              const value = e.target.value === '' ? null : Math.max(0, Math.min(100, Number(e.target.value)));
              if (value !== task.storyPoints) inline.onPoints(task, value);
            }}
            onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
        ) : (
          <PointsBadge points={task.storyPoints} />
        )}
        <StatusBadge status={task.status} />
        {actions}
        {inline ? (
          <label className="inline-assignee" title={task.assignee ? task.assignee.displayName : 'Unassigned'}>
            {task.assignee ? <Avatar user={task.assignee} /> : <span className="avatar-empty" />}
            <select value={task.assignee?.id ?? ''} aria-label={`Assignee of ${task.key}`}
              onChange={(e) => inline.onAssign(task, e.target.value ? Number(e.target.value) : null)}>
              <option value="">Unassigned</option>
              {inline.members.filter((m) => m.role !== 'VIEWER').map((m) => (
                <option key={m.id} value={m.id}>{m.displayName}</option>
              ))}
            </select>
          </label>
        ) : task.assignee
          ? <Avatar user={task.assignee} />
          : <span className="avatar-empty" title="Unassigned" />}
      </div>
    </li>
  );
}
