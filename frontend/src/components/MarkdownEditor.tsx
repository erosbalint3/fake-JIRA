import { useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Avatar } from './Avatar';
import { Markdown } from './Markdown';
import type { User } from '../types';

interface Props {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  rows?: number;
  maxLength?: number;
  /** Members offered when typing @. */
  members?: User[];
  onSubmitShortcut?: () => void;
  label?: string;
  invalid?: boolean;
}

/** Textarea with a Write / Preview toggle and @mention autocomplete. */
export function MarkdownEditor({
  value, onChange, placeholder, rows = 5, maxLength, members = [], onSubmitShortcut, label, invalid,
}: Props) {
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const [caret, setCaret] = useState(0);
  const [highlight, setHighlight] = useState(0);
  const ref = useRef<HTMLTextAreaElement>(null);

  const mention = useMemo(() => {
    const before = value.slice(0, caret);
    const match = /(^|\s)@([A-Za-z0-9._-]{0,40})$/.exec(before);
    if (!match) return null;
    const query = match[2].toLowerCase();
    const options = members.filter((m) => m.username.toLowerCase().startsWith(query)).slice(0, 6);
    return options.length ? { start: before.length - match[2].length, options } : null;
  }, [value, caret, members]);

  const insert = (username: string) => {
    if (!mention) return;
    const next = `${value.slice(0, mention.start)}${username} ${value.slice(caret)}`;
    onChange(next);
    const position = mention.start + username.length + 1;
    requestAnimationFrame(() => {
      ref.current?.focus();
      ref.current?.setSelectionRange(position, position);
      setCaret(position);
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (mention) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const delta = event.key === 'ArrowDown' ? 1 : -1;
        setHighlight((h) => (h + delta + mention.options.length) % mention.options.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        insert(mention.options[Math.min(highlight, mention.options.length - 1)].username);
        return;
      }
    }
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && onSubmitShortcut) {
      event.preventDefault();
      onSubmitShortcut();
    }
  };

  return (
    <div className="md-editor">
      <div className="md-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'write'} className={tab === 'write' ? 'active' : ''}
          onClick={() => setTab('write')}>Write</button>
        <button type="button" role="tab" aria-selected={tab === 'preview'} className={tab === 'preview' ? 'active' : ''}
          onClick={() => setTab('preview')}>Preview</button>
        <span className="md-hint">Markdown supported</span>
      </div>
      {tab === 'write' ? (
        <div className="md-write">
          <textarea
            ref={ref}
            rows={rows}
            value={value}
            maxLength={maxLength}
            placeholder={placeholder}
            aria-label={label}
            aria-invalid={invalid}
            onChange={(e) => {
              onChange(e.target.value);
              setCaret(e.target.selectionStart);
              setHighlight(0);
            }}
            onSelect={(e) => setCaret(e.currentTarget.selectionStart)}
            onKeyDown={onKeyDown}
          />
          {mention && (
            <ul className="mention-menu" role="listbox">
              {mention.options.map((member, index) => (
                <li key={member.id} role="option" aria-selected={index === highlight}>
                  <button type="button" className={index === highlight ? 'active' : ''}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      insert(member.username);
                    }}>
                    <Avatar user={member} size={20} /> {member.username}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <div className="md-preview">
          {value.trim() ? <Markdown>{value}</Markdown> : <p className="muted">Nothing to preview.</p>}
        </div>
      )}
    </div>
  );
}
