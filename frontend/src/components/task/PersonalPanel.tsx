import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { AlarmClock, Lock, Plus, Sun, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import { todayIso, timeAgo } from '../../format';
import type { PersonalNotes, Reminder, Task } from '../../types';
import { ReminderModal, formatWhen } from '../Reminders';
import { t } from '../../i18n';

/** "Just for you": Today, reminders, private notes and a private checklist. Nobody else sees any of it. */
export function PersonalPanel({ task }: { task: Task }) {
  const toast = useToast();
  const [onToday, setOnToday] = useState<boolean | null>(null);
  const [reminders, setReminders] = useState<Reminder[]>([]);
  const [personal, setPersonal] = useState<PersonalNotes | null>(null);
  const [note, setNote] = useState('');
  const [saved, setSaved] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [item, setItem] = useState('');
  const [reminding, setReminding] = useState(false);
  const saveTimer = useRef<number | undefined>(undefined);

  const loadToday = useCallback(() => {
    api.today(todayIso()).then((day) => setOnToday(day.picks.some((p) => p.id === task.id))).catch(() => {});
  }, [task.id]);
  const loadReminders = useCallback(() => {
    api.taskReminders(task.id).then(setReminders).catch(() => {});
  }, [task.id]);

  useEffect(() => {
    loadToday();
    loadReminders();
    api.personalNotes(task.id).then((data) => {
      setPersonal(data);
      setNote(data.note);
    }).catch(() => {});
  }, [task.id, loadToday, loadReminders]);
  useLiveRefresh((m) => m.type === 'today', loadToday);
  useLiveRefresh((m) => m.type === 'reminders', loadReminders);
  useEffect(() => () => window.clearTimeout(saveTimer.current), []);

  const toggleToday = async () => {
    try {
      const day = onToday ? await api.unpickToday(task.id, todayIso()) : await api.pickToday(task.id, todayIso());
      const now = day.picks.some((p) => p.id === task.id);
      setOnToday(now);
      toast(now ? t('Added to your day') : t('Removed from your day'));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  // Notes save by themselves shortly after typing stops.
  const changeNote = (value: string) => {
    setNote(value);
    setSaved('saving');
    window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      api.savePersonalNote(task.id, value).then((data) => {
        setPersonal(data);
        setSaved('saved');
      }).catch((e: ApiError) => {
        setSaved('idle');
        toast(e.message, 'error');
      });
    }, 700);
  };

  const addItem = async (event: FormEvent) => {
    event.preventDefault();
    if (!item.trim()) return;
    try {
      setPersonal(await api.addPrivateItem(task.id, item.trim()));
      setItem('');
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const update = (promise: Promise<PersonalNotes>) => promise.then(setPersonal).catch((e: ApiError) => toast(e.message, 'error'));

  return (
    <section className="side-section personal-panel" aria-labelledby="personal-title">
      <h3 className="side-title" id="personal-title"><Lock size={15} aria-hidden /> {t('Just for you')}</h3>
      <div className="personal-buttons">
        <button className={`btn btn-sm ${onToday ? 'btn-primary' : 'btn-soft'}`} aria-pressed={!!onToday} onClick={toggleToday}
          disabled={onToday === null}>
          <Sun size={14} /> {onToday ? t('On today’s plan') : t('Add to Today')}
        </button>
        <button className="btn btn-soft btn-sm" onClick={() => setReminding(true)}><AlarmClock size={14} /> {t('Remind me')}</button>
      </div>
      {reminders.length > 0 && (
        <ul className="mini-list">
          {reminders.map((r) => (
            <li key={r.id} className="reminder-mini">
              <AlarmClock size={13} aria-hidden /> {formatWhen(r.remindAt)}{r.note && <span className="muted"> — {r.note}</span>}
              <button className="icon-button" aria-label={t('Delete reminder')}
                onClick={() => api.deleteReminder(r.id).then(loadReminders)}><Trash2 size={13} /></button>
            </li>
          ))}
        </ul>
      )}
      <label className="field">
        <span className="small muted">
          {t('Private notes')}
          {saved === 'saving' && ` · ${t('saving…')}`}
          {saved === 'saved' && personal?.updatedAt && ` · ${t('saved')} ${timeAgo(personal.updatedAt)}`}
        </span>
        <textarea value={note} rows={3} maxLength={10000} placeholder={t('Only you can see these notes.')}
          onChange={(e) => changeNote(e.target.value)} />
      </label>
      {personal && personal.items.length > 0 && (
        <ul className="checklist private-checklist" aria-label={t('Private checklist')}>
          {personal.items.map((i) => (
            <li key={i.id}>
              <input type="checkbox" checked={i.done} aria-label={i.text}
                onChange={() => update(api.updatePrivateItem(i.id, { done: !i.done }))} />
              <span className={i.done ? 'done-text' : ''}>{i.text}</span>
              <button className="icon-button" aria-label={t('Delete {item}', { item: i.text })}
                onClick={() => update(api.deletePrivateItem(i.id))}><Trash2 size={13} /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="inline-add" onSubmit={addItem}>
        <input value={item} maxLength={200} placeholder={t('Add a private to-do')} aria-label={t('Add a private to-do')}
          onChange={(e) => setItem(e.target.value)} />
        <button className="icon-button" type="submit" aria-label={t('Add')} disabled={!item.trim()}><Plus size={16} /></button>
      </form>
      {reminding && <ReminderModal taskId={task.id} taskKey={task.key} onClose={() => setReminding(false)} onSaved={loadReminders} />}
    </section>
  );
}
