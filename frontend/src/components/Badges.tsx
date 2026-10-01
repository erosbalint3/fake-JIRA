import { Ban, BookOpen, Bug, CalendarClock, CheckSquare, ChevronDown, ChevronUp, ChevronsUp, Equal, FlaskConical, ListTree } from 'lucide-react';
import { dueState, formatDay } from '../format';
import { PRIORITY_LABEL, STATUS_LABEL, TASK_TYPE_LABEL, type EpicRef, type Priority, type Status, type TaskType } from '../types';
import { t } from '../i18n';

const PRIORITY_ICON = {
  CRITICAL: ChevronsUp,
  HIGH: ChevronUp,
  MEDIUM: Equal,
  LOW: ChevronDown,
} as const;

export function PriorityBadge({ priority, compact = false }: { priority: Priority; compact?: boolean }) {
  const Icon = PRIORITY_ICON[priority];
  return (
    <span className={`priority priority-${priority.toLowerCase()}`} title={t('{p} priority', { p: t(PRIORITY_LABEL[priority]) })}>
      <Icon size={14} strokeWidth={2.5} aria-hidden />
      {!compact && t(PRIORITY_LABEL[priority])}
    </span>
  );
}

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`status status-${status.toLowerCase()}`}>{t(STATUS_LABEL[status])}</span>;
}

export function DueBadge({ date, done }: { date: string; done: boolean }) {
  const state = dueState(date, done);
  const label = state === 'overdue' ? t('Overdue') : state === 'today' ? t('Due today') : t('Due date');
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
    <span className={`checklist-progress ${done === total ? 'complete' : ''}`} title={t('{done} of {total} checklist items done', { done, total })}>
      <CheckSquare size={13} aria-hidden />
      {done}/{total}
    </span>
  );
}

/** Epic identity: a dot in the epic's palette color plus its name in normal text. */
export function EpicChip({ epic }: { epic: EpicRef | null }) {
  if (!epic) return null;
  return (
    <span className="epic-chip" title={t('Epic: {name}', { name: epic.name })}>
      {epic.icon
        ? <span className="epic-emoji" aria-hidden>{epic.icon}</span>
        : <span className="epic-dot" style={{ background: `var(--cat-${epic.colorIndex % 8})` }} aria-hidden />}
      {epic.name}
    </span>
  );
}

export function PointsBadge({ points }: { points: number | null }) {
  if (points === null || points === undefined) return null;
  return <span className="points-badge" title={points === 1 ? t('1 story point') : t('{n} story points', { n: points })}>{points}</span>;
}

export function BlockedBadge({ blocked }: { blocked: boolean }) {
  if (!blocked) return null;
  return <span className="blocked-badge" title={t('Blocked by an unfinished task')}><Ban size={12} aria-hidden /> {t('Blocked')}</span>;
}

export function SubtaskBadge({ done, total }: { done: number; total: number }) {
  if (!total) return null;
  return (
    <span className={`checklist-progress ${done === total ? 'complete' : ''}`} title={t('{done} of {total} subtasks done', { done, total })}>
      <ListTree size={13} aria-hidden />
      {done}/{total}
    </span>
  );
}

const TYPE_ICON = { TASK: CheckSquare, BUG: Bug, STORY: BookOpen, SPIKE: FlaskConical } as const;

/** Issue type as a small colored icon (with a tooltip); pass {@code label} to show the name too. */
export function TypeIcon({ type, label = false }: { type: TaskType; label?: boolean }) {
  const Icon = TYPE_ICON[type] ?? CheckSquare;
  return (
    <span className={`type-icon type-${type.toLowerCase()}`} title={t(TASK_TYPE_LABEL[type])}>
      <Icon size={14} aria-hidden />
      {label ? <span>{t(TASK_TYPE_LABEL[type])}</span> : <span className="sr-only">{t(TASK_TYPE_LABEL[type])}</span>}
    </span>
  );
}
