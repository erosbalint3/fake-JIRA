import { CalendarClock, CheckSquare, ChevronsUp, ChevronUp, Equal, ChevronDown } from 'lucide-react';
import { dueState, formatDay } from '../format';
import { PRIORITY_LABEL, STATUS_LABEL, type Priority, type Status } from '../types';

const PRIORITY_ICON = {
  CRITICAL: ChevronsUp,
  HIGH: ChevronUp,
  MEDIUM: Equal,
  LOW: ChevronDown,
} as const;

export function PriorityBadge({ priority, compact = false }: { priority: Priority; compact?: boolean }) {
  const Icon = PRIORITY_ICON[priority];
  return (
    <span className={`priority priority-${priority.toLowerCase()}`} title={`${PRIORITY_LABEL[priority]} priority`}>
      <Icon size={14} strokeWidth={2.5} aria-hidden />
      {!compact && PRIORITY_LABEL[priority]}
    </span>
  );
}

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`status status-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>;
}

export function DueBadge({ date, done }: { date: string; done: boolean }) {
  const state = dueState(date, done);
  const label = state === 'overdue' ? 'Overdue' : state === 'today' ? 'Due today' : 'Due';
  return (
    <span className={`due due-${state ?? 'done'}`} title={`${label}: ${formatDay(date, true)}`}>
      <CalendarClock size={13} aria-hidden />
      {formatDay(date)}
    </span>
  );
}

export function Labels({ labels, max }: { labels: string[]; max?: number }) {
  if (!labels.length) return null;
  const shown = max ? labels.slice(0, max) : labels;
  return (
    <span className="labels">
      {shown.map((label) => <span key={label} className="label-chip">{label}</span>)}
      {max && labels.length > max && <span className="label-chip more">+{labels.length - max}</span>}
    </span>
  );
}

export function ChecklistProgress({ done, total }: { done: number; total: number }) {
  if (!total) return null;
  return (
    <span className={`checklist-progress ${done === total ? 'complete' : ''}`} title={`${done} of ${total} checklist items done`}>
      <CheckSquare size={13} aria-hidden />
      {done}/{total}
    </span>
  );
}
