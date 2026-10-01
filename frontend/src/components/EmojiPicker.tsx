import { useEffect, useRef, useState } from 'react';
import { t } from '../i18n';

export const EMOJI = ['🚀', '🐛', '✨', '🎯', '📦', '🔒', '💳', '📱', '🖥️', '🌐', '⚙️', '🧪', '📊', '📝', '🎨', '🔍',
  '🛒', '💬', '📣', '🧭', '🏗️', '🧰', '🔥', '⭐', '🌱', '🍀', '🐙', '🦄', '🐝', '🌈', '☁️', '⚡',
  '🧠', '🤖', '📚', '🎓', '🏥', '🏦', '🚚', '✈️', '🎮', '🎵', '📷', '🗺️', '🧾', '💡', '✅', '🔧'];

/** A small grid of emoji to pick from; empty clears the icon. */
export function EmojiPicker({ value, label, onPick, disabled }: {
  value: string | null | undefined;
  label: string;
  onPick: (emoji: string) => void;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);
  return (
    <div className="emoji-picker" ref={ref}>
      <button type="button" className="emoji-trigger" aria-label={label} aria-expanded={open} disabled={disabled}
        onClick={() => setOpen(!open)}>
        {value || <span className="muted small">{t('Icon')}</span>}
      </button>
      {open && (
        <div className="emoji-grid" role="listbox" aria-label={label}>
          {EMOJI.map((e) => (
            <button key={e} type="button" role="option" aria-selected={e === value} className={e === value ? 'active' : ''}
              onClick={() => {
                onPick(e);
                setOpen(false);
              }}>{e}</button>
          ))}
          <button type="button" className="emoji-clear" onClick={() => {
            onPick('');
            setOpen(false);
          }}>{t('No icon')}</button>
        </div>
      )}
    </div>
  );
}
