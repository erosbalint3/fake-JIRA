import { useState, type FormEvent } from 'react';
import { CalendarPlus, Palmtree } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { formatDay, todayIso } from '../../format';
import { SubscribeModal } from '../../pages/CalendarPage';
import type { Profile } from '../../types';

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
      toast('Out-of-office saved');
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
      <h2 className="panel-title"><Palmtree size={17} /> Out of office</h2>
      <p className="muted small hint">
        {active
          ? <>You're marked away{away.from ? ` from ${formatDay(away.from)}` : ''} until <strong>{formatDay(away.until!)}</strong>.
            Teammates see this when they assign or mention you.</>
          : 'Let teammates know when you are away. It shows next to your name and on the team calendar.'}
      </p>
      <form className="form narrow" onSubmit={save}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid two">
          <label className="field">
            <span>First day</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="field">
            <span>Last day</span>
            <input type="date" value={until} min={from || todayIso()} required onChange={(e) => setUntil(e.target.value)} />
          </label>
        </div>
        <label className="field">
          <span>Note <span className="muted">(optional)</span></span>
          <input value={message} maxLength={200} placeholder="On holiday — ask Bob about releases" onChange={(e) => setMessage(e.target.value)} />
        </label>
        <div className="button-row">
          <button className="btn btn-primary" disabled={busy || !until}>{active ? 'Update' : 'Set out of office'}</button>
          {away.until && (
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={async () => {
              await api.clearAway();
              setUntil('');
              setMessage('');
              toast("Welcome back! You're no longer marked away");
              onChange();
            }}>I'm back</button>
          )}
        </div>
      </form>
      <hr className="rule" />
      <h3 className="subsection-title">Calendar feed</h3>
      <p className="muted small hint">
        {profile.calendarFeed ? 'Your calendar app is subscribed to your FakeJIRA dates.' : 'See due dates, sprints and releases in your own calendar app.'}
      </p>
      <button className="btn btn-soft" onClick={() => setSubscribing(true)}><CalendarPlus size={16} /> {profile.calendarFeed ? 'Manage feed' : 'Subscribe'}</button>
      {subscribing && <SubscribeModal onClose={() => {
        setSubscribing(false);
        onChange();
      }} />}
    </section>
  );
}
