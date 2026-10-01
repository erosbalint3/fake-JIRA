import { useCallback, useEffect, useState } from 'react';
import { Gauge } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { useLiveRefresh } from '../../live';
import { Avatar } from '../Avatar';
import { formatDay } from '../../format';
import type { SprintCapacity } from '../../types';
import { t } from '../../i18n';

const hours = (n: number) => `${Number(n).toFixed(Number(n) % 1 ? 1 : 0)} h`;

/**
 * Hours each person has in the sprint (working days × hours a day, minus time off and extra days off),
 * against the remaining estimates of their tasks.
 */
export function CapacityPanel({ sprintId, projectId, canEdit }: { sprintId: number; projectId: number; canEdit: boolean }) {
  const toast = useToast();
  const [data, setData] = useState<SprintCapacity | null>(null);

  const load = useCallback(() => {
    api.sprintCapacity(sprintId).then(setData).catch(() => {});
  }, [sprintId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === projectId, load, 1500);

  if (!data) return null;
  const save = async (userId: number, hoursPerDay: number, daysOff: number) => {
    try {
      setData(await api.setCapacity(sprintId, userId, hoursPerDay, daysOff));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };
  const max = Math.max(1, ...data.people.map((p) => Math.max(Number(p.availableHours), Number(p.remainingHours))));

  return (
    <section className="panel capacity-panel">
      <h2 className="panel-title"><Gauge size={16} /> {t('Capacity')}</h2>
      <p className="muted small">
        {formatDay(data.start)} – {formatDay(data.end)} · {t('{n} working days', { n: data.workingDays })}
        {data.datesAssumed && <> · {t('dates assumed (two weeks from next Monday) until the sprint is scheduled')}</>}
        {' · '}{t('Time off from profiles is subtracted automatically.')}
      </p>
      <div className="table-wrap">
        <table className="viz-table capacity-table">
          <thead>
            <tr>
              <th>{t('Person')}</th>
              <th className="num">{t('Hours a day')}</th>
              <th className="num">{t('Days off')}</th>
              <th className="num">{t('Available')}</th>
              <th className="num">{t('Remaining work')}</th>
              <th>{t('Load')}</th>
            </tr>
          </thead>
          <tbody>
            {data.people.map((p) => (
              <tr key={p.user.id} className={p.over ? 'over' : ''}>
                <td><span className="person"><Avatar user={p.user} size={22} /> {p.user.displayName}</span>
                  {p.awayDays > 0 && <span className="muted small"> · {t('{n} days away', { n: p.awayDays })}</span>}</td>
                <td className="num">
                  <input type="number" min={0} max={12} step={0.5} defaultValue={Number(p.hoursPerDay)} disabled={!canEdit}
                    key={`h-${p.user.id}-${p.hoursPerDay}`} aria-label={t('Hours a day for {name}', { name: p.user.displayName })}
                    onBlur={(e) => Number(e.target.value) !== Number(p.hoursPerDay) && save(p.user.id, Number(e.target.value), p.daysOff)} />
                </td>
                <td className="num">
                  <input type="number" min={0} max={60} defaultValue={p.daysOff} disabled={!canEdit}
                    key={`d-${p.user.id}-${p.daysOff}`} aria-label={t('Days off for {name}', { name: p.user.displayName })}
                    onBlur={(e) => Number(e.target.value) !== p.daysOff && save(p.user.id, Number(p.hoursPerDay), Number(e.target.value))} />
                </td>
                <td className="num">{hours(p.availableHours)}</td>
                <td className="num">{hours(p.remainingHours)}
                  {p.unestimated > 0 && <span className="muted small" title={t('Tasks without an estimate')}> +{p.unestimated}?</span>}</td>
                <td className="load-cell">
                  <div className="load-bar" role="img" aria-label={t('{used} of {available}', { used: hours(p.remainingHours), available: hours(p.availableHours) })}>
                    <span className="load-available" style={{ width: `${(Number(p.availableHours) / max) * 100}%` }} />
                    <span className={`load-used ${p.over ? 'over' : ''}`} style={{ width: `${(Number(p.remainingHours) / max) * 100}%` }} />
                  </div>
                  {p.over && <span className="small overdue-text">{t('over by {h}', { h: hours(Number(p.remainingHours) - Number(p.availableHours)) })}</span>}
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th>{t('Team')}</th><td /><td />
              <td className="num"><strong>{hours(data.availableHours)}</strong></td>
              <td className="num"><strong>{hours(data.remainingHours)}</strong></td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
