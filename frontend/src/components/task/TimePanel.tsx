import { useState, type FormEvent } from 'react';
import { Timer, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { Avatar } from '../Avatar';
import { formatDay, formatMinutes, parseDuration, todayIso } from '../../format';
import type { TimeEntry } from '../../types';

interface Props {
  taskId: number;
  entries: TimeEntry[];
  userId: number;
  isOwner: boolean;
  canEdit: boolean;
  onChange: () => void;
}

export function TimePanel({ taskId, entries, userId, isOwner, canEdit, onChange }: Props) {
  const toast = useToast();
  const [duration, setDuration] = useState('');
  const [date, setDate] = useState(todayIso());
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const total = entries.reduce((sum, e) => sum + e.minutes, 0);
  const minutes = parseDuration(duration);

  const log = async (event: FormEvent) => {
    event.preventDefault();
    if (!minutes) {
      toast('Enter a duration like 1h 30m, 45m or 1.5h', 'error');
      return;
    }
    setBusy(true);
    try {
      await api.logTime(taskId, minutes, date, note.trim());
      setDuration('');
      setNote('');
      onChange();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><Timer size={16} /> Time tracking {total > 0 && <span className="muted small">{formatMinutes(total)} logged</span>}</h2>
      {canEdit && (
        <form className="time-form" onSubmit={log}>
          <input value={duration} onChange={(e) => setDuration(e.target.value)} placeholder="1h 30m"
            aria-label="Time spent" aria-invalid={!!duration && !minutes} />
          <input type="date" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} aria-label="Date" />
          <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} placeholder="What did you work on? (optional)"
            aria-label="Note" className="time-note" />
          <button className="btn btn-soft btn-sm" disabled={busy || !minutes}>Log</button>
        </form>
      )}
      {entries.length === 0 ? <p className="muted">No time logged yet.</p> : (
        <ul className="time-list">
          {entries.map((entry) => (
            <li key={entry.id}>
              <Avatar user={entry.user} size={22} />
              <strong>{formatMinutes(entry.minutes)}</strong>
              <span className="muted small">{formatDay(entry.date)}</span>
              <span className="time-entry-note">{entry.note}</span>
              {(entry.user.id === userId || isOwner) && (
                <button className="icon-button sm" aria-label="Delete time entry" onClick={async () => {
                  await api.deleteTime(taskId, entry.id).catch((e: ApiError) => toast(e.message, 'error'));
                  onChange();
                }}>
                  <Trash2 size={14} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
