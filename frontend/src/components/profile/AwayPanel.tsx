import { useState, type FormEvent } from 'react';
import { CalendarPlus, Palmtree } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { formatDay, todayIso } from '../../format';
import { SubscribeModal } from '../../pages/CalendarPage';
import type { Profile } from '../../types';
import { t } from '../../i18n';

/** Out-of-office dates (shown to teammates next to your name) and the personal calendar feed. */
export function AwayPanel({ profile, onChange }: { profile: Profile; onChange: () => void }) {
  const toast = useToast();
  const away = profile.away;
  const [from, setFrom] = useState(away.from ?? todayIso());
  const [until, setUntil] = useState(away.until ?? '');
  const [message, setMessage] = useState(away.message ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [subscribing, setSubscribing] = useState(false);
  const active = !!away.until && away.until >= todayIso();

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.setAway(from || null, until, message);
      toast(t('Out-of-office saved'));
      onChange();
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors?.until ?? err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><Palmtree size={17} /> {t('Out of office')}</h2>
      <p className="muted small hint">
        {active
          ? <>{away.from ? t("You're marked away from {from} until {until}.", { from: formatDay(away.from), until: formatDay(away.until!) })
              : t("You're marked away until {until}.", { until: formatDay(away.until!) })}{' '}
            {t('Teammates see this when they assign or mention you.')}</>
          : t('Let teammates know when you are away. It shows next to your name and on the team calendar.')}
      </p>
      <form className="form narrow" onSubmit={save}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid two">
          <label className="field">
            <span>{t('First day')}</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Last day')}</span>
            <input type="date" value={until} min={from || todayIso()} required onChange={(e) => setUntil(e.target.value)} />
          </label>
        </div>
        <label className="field">
          <span>{t('Note')} <span className="muted">{t('(optional)')}</span></span>
          <input value={message} maxLength={200} placeholder={t('On holiday — ask Bob about releases')} onChange={(e) => setMessage(e.target.value)} />
        </label>
        <div className="button-row">
          <button className="btn btn-primary" disabled={busy || !until}>{active ? t('Update') : t('Set out of office')}</button>
          {away.until && (
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={async () => {
              await api.clearAway();
              setUntil('');
              setMessage('');
              toast(t("Welcome back! You're no longer marked away"));
              onChange();
            }}>{t("I'm back")}</button>
          )}
        </div>
      </form>
      <hr className="rule" />
      <h3 className="subsection-title">{t('Calendar feed')}</h3>
      <p className="muted small hint">
        {profile.calendarFeed ? t('Your calendar app is subscribed to your FakeJIRA dates.') : t('See due dates, sprints and releases in your own calendar app.')}
      </p>
      <button className="btn btn-soft" onClick={() => setSubscribing(true)}><CalendarPlus size={16} /> {profile.calendarFeed ? t('Manage feed') : t('Subscribe')}</button>
      {subscribing && <SubscribeModal onClose={() => {
        setSubscribing(false);
        onChange();
      }} />}
    </section>
  );
}
