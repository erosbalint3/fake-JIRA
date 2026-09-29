import { useState } from 'react';
import { Trash2, X } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { ConfirmDialog } from './Modal';
import {
  PRIORITIES, PRIORITY_LABEL, STATUSES, STATUS_LABEL, type BulkChange, type Epic, type Member, type Sprint,
} from '../types';

interface Props {
  selected: number[];
  members: Member[];
  sprints: Sprint[];
  epics: Epic[];
  onClear: () => void;
  onDone: () => void;
}

/** Floating toolbar that applies one change to every selected task. */
export function BulkBar({ selected, members, sprints, epics, onClear, onDone }: Props) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [label, setLabel] = useState('');

  const apply = async (change: BulkChange, message: string) => {
    setBusy(true);
    try {
      await api.bulk(selected, change);
      toast(`${message} · ${selected.length} task${selected.length === 1 ? '' : 's'}`);
      onDone();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const select = (label: string, options: [string, string][], onPick: (value: string) => void) => (
    <select value="" disabled={busy} aria-label={label} onChange={(e) => e.target.value && onPick(e.target.value)}>
      <option value="">{label}</option>
      {options.map(([value, text]) => <option key={value} value={value}>{text}</option>)}
    </select>
  );

  return (
    <div className="bulk-bar" role="toolbar" aria-label="Bulk edit">
      <strong>{selected.length} selected</strong>
      {select('Status', STATUSES.map((s) => [s, STATUS_LABEL[s]]),
        (v) => apply({ status: v as BulkChange['status'] }, `Moved to ${STATUS_LABEL[v as keyof typeof STATUS_LABEL]}`))}
      {select('Assignee', [['none', 'Unassigned'], ...members.filter((m) => m.role !== 'VIEWER').map((m) => [String(m.id), m.displayName] as [string, string])],
        (v) => apply(v === 'none' ? { unassign: true } : { assigneeId: Number(v) }, 'Assignee updated'))}
      {select('Sprint', [['backlog', 'Backlog'], ...sprints.filter((s) => s.state !== 'COMPLETED').map((s) => [String(s.id), s.name] as [string, string])],
        (v) => apply(v === 'backlog' ? { clearSprint: true } : { sprintId: Number(v) }, 'Sprint updated'))}
      {select('Priority', PRIORITIES.map((p) => [p, PRIORITY_LABEL[p]]),
        (v) => apply({ priority: v as BulkChange['priority'] }, 'Priority updated'))}
      {select('Epic', [['none', 'No epic'], ...epics.map((e) => [String(e.id), e.name] as [string, string])],
        (v) => apply(v === 'none' ? { clearEpic: true } : { epicId: Number(v) }, 'Epic updated'))}
      <form className="bulk-label" onSubmit={(e) => {
        e.preventDefault();
        if (label.trim()) apply({ addLabels: [label.trim()] }, `Label "${label.trim()}" added`).then(() => setLabel(''));
      }}>
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="+ label" aria-label="Add label" disabled={busy} />
      </form>
      <button className="icon-button danger-icon" onClick={() => setConfirmDelete(true)} aria-label="Delete selected" title="Delete selected">
        <Trash2 size={17} />
      </button>
      <button className="icon-button" onClick={onClear} aria-label="Clear selection" title="Clear selection (Esc)">
        <X size={17} />
      </button>
      {confirmDelete && (
        <ConfirmDialog title={`Delete ${selected.length} task${selected.length === 1 ? '' : 's'}?`} danger busy={busy}
          message="They are removed permanently, with their subtasks, comments and files."
          confirmLabel="Delete" onClose={() => setConfirmDelete(false)}
          onConfirm={async () => {
            setConfirmDelete(false);
            await apply({ delete: true }, 'Deleted');
          }} />
      )}
    </div>
  );
}
