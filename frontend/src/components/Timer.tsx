import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Pause, Play, Timer as TimerIcon, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { formatMinutes } from '../format';
import type { RunningTimer, Task } from '../types';
import { Modal } from './Modal';
import { t } from '../i18n';

const TIMER_CHANGED = 'fakejira:timer-changed';
/** After this long without mouse or keyboard activity, the timer asks what to do with the time away. */
const IDLE_MINUTES = 10;

export function formatElapsed(seconds: number) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** The running timer (null when none), kept in sync across tabs and components. */
export function useTimer() {
  const [timer, setTimer] = useState<RunningTimer | null | undefined>(undefined);
  // The server's elapsed time plus the local clock since it was fetched, so clock skew does not matter.
  const [base, setBase] = useState({ at: Date.now(), seconds: 0 });
  const [, tick] = useState(0);

  const load = useCallback(() => {
    api.timer().then((current) => {
      setTimer(current ?? null);
      setBase({ at: Date.now(), seconds: current?.elapsedSeconds ?? 0 });
    }).catch(() => setTimer(null));
  }, []);

  useEffect(() => {
    load();
    window.addEventListener(TIMER_CHANGED, load);
    return () => window.removeEventListener(TIMER_CHANGED, load);
  }, [load]);
  useLiveRefresh((m) => m.type === 'timer', load, 100);

  useEffect(() => {
    if (!timer) return;
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [timer]);

  const elapsed = timer ? base.seconds + Math.floor((Date.now() - base.at) / 1000) : 0;
  return { timer, elapsed, reload: load };
}

export function timerChanged() {
  window.dispatchEvent(new Event(TIMER_CHANGED));
}

/** Start/stop button for a task page. */
export function TimerButton({ task, disabled }: { task: Task; disabled?: boolean }) {
  const { timer, elapsed } = useTimer();
  const toast = useToast();
  const [stopping, setStopping] = useState(false);
  const running = timer?.task.id === task.id;

  const start = async () => {
    try {
      const result = await api.startTimer(task.id);
      if (result.logged && timer) {
        toast(t('Logged {time} on {key}', { time: formatMinutes(result.logged.minutes), key: timer.task.key }));
      }
      timerChanged();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <>
      {running ? (
        <button className="btn btn-soft timer-running" onClick={() => setStopping(true)}>
          <Pause size={16} /> {t('Stop timer')} <span className="mono">{formatElapsed(elapsed)}</span>
        </button>
      ) : (
        <button className="btn btn-soft" onClick={start} disabled={disabled}
          title={timer ? t('Stops the timer on {key} and logs its time', { key: timer.task.key }) : undefined}>
          <Play size={16} /> {t('Start timer')}
        </button>
      )}
      {stopping && timer && <StopTimerModal timer={timer} elapsed={elapsed} onClose={() => setStopping(false)} />}
    </>
  );
}

function StopTimerModal({ timer, elapsed, awayMinutes, onClose }: {
  timer: RunningTimer; elapsed: number; awayMinutes?: number; onClose: () => void;
}) {
  const toast = useToast();
  const measured = Math.max(1, Math.ceil(elapsed / 60));
  const [minutes, setMinutes] = useState(String(Math.max(0, measured - (awayMinutes ?? 0))));
  const [note, setNote] = useState('');
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const value = Number(minutes);
    if (!Number.isInteger(value) || value < 0 || value > 1440) {
      setError(t('Enter between 0 and 1440 minutes.'));
      return;
    }
    try {
      const result = await api.stopTimer(value, note.trim() || undefined);
      toast(result.logged
        ? t('Logged {time} on {key}', { time: formatMinutes(result.logged.minutes), key: timer.task.key })
        : t('Timer stopped; nothing logged.'));
      timerChanged();
      onClose();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };

  return (
    <Modal title={t('Stop timer')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" type="submit" form="stop-timer-form">{t('Log time')}</button>
      </>
    }>
      <form id="stop-timer-form" className="form" onSubmit={submit}>
        {awayMinutes ? (
          <p className="muted">{t('You were away for {n} minutes; that time is left out below.', { n: awayMinutes })}</p>
        ) : null}
        <p>{timer.task.key} · {timer.task.title}</p>
        <label className="field">
          <span>{t('Minutes to log')} <span className="muted small">({t('measured')}: {measured})</span></span>
          <input type="number" min={0} max={1440} value={minutes} onChange={(e) => setMinutes(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('Note')}</span>
          <input value={note} maxLength={200} placeholder={t('Timer')} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error && <p className="field-error" role="alert">{error}</p>}
      </form>
    </Modal>
  );
}

/** The floating stopwatch shown while a timer runs, with the idle check. */
export function TimerWidget() {
  const { timer, elapsed } = useTimer();
  const toast = useToast();
  const [stopping, setStopping] = useState<{ away?: number } | null>(null);
  const [idle, setIdle] = useState<number | null>(null);
  const lastActive = useRef(Date.now());
  const running = useRef(false);
  running.current = !!timer;

  useEffect(() => {
    const activity = () => {
      const away = (Date.now() - lastActive.current) / 60000;
      lastActive.current = Date.now();
      if (running.current && away >= IDLE_MINUTES) setIdle(Math.floor(away));
    };
    const visible = () => document.visibilityState === 'visible' && activity();
    const events = ['mousemove', 'keydown', 'pointerdown', 'wheel', 'touchstart'] as const;
    events.forEach((e) => window.addEventListener(e, activity, { passive: true }));
    document.addEventListener('visibilitychange', visible);
    return () => {
      events.forEach((e) => window.removeEventListener(e, activity));
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);

  if (!timer) return null;

  const discard = async () => {
    try {
      await api.discardTimer();
      toast(t('Timer discarded'));
      timerChanged();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <>
      <div className="timer-pill" role="status" aria-label={t('Running timer')}>
        <TimerIcon size={16} aria-hidden />
        <Link to={`/tasks/${timer.task.id}`} className="timer-task" title={timer.task.title}>{timer.task.key}</Link>
        <span className="mono">{formatElapsed(elapsed)}</span>
        <button className="btn btn-soft btn-sm" onClick={() => setStopping({})}><Pause size={14} /> {t('Stop')}</button>
        <button className="icon-button" onClick={discard} aria-label={t('Discard timer')} title={t('Discard timer')}>
          <Trash2 size={15} />
        </button>
      </div>
      {idle !== null && !stopping && (
        <Modal title={t('Were you still working?')} onClose={() => setIdle(null)} footer={
          <>
            <button className="btn btn-ghost" onClick={() => setIdle(null)}>{t('Keep the time')}</button>
            <button className="btn btn-primary" onClick={() => {
              setStopping({ away: idle });
              setIdle(null);
            }}>{t('Stop and leave out {n} min', { n: idle })}</button>
          </>
        }>
          <p>{t('Your timer on {key} kept running while you were away for {n} minutes.', { key: timer.task.key, n: idle })}</p>
        </Modal>
      )}
      {stopping && <StopTimerModal timer={timer} elapsed={elapsed} awayMinutes={stopping.away} onClose={() => setStopping(null)} />}
    </>
  );
}
