import { Avatar } from '../Avatar';
import type { Present } from '../../collab';
import { t } from '../../i18n';

/** Who else has this task open right now, and whether they are editing it. */
export function PresenceBar({ present }: { present: Present[] }) {
  if (present.length === 0) return null;
  const editors = present.filter((p) => p.editing);
  return (
    <div className="presence-bar" role="status" aria-live="polite">
      <span className="avatar-stack">
        {present.map((p) => (
          <span key={p.user.id} className={`presence-avatar ${p.editing ? 'editing' : ''}`}
            title={p.editing ? t('{name} is editing', { name: p.user.displayName }) : t('{name} is viewing', { name: p.user.displayName })}>
            <Avatar user={p.user} size={24} />
          </span>
        ))}
      </span>
      <span className="small muted">
        {editors.length
          ? t('{names} editing', { names: editors.map((p) => p.user.displayName).join(', ') })
          : t('{names} viewing', { names: present.map((p) => p.user.displayName).join(', ') })}
      </span>
    </div>
  );
}
