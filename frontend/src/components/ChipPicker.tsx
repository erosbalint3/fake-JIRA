import { X } from 'lucide-react';
import { t } from '../i18n';

interface Option {
  id: number;
  label: string;
}

/** Chosen items as removable chips plus a select to add another one. */
export function ChipPicker({ options, selected, onChange, disabled, label, max }: {
  options: Option[];
  selected: number[];
  onChange: (ids: number[]) => void;
  disabled?: boolean;
  label: string;
  max?: number;
}) {
  const remaining = options.filter((o) => !selected.includes(o.id));
  return (
    <div className="chip-picker">
      {selected.map((id) => {
        const option = options.find((o) => o.id === id);
        return (
          <span key={id} className="chip chip-sm">
            {option?.label ?? `#${id}`}
            {!disabled && (
              <button className="chip-remove" aria-label={t('Remove {name}', { name: option?.label ?? '' })}
                onClick={() => onChange(selected.filter((x) => x !== id))}><X size={12} /></button>
            )}
          </span>
        );
      })}
      {!disabled && remaining.length > 0 && (max === undefined || selected.length < max) && (
        <select className="chip-add" aria-label={label} value="" onChange={(e) => e.target.value && onChange([...selected, Number(e.target.value)])}>
          <option value="">+ {t('Add')}</option>
          {remaining.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
        </select>
      )}
      {selected.length === 0 && (disabled || remaining.length === 0) && <span className="muted">{t('None')}</span>}
    </div>
  );
}
