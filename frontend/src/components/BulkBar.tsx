import { isReadOnlyRole } from '../types';
import { useState } from 'react';
import { Trash2, X } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { ConfirmDialog } from './Modal';
import { deleteLater } from '../undo';
import {
  PRIORITIES, PRIORITY_LABEL, STATUSES, STATUS_LABEL, TASK_TYPES, TASK_TYPE_LABEL, type BulkChange, type Epic, type Member,
  type Sprint, type Task,
} from '../types';
import { t } from '../i18n';

interface Props {
  selected: number[];
  /** The selected tasks as they are now, to undo a change. */
  tasks: Task[];
  members: Member[];
  sprints: Sprint[];
  epics: Epic[];
  onClear: () => void;
  onDone: () => void;
  /** Hides tasks while their delete can still be undone (null shows them again). */
  onHide: (ids: number[] | null) => void;
}

function groupBy<K>(tasks: Task[], key: (task: Task) => K): Map<K, number[]> {
  const groups = new Map<K, number[]>();
  tasks.forEach((task) => groups.set(key(task), [...(groups.get(key(task)) ?? []), task.id]));
  return groups;
}

/** The changes that put the tasks back the way they were before {@code change}. */
function inverse(change: BulkChange, before: Task[]): [number[], BulkChange][] {
  const out: [number[], BulkChange][] = [];
  if (change.status) groupBy(before, (t) => t.status).forEach((ids, status) => out.push([ids, { status }]));
  if (change.priority) groupBy(before, (t) => t.priority).forEach((ids, priority) => out.push([ids, { priority }]));
  if (change.type) groupBy(before, (t) => t.type).forEach((ids, type) => out.push([ids, { type }]));
  if (change.assigneeId !== undefined || change.unassign) {
    groupBy(before, (t) => t.assignee?.id ?? null).forEach((ids, id) => out.push([ids, id === null ? { unassign: true } : { assigneeId: id }]));
  }
  if (change.sprintId !== undefined || change.clearSprint) {
    groupBy(before.filter((t) => t.sprint?.state !== 'COMPLETED'), (t) => t.sprint?.id ?? null)
      .forEach((ids, id) => out.push([ids, id === null ? { clearSprint: true } : { sprintId: id }]));
  }
  if (change.epicId !== undefined || change.clearEpic) {
    groupBy(before, (t) => t.epic?.id ?? null).forEach((ids, id) => out.push([ids, id === null ? { clearEpic: true } : { epicId: id }]));
  }
  if (change.addLabels?.length) {
    const label = change.addLabels[0].toLowerCase();
    const ids = before.filter((t) => !t.labels.includes(label)).map((t) => t.id);
    if (ids.length) out.push([ids, { removeLabels: [label] }]);
  }
  return out;
}

/** Floating toolbar that applies one change to every selected task. */
export function BulkBar({ selected, tasks, members, sprints, epics, onClear, onDone, onHide }: Props) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [label, setLabel] = useState('');

  const apply = async (change: BulkChange, message: string) => {
    setBusy(true);
    const before = tasks.filter((t) => selected.includes(t.id));
    try {
      await api.bulk(selected, change);
      const undo = inverse(change, before);
      toast(`${message} · ${selected.length} task${selected.length === 1 ? '' : 's'}`, 'success', undo.length ? {
        action: { label: 'Undo', onClick: async () => {
          try {
            for (const [ids, back] of undo) await api.bulk(ids, back);
            toast(t("Change undone"));
          } catch (e) {
            toast((e as ApiError).message, 'error');
          }
          onDone();
        } },
      } : {});
      onDone();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const remove = () => {
    const ids = [...selected];
    onHide(ids);
    onClear();
    const cancel = deleteLater(ids, (error) => {
      if (error) toast(error.message, 'error');
      onHide(null);
      onDone();
    });
    toast(`Deleted ${ids.length} task${ids.length === 1 ? '' : 's'}`, 'success', {
      action: { label: 'Undo', onClick: () => {
        cancel();
        onHide(null);
      } },
    });
  };

  const select = (label: string, options: [string, string][], onPick: (value: string) => void) => (
    <select value="" disabled={busy} aria-label={label} onChange={(e) => e.target.value && onPick(e.target.value)}>
      <option value="">{label}</option>
      {options.map(([value, text]) => <option key={value} value={value}>{text}</option>)}
    </select>
  );

  return (
    <div className="bulk-bar" role="toolbar" aria-label={t("Bulk edit")}>
      <strong>{selected.length} selected</strong>
      {select('Status', STATUSES.map((s) => [s, STATUS_LABEL[s]]),
        (v) => apply({ status: v as BulkChange['status'] }, `Moved to ${STATUS_LABEL[v as keyof typeof STATUS_LABEL]}`))}
      {select('Assignee', [['none', 'Unassigned'], ...members.filter((m) => !isReadOnlyRole(m.role)).map((m) => [String(m.id), m.displayName] as [string, string])],
        (v) => apply(v === 'none' ? { unassign: true } : { assigneeId: Number(v) }, 'Assignee updated'))}
      {select('Sprint', [['backlog', 'Backlog'], ...sprints.filter((s) => s.state !== 'COMPLETED').map((s) => [String(s.id), s.name] as [string, string])],
        (v) => apply(v === 'backlog' ? { clearSprint: true } : { sprintId: Number(v) }, 'Sprint updated'))}
      {select('Priority', PRIORITIES.map((p) => [p, PRIORITY_LABEL[p]]),
        (v) => apply({ priority: v as BulkChange['priority'] }, 'Priority updated'))}
      {select('Type', TASK_TYPES.map((t) => [t, TASK_TYPE_LABEL[t]]),
        (v) => apply({ type: v as BulkChange['type'] }, 'Type updated'))}
      {select('Epic', [['none', 'No epic'], ...epics.map((e) => [String(e.id), e.name] as [string, string])],
        (v) => apply(v === 'none' ? { clearEpic: true } : { epicId: Number(v) }, 'Epic updated'))}
      <form className="bulk-label" onSubmit={(e) => {
        e.preventDefault();
        if (label.trim()) apply({ addLabels: [label.trim()] }, `Label "${label.trim()}" added`).then(() => setLabel(''));
      }}>
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={t("+ label")} aria-label={t("Add label")} disabled={busy} />
      </form>
      <button className="icon-button danger-icon" onClick={() => (selected.length > 20 ? setConfirmDelete(true) : remove())}
        aria-label={t("Delete selected")} title={t("Delete selected (can be undone for a few seconds)")}>
        <Trash2 size={17} />
      </button>
      <button className="icon-button" onClick={onClear} aria-label={t("Clear selection")} title={t("Clear selection (Esc)")}>
        <X size={17} />
      </button>
      {confirmDelete && (
        <ConfirmDialog title={`Delete ${selected.length} tasks?`} danger busy={busy}
          message={t("They are removed with their subtasks, comments and files. You can undo for a few seconds.")}
          confirmLabel={t("Delete")} onClose={() => setConfirmDelete(false)}
          onConfirm={() => {
            setConfirmDelete(false);
            remove();
          }} />
      )}
    </div>
  );
}
