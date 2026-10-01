import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { AlarmClock, BellPlus, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import type { Reminder } from '../types';
import { Modal } from './Modal';
import { t } from '../i18n';

function at(date: Date, hours: number, minutes = 0) {
  const copy = new Date(date);
  copy.setHours(hours, minutes, 0, 0);
  return copy;
}

/** Common "later" times: in an hour, this evening, tomorrow morning, next Monday morning. */
export function laterPresets(now = new Date()): { label: string; at: Date }[] {
  const presets = [{ label: t('In 1 hour'), at: new Date(now.getTime() + 3600_000) }];
  const evening = at(now, 17);
  if (evening.getTime() - now.getTime() > 3600_000) presets.push({ label: t('This evening (17:00)'), at: evening });
  const tomorrow = at(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1), 9);
  presets.push({ label: t('Tomorrow morning (9:00)'), at: tomorrow });
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() + ((8 - now.getDay()) % 7 || 7));
  presets.push({ label: t('Next Monday (9:00)'), at: at(monday, 9) });
  return presets;
}

/** yyyy-MM-ddTHH:mm in local time, for datetime-local inputs. */
export function localInput(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function formatWhen(iso: string) {
  return new Date(iso).toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Asks when (and optionally why) to be reminded; {@code taskId} null for a free-standing reminder. */
export function ReminderModal({ taskId, taskKey, onClose, onSaved }: {
  taskId: number | null; taskKey?: string; onClose: () => void; onSaved?: () => void;
}) {
  const toast = useToast();
  const [when, setWhen] = useState(localInput(laterPresets()[0].at));
  const [note, setNote] = useState('');
  const [error, setError] = useState('');

  const save = async (event?: FormEvent, time = when) => {
    event?.preventDefault();
    const date = new Date(time);
    if (Number.isNaN(date.getTime())) {
      setError(t('Choose a date and time.'));
      return;
    }
    try {
      await api.createReminder({ taskId, remindAt: date.toISOString(), note: note.trim() });
      toast(t('Reminder set for {when}', { when: formatWhen(date.toISOString()) }));
      onSaved?.();
      onClose();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <Modal title={taskKey ? t('Remind me about {key}', { key: taskKey }) : t('New reminder')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" type="submit" form="reminder-form">{t('Set reminder')}</button>
      </>
    }>
      <form id="reminder-form" className="form" onSubmit={save}>
        <div className="preset-row" role="group" aria-label={t('Quick times')}>
          {laterPresets().map((preset) => (
            <button key={preset.label} type="button" className="chip-button"
              onClick={() => setWhen(localInput(preset.at))}>{preset.label}</button>
          ))}
        </div>
        <label className="field">
          <span>{t('When')}</span>
          <input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} required />
        </label>
        <label className="field">
          <span>{taskId ? t('Note (optional)') : t('What about?')}</span>
          <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error && <small className="field-error" role="alert">{error}</small>}
      </form>
    </Modal>
  );
}

/** Upcoming reminders, with a button to add one. */
export function RemindersPanel() {
  const toast = useToast();
  const [items, setItems] = useState<Reminder[] | null>(null);
  const [adding, setAdding] = useState(false);
  const load = useCallback(() => {
    api.reminders().then(setItems).catch(() => setItems([]));
  }, []);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'reminders', load);

  const remove = async (reminder: Reminder) => {
    try {
      await api.deleteReminder(reminder.id);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel reminders-panel">
      <div className="panel-title-row">
        <h2><AlarmClock size={18} aria-hidden /> {t('Reminders')}</h2>
        <button className="btn btn-soft btn-sm" onClick={() => setAdding(true)}><BellPlus size={14} /> {t('New reminder')}</button>
      </div>
      {items && items.length === 0 && <p className="muted small">{t('No upcoming reminders. Use “Remind me” on a task, or add one here.')}</p>}
      {items && items.length > 0 && (
        <ul className="plain-list reminder-list">
          {items.map((r) => (
            <li key={r.id}>
              <span className="reminder-when">{formatWhen(r.remindAt)}</span>
              <span className="reminder-what">
                {r.task && <Link to={`/tasks/${r.task.id}`}>{r.task.key}</Link>} {r.task?.title}
                {r.note && <span className={r.task ? 'muted' : ''}>{r.task ? ' — ' : ''}{r.note}</span>}
              </span>
              <button className="icon-button" onClick={() => remove(r)} aria-label={t('Delete reminder')}><Trash2 size={15} /></button>
            </li>
          ))}
        </ul>
      )}
      {adding && <ReminderModal taskId={null} onClose={() => setAdding(false)} onSaved={load} />}
    </section>
  );
}
