import { useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import { t } from '../i18n';

/** Chip input for labels; Enter or comma adds, Backspace on empty removes the last one. */
export function LabelInput({ value, onChange, suggestions = [] }: {
  value: string[];
  onChange: (labels: string[]) => void;
  suggestions?: string[];
}) {
  const [draft, setDraft] = useState('');

  const add = (raw: string) => {
    const label = raw.trim().replace(/\s+/g, '-').toLowerCase().slice(0, 30);
    if (label && !value.includes(label) && value.length < 10) onChange([...value, label].sort());
    setDraft('');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      add(draft);
    } else if (event.key === 'Backspace' && !draft && value.length) {
      onChange(value.slice(0, -1));
    }
  };

  const open = suggestions.filter((s) => !value.includes(s) && s.startsWith(draft.toLowerCase())).slice(0, 8);

  return (
    <div className="label-input">
      <div className="label-input-box">
        {value.map((label) => (
          <span key={label} className="label-chip">
            {label}
            <button type="button" onClick={() => onChange(value.filter((l) => l !== label))} aria-label={t('Remove {name}', { name: label })}>
              <X size={12} />
            </button>
          </span>
        ))}
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value.replace(',', ''))}
          onKeyDown={onKeyDown}
          onBlur={() => draft && add(draft)}
          placeholder={value.length ? '' : t('Add labels…')}
          aria-label={t('Add label')}
        />
      </div>
      {open.length > 0 && (
        <div className="label-suggestions">
          {open.map((s) => (
            <button key={s} type="button" className="label-chip suggestion" onMouseDown={(e) => {
              e.preventDefault();
              add(s);
            }}>+ {s}</button>
          ))}
        </div>
      )}
    </div>
  );
}
