import { useCallback, useEffect, useState } from 'react';
import { CalendarClock, Eye, Pencil, Send, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { ReportSubscription } from '../../types';
import { Modal } from '../Modal';
import { ScheduleReportModal, scheduleText } from '../reports/ScheduleReportModal';
import { t } from '../../i18n';

const KIND_LABEL = { filter: 'Query', project: 'Project summary', dashboard: 'Dashboard' };

/** The reports the user gets by email, with a preview and "send now". */
export function ScheduledReportsPanel() {
  const toast = useToast();
  const [items, setItems] = useState<ReportSubscription[] | null>(null);
  const [editing, setEditing] = useState<ReportSubscription | null>(null);
  const [preview, setPreview] = useState<{ subject: string; body: string } | null>(null);
  const load = useCallback(() => {
    api.reportSubscriptions().then(setItems).catch(() => setItems([]));
  }, []);
  useEffect(load, [load]);

  const run = async (action: () => Promise<unknown>) => {
    try {
      await action();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel" id="scheduled-reports">
      <h2 className="panel-title"><CalendarClock size={16} aria-hidden /> {t('Scheduled reports')}</h2>
      {items && items.length === 0 && (
        <p className="muted small hint">{t('Use “Email me” on a project’s reports, a search or a dashboard to get it by email every day or week.')}</p>
      )}
      {items && items.length > 0 && (
        <ul className="mini-list">
          {items.map((s) => (
            <li key={s.id}>
              <span className="grow">
                <strong>{s.title}</strong>
                <span className="muted small"> · {t(KIND_LABEL[s.kind])} · {scheduleText(s)}</span>
              </span>
              <button className="icon-button" aria-label={t('Preview {title}', { title: s.title })}
                onClick={() => run(async () => setPreview(await api.previewReport(s.id)))}><Eye size={15} /></button>
              <button className="icon-button" aria-label={t('Send {title} now', { title: s.title })}
                onClick={() => run(async () => {
                  const result = await api.sendReport(s.id);
                  if (result.sent) toast(t('Sent to your email'));
                  else setPreview(result);
                })}><Send size={15} /></button>
              <button className="icon-button" aria-label={t('Edit {title}', { title: s.title })} onClick={() => setEditing(s)}><Pencil size={15} /></button>
              <button className="icon-button" aria-label={t('Delete {title}', { title: s.title })}
                onClick={() => run(async () => {
                  await api.deleteReportSubscription(s.id);
                  toast(t('Scheduled report removed'));
                  load();
                })}><Trash2 size={15} /></button>
            </li>
          ))}
        </ul>
      )}
      {editing && <ScheduleReportModal kind={editing.kind} target={editing.target} defaultTitle={editing.title}
        existing={editing} onClose={() => setEditing(null)} onSaved={load} />}
      {preview && (
        <Modal title={preview.subject} onClose={() => setPreview(null)} wide>
          <pre className="report-preview">{preview.body}</pre>
        </Modal>
      )}
    </section>
  );
}
