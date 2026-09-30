import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { BadgeCheck, Check, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { useLiveRefresh } from '../../live';
import { Avatar } from '../Avatar';
import { timeAgo } from '../../format';
import type { Approval, Member } from '../../types';
import { t } from '../../i18n';

const STATE_LABEL = { PENDING: 'Waiting', APPROVED: 'Approved', REJECTED: 'Rejected' } as const;

/** Sign-offs on a task: ask a member, and the approver approves or rejects with a note. */
export function ApprovalsPanel({ taskId, members, canEdit, userId }: { taskId: number; members: Member[]; canEdit: boolean; userId: number }) {
  const toast = useToast();
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [asking, setAsking] = useState(false);
  const [approver, setApprover] = useState('');
  const [note, setNote] = useState('');
  const [deciding, setDeciding] = useState<{ approval: Approval; approve: boolean } | null>(null);
  const [decisionNote, setDecisionNote] = useState('');

  const load = useCallback(() => {
    api.approvals(taskId).then(setApprovals).catch(() => {});
  }, [taskId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, load, 500);

  const ask = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await api.requestApproval(taskId, Number(approver), note.trim());
      toast(t('Approval requested'));
      setAsking(false);
      setApprover('');
      setNote('');
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const decide = async (event: FormEvent) => {
    event.preventDefault();
    if (!deciding) return;
    try {
      await api.decideApproval(deciding.approval.id, deciding.approve, decisionNote.trim());
      toast(deciding.approve ? t('Approved') : t('Rejected'));
      setDeciding(null);
      setDecisionNote('');
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const candidates = members.filter((m) => !approvals.some((a) => a.approver.id === m.id && a.state === 'PENDING'));
  if (!approvals.length && !canEdit) return null;

  return (
    <section className="side-section" id="approvals">
      <h3 className="side-title"><BadgeCheck size={15} /> {t('Approvals')}</h3>
      {approvals.length > 0 && (
        <ul className="approval-list">
          {approvals.map((a) => (
            <li key={a.id} className={`approval approval-${a.state.toLowerCase()}`}>
              <Avatar user={a.approver} size={22} />
              <div className="approval-body">
                <div><strong>{a.approver.displayName}</strong> <span className={`approval-state ${a.state.toLowerCase()}`}>{t(STATE_LABEL[a.state])}</span></div>
                {a.request && <div className="small muted">“{a.request}”</div>}
                {a.decisionNote && <div className="small">{a.decisionNote}</div>}
                <div className="small muted">{a.decidedAt ? timeAgo(a.decidedAt) : t('asked {when}', { when: timeAgo(a.createdAt) })}</div>
                {a.canDecide && a.state === 'PENDING' && (
                  <div className="approval-actions">
                    <button className="btn btn-soft btn-sm" onClick={() => setDeciding({ approval: a, approve: true })}><Check size={14} /> {t('Approve')}</button>
                    <button className="btn btn-ghost btn-sm" onClick={() => setDeciding({ approval: a, approve: false })}><X size={14} /> {t('Reject')}</button>
                  </div>
                )}
                {canEdit && a.requestedBy.id === userId && a.state === 'PENDING' && (
                  <button className="link small" onClick={async () => {
                    await api.withdrawApproval(a.id).catch((e: ApiError) => toast(e.message, 'error'));
                    load();
                  }}>{t('Withdraw')}</button>
                )}
                {canEdit && a.state === 'REJECTED' && (
                  <button className="link small" onClick={async () => {
                    await api.requestApproval(taskId, a.approver.id, a.request).catch((e: ApiError) => toast(e.message, 'error'));
                    load();
                  }}>{t('Ask again')}</button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {deciding && (
        <form className="approval-form" onSubmit={decide}>
          <textarea rows={2} maxLength={500} value={decisionNote} autoFocus
            placeholder={deciding.approve ? t('Note (optional)') : t('What needs to change?')}
            aria-label={t('Note')} onChange={(e) => setDecisionNote(e.target.value)} />
          <div className="button-row">
            <button className={`btn btn-sm ${deciding.approve ? 'btn-primary' : 'btn-danger'}`}>
              {deciding.approve ? t('Approve') : t('Reject')}</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeciding(null)}>{t('Cancel')}</button>
          </div>
        </form>
      )}
      {canEdit && !asking && candidates.length > 0 && (
        <button className="btn btn-ghost btn-sm" onClick={() => setAsking(true)}>{t('Ask for approval')}</button>
      )}
      {asking && (
        <form className="approval-form" onSubmit={ask}>
          <select value={approver} required aria-label={t('Approver')} onChange={(e) => setApprover(e.target.value)}>
            <option value="">{t('Who should approve?')}</option>
            {candidates.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
          </select>
          <textarea rows={2} maxLength={500} value={note} placeholder={t('What should they check? (optional)')}
            aria-label={t('Note')} onChange={(e) => setNote(e.target.value)} />
          <div className="button-row">
            <button className="btn btn-primary btn-sm" disabled={!approver}>{t('Ask')}</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAsking(false)}>{t('Cancel')}</button>
          </div>
        </form>
      )}
    </section>
  );
}
