import { useCallback, useEffect, useState } from 'react';
import { Copy, Globe2, Trash2 } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { formatDate } from '../../format';
import { Modal } from '../Modal';
import { Spinner } from '../States';
import type { ShareLinkInfo, Task } from '../../types';
import { t } from '../../i18n';

/** Read-only public links to this task, for people without an account. */
export function ShareModal({ task, onClose }: { task: Task; onClose: () => void }) {
  const toast = useToast();
  const [links, setLinks] = useState<ShareLinkInfo[] | null>(null);
  const [includeComments, setIncludeComments] = useState(false);
  const [days, setDays] = useState('30');

  const load = useCallback(() => {
    api.shares(task.id).then(setLinks).catch((e: ApiError) => {
      toast(e.message, 'error');
      setLinks([]);
    });
  }, [task.id, toast]);
  useEffect(load, [load]);

  const copy = (url: string) => navigator.clipboard.writeText(url)
    .then(() => toast(t('Link copied'))).catch(() => toast(t('Could not copy'), 'error'));

  return (
    <Modal title={t('Share {key} publicly', { key: task.key })} onClose={onClose} footer={<button className="btn btn-ghost" onClick={onClose}>{t('Close')}</button>}>
      <div className="form">
        <p className="muted small">{t('Anyone with the link can see the title, description, status, checklist and sub-tasks — no sign-in needed. Revoke a link at any time.')}</p>
        <div className="share-create">
          <label className="toggle">
            <input type="checkbox" checked={includeComments} onChange={(e) => setIncludeComments(e.target.checked)} /> {t('Include comments')}
          </label>
          <select value={days} onChange={(e) => setDays(e.target.value)} aria-label={t('Link expiry')}>
            <option value="7">{t('Expires in {n} days', { n: 7 })}</option>
            <option value="30">{t('Expires in {n} days', { n: 30 })}</option>
            <option value="365">{t('Expires in a year')}</option>
            <option value="">{t('Never expires')}</option>
          </select>
          <button className="btn btn-primary btn-sm" onClick={async () => {
            try {
              const link = await api.createShare(task.id, includeComments, days ? Number(days) : null);
              await copy(link.url);
              load();
            } catch (e) {
              toast((e as ApiError).message, 'error');
            }
          }}><Globe2 size={15} /> {t('Create link')}</button>
        </div>
        {!links ? <Spinner /> : links.length > 0 && (
          <ul className="share-list">
            {links.map((l) => (
              <li key={l.id} className={l.expired ? 'expired' : ''}>
                <div className="copy-field">
                  <input readOnly value={l.url} aria-label={t('Share link')} onFocus={(e) => e.target.select()} />
                  <button className="icon-button" aria-label={t('Copy link')} onClick={() => copy(l.url)}><Copy size={16} /></button>
                  <button className="icon-button" aria-label={t('Revoke link')} onClick={async () => {
                    await api.revokeShare(l.id);
                    toast(t('Link revoked'));
                    load();
                  }}><Trash2 size={16} /></button>
                </div>
                <span className="muted small">
                  {l.includeComments ? t('With comments') : t('Without comments')} · {l.views === 1 ? t('1 view') : t('{n} views', { n: l.views })} · {t('by {name}', { name: l.createdBy })}
                  {' · '}{l.expiresAt ? t(l.expired ? 'expired {date}' : 'expires {date}', { date: formatDate(l.expiresAt) }) : t('never expires')}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  );
}
