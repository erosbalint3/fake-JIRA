import { useEffect, useLayoutEffect, useState } from 'react';
import { X } from 'lucide-react';
import { t } from '../i18n';

const KEY = 'fakejira.tour.done';
export const START_TOUR = 'fakejira:start-tour';

const STEPS = [
  { target: '.switcher', title: 'Your projects', body: 'Switch between projects here. Each has its own board, backlog, sprints and members.' },
  { target: '.search-button', title: 'Find anything', body: 'Press Ctrl+K to jump to any task, project, filter or page. Type / on a list to filter it.' },
  { target: '.create-button', title: 'Create tasks', body: 'Press C anywhere to create a task in the current project.' },
  { target: '.nav', title: 'Plan and track', body: 'Board, backlog, roadmap, releases and reports for the project; your dashboard, calendar and search for everything.' },
  { target: '.sidebar-actions', title: 'Make it yours', body: 'Switch the theme, see every keyboard shortcut (?), and set your language and notifications on your profile.' },
];

function done() {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return true;
  }
}

/** A short first-run tour over the sidebar. Non-modal: the app stays usable while it is open. */
export function OnboardingTour() {
  const [step, setStep] = useState<number | null>(() => (done() ? null : 0));
  const [rect, setRect] = useState<DOMRect | null>(null);

  useEffect(() => {
    const start = () => setStep(0);
    window.addEventListener(START_TOUR, start);
    return () => window.removeEventListener(START_TOUR, start);
  }, []);

  useLayoutEffect(() => {
    if (step === null) return;
    const el = document.querySelector(STEPS[step].target);
    el?.classList.add('tour-target');
    const measure = () => setRect(el && el.getClientRects().length ? el.getBoundingClientRect() : null);
    measure();
    window.addEventListener('resize', measure);
    return () => {
      el?.classList.remove('tour-target');
      window.removeEventListener('resize', measure);
    };
  }, [step]);

  if (step === null) return null;
  const finish = () => {
    try {
      localStorage.setItem(KEY, '1');
    } catch {
      /* storage unavailable */
    }
    setStep(null);
  };
  const current = STEPS[step];
  const last = step === STEPS.length - 1;
  const style = rect && window.innerWidth > 800
    ? { left: rect.right + 14, top: Math.min(Math.max(12, rect.top), window.innerHeight - 220) }
    : undefined;

  return (
    <div className={`tour-card ${style ? '' : 'docked'}`} style={style} role="dialog" aria-labelledby="tour-title"
      aria-describedby="tour-body" onKeyDown={(e) => e.key === 'Escape' && finish()}>
      <div className="tour-head">
        <span className="muted small">{t('{n} of {total}', { n: step + 1, total: STEPS.length })}</span>
        <button className="icon-button sm" aria-label={t('Close the tour')} onClick={finish}><X size={14} /></button>
      </div>
      <h2 id="tour-title">{t(current.title)}</h2>
      <p id="tour-body">{t(current.body)}</p>
      <div className="tour-actions">
        <button className="btn btn-ghost btn-sm" onClick={finish}>{t('Skip')}</button>
        {step > 0 && <button className="btn btn-ghost btn-sm" onClick={() => setStep(step - 1)}>{t('Back')}</button>}
        <button className="btn btn-primary btn-sm" autoFocus onClick={() => (last ? finish() : setStep(step + 1))}>
          {last ? t('Done') : t('Next')}</button>
      </div>
    </div>
  );
}
