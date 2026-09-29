import { useEffect, useRef, useState } from 'react';
import { MoreHorizontal } from 'lucide-react';

export interface MenuAction {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  hidden?: boolean;
}

/** A "…" button with a small dropdown of actions. */
export function ActionMenu({ label, actions }: { label: string; actions: MenuAction[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const visible = actions.filter((a) => !a.hidden);
  if (visible.length === 0) return null;
  return (
    <div className="menu" ref={ref}>
      <button className="icon-button" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
        <MoreHorizontal size={18} />
      </button>
      {open && (
        <div className="menu-list" role="menu">
          {visible.map((action) => (
            <button key={action.label} role="menuitem" className={action.danger ? 'danger' : ''} onClick={() => {
              setOpen(false);
              action.onSelect();
            }}>{action.label}</button>
          ))}
        </div>
      )}
    </div>
  );
}
