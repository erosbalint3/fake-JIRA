import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Link2, X } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { StatusBadge } from '../Badges';
import { LINK_TYPES, type LinkType, type TaskLink } from '../../types';

interface Props {
  taskId: number;
  projectKey: string;
  links: TaskLink[];
  canEdit: boolean;
  onChange: () => void;
}

export function LinksPanel({ taskId, projectKey, links, canEdit, onChange }: Props) {
  const toast = useToast();
  const [type, setType] = useState<LinkType>('BLOCKS');
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);

  const add = async (event: FormEvent) => {
    event.preventDefault();
    let key = target.trim().toUpperCase();
    if (/^\d+$/.test(key)) key = `${projectKey}-${key}`;
    if (!key) return;
    setBusy(true);
    try {
      await api.addLink(taskId, type, key);
      setTarget('');
      onChange();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const groups = new Map<string, TaskLink[]>();
  links.forEach((link) => groups.set(link.label, [...(groups.get(link.label) ?? []), link]));

  return (
    <section className="panel">
      <h2 className="panel-title"><Link2 size={16} /> Linked tasks {links.length > 0 && <span className="count">{links.length}</span>}</h2>
      {[...groups.entries()].map(([label, items]) => (
        <div key={label} className="link-group">
          <span className="link-label">{label}</span>
          <ul className="mini-list">
            {items.map((link) => (
              <li key={link.id}>
                <span className="task-key">{link.task.key}</span>
                <Link to={`/tasks/${link.task.id}`} className="mini-title">{link.task.title}</Link>
                <StatusBadge status={link.task.status} />
                {canEdit && (
                  <button className="icon-button sm" aria-label={`Remove link to ${link.task.key}`}
                    onClick={async () => {
                      await api.deleteLink(taskId, link.id).catch((e: ApiError) => toast(e.message, 'error'));
                      onChange();
                    }}>
                    <X size={14} />
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {links.length === 0 && <p className="muted">No links yet.</p>}
      {canEdit && (
        <form className="inline-add link-form" onSubmit={add}>
          <select value={type} onChange={(e) => setType(e.target.value as LinkType)} aria-label="Link type">
            {LINK_TYPES.map((t) => <option key={t.type} value={t.type}>This task {t.outward}</option>)}
          </select>
          <input value={target} placeholder={`${projectKey}-12`} onChange={(e) => setTarget(e.target.value)}
            aria-label="Task key to link" />
          <button className="btn btn-soft btn-sm" disabled={busy || !target.trim()}>Link</button>
        </form>
      )}
    </section>
  );
}
