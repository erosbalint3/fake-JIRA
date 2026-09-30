import { useEffect, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { api } from '../../api';
import { useLiveRefresh } from '../../live';
import type { SlaState, Task, TaskSla } from '../../types';
import { SLA_STATE_LABEL } from '../reports/InsightReports';
import { t } from '../../i18n';

function Line({ label, state, due, done }: { label: string; state: SlaState; due: string | null; done: string | null }) {
  if (!state || !due) return null;
  return (
    <li>
      <span className="grow">{label}</span>
      <span className={`sla-badge ${state}`}>{t(SLA_STATE_LABEL[state])}</span>
      <span className="muted small">
        {done ? t('at {when}', { when: new Date(done).toLocaleString() }) : t('due {when}', { when: new Date(due).toLocaleString() })}
      </span>
    </li>
  );
}

/** The task's service-level targets, when its project has them for this priority. */
export function SlaPanel({ task }: { task: Task }) {
  const [sla, setSla] = useState<TaskSla | null>(null);
  const load = () => {
    api.taskSla(task.id).then((result) => setSla(result ?? null)).catch(() => setSla(null));
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [task.id, task.priority, task.status, task.updatedAt]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === task.id, load, 800);
  if (!sla) return null;
  return (
    <section className="side-section">
      <h3 className="side-title"><ShieldCheck size={15} aria-hidden /> {t('SLA')}</h3>
      <ul className="mini-list sla-lines">
        <Line label={t('First response')} state={sla.responseState} due={sla.responseDueAt} done={sla.respondedAt} />
        <Line label={t('Resolution')} state={sla.resolveState} due={sla.resolveDueAt} done={sla.resolvedAt} />
      </ul>
    </section>
  );
}
