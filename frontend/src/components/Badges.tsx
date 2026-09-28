import { ChevronsUp, ChevronUp, Equal, ChevronDown } from 'lucide-react';
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
