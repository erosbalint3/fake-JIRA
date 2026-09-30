import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { ReportKind, ReportSubscription } from '../../types';
import { Modal } from '../Modal';
import { t } from '../../i18n';

export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export function scheduleText(s: Pick<ReportSubscription, 'frequency' | 'weekday' | 'hour'>) {
  const time = `${String(s.hour).padStart(2, '0')}:00`;
  return s.frequency === 'DAILY'
    ? t('Every day at {time}', { time })
    : t('Every {day} at {time}', { day: t(WEEKDAYS[s.weekday - 1]), time });
}

/** Schedules (or edits) an emailed report of a query, a project or a dashboard. */
export function ScheduleReportModal({ kind, target, defaultTitle, existing, onClose, onSaved }: {
  kind: ReportKind; target: string; defaultTitle: string; existing?: ReportSubscription;
  onClose: () => void; onSaved?: () => void;
}) {
  const toast = useToast();
  const [title, setTitle] = useState(existing?.title ?? defaultTitle);
  const [frequency, setFrequency] = useState<'DAILY' | 'WEEKLY'>(existing?.frequency ?? 'WEEKLY');
  const [weekday, setWeekday] = useState(existing?.weekday ?? 1);
  const [hour, setHour] = useState(existing?.hour ?? 8);
  const [error, setError] = useState('');

  const save = async (event: FormEvent) => {
    event.preventDefault();
    const input = { kind, target, title: title.trim(), frequency, weekday, hour };
    try {
      if (existing) await api.updateReportSubscription(existing.id, input);
      else await api.createReportSubscription(input);
      toast(t('Scheduled: {when}', { when: scheduleText({ frequency, weekday, hour }) }));
      onSaved?.();
      onClose();
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors?.target ?? err.message);
    }
  };

  return (
    <Modal title={existing ? t('Edit scheduled report') : t('Email me this report')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" type="submit" form="schedule-form">{t('Save schedule')}</button>
      </>
    }>
      <form id="schedule-form" className="form" onSubmit={save}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t('Subject')}</span>
          <input value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <div className="form-grid two">
          <label className="field">
            <span>{t('How often')}</span>
            <select value={frequency} onChange={(e) => setFrequency(e.target.value as 'DAILY' | 'WEEKLY')}>
              <option value="DAILY">{t('Daily')}</option>
              <option value="WEEKLY">{t('Weekly')}</option>
            </select>
          </label>
          {frequency === 'WEEKLY' && (
            <label className="field">
              <span>{t('Day')}</span>
              <select value={weekday} onChange={(e) => setWeekday(Number(e.target.value))}>
                {WEEKDAYS.map((d, i) => <option key={d} value={i + 1}>{t(d)}</option>)}
              </select>
            </label>
          )}
          <label className="field">
            <span>{t('Time')}</span>
            <select value={hour} onChange={(e) => setHour(Number(e.target.value))}>
              {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{String(h).padStart(2, '0')}:00</option>)}
            </select>
          </label>
        </div>
        <p className="muted small">
          {t('Times are in your time zone from')} <Link to="/profile#notifications" onClick={onClose}>{t('notification settings')}</Link>.
        </p>
      </form>
    </Modal>
  );
}
