import { useEffect, useRef, useState } from 'react';
import { Bell, BellOff, BellRing, Check } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import type { NotificationLevel } from '../types';
import { LEVEL_LABEL } from './profile/NotificationRulesPanel';
import { t } from '../i18n';

/** Quick per-project notification level: everything, only things for me, or muted. */
export function ProjectNotifyButton({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [level, setLevel] = useState<NotificationLevel | null | undefined>(undefined);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api.projectNotificationRule(projectKey).then((rule) => setLevel(rule.level)).catch(() => setLevel(null));
  }, [projectKey]);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => !ref.current?.contains(event.target as Node) && setOpen(false);
    const escape = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const choose = async (next: NotificationLevel | null) => {
    setOpen(false);
    try {
      if (next === null) await api.deleteProjectNotificationRule(projectKey);
      else await api.saveProjectNotificationRule(projectKey, { level: next, email: null, push: null });
      setLevel(next);
      toast(next === 'MUTED' ? t('{key} muted', { key: projectKey })
        : next === null ? t('{key} follows your default rule', { key: projectKey })
          : t('Notifications for {key}: {level}', { key: projectKey, level: t(LEVEL_LABEL[next]).toLowerCase() }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  if (level === undefined) return null;
  const Icon = level === 'MUTED' ? BellOff : level === 'DIRECT' ? BellRing : Bell;
  const label = level ? t(LEVEL_LABEL[level]) : t('Default');
  const options: [NotificationLevel | null, string][] = [
    [null, t('My default rule')], ['ALL', t(LEVEL_LABEL.ALL)], ['DIRECT', t(LEVEL_LABEL.DIRECT)], ['MUTED', t(LEVEL_LABEL.MUTED)],
  ];

  return (
    <div className="menu" ref={ref}>
      <button className={`btn btn-ghost btn-sm ${level === 'MUTED' ? 'is-on' : ''}`} aria-haspopup="menu" aria-expanded={open}
        aria-label={t('Notifications for this project: {level}', { level: label })} onClick={() => setOpen(!open)}>
        <Icon size={15} /> {level === 'MUTED' ? t('Muted') : t('Notify')}
      </button>
      {open && (
        <div className="menu-list" role="menu">
          {options.map(([value, text]) => (
            <button key={text} role="menuitemradio" aria-checked={level === value} onClick={() => choose(value)}>
              <span className="menu-check">{level === value && <Check size={14} />}</span> {text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
