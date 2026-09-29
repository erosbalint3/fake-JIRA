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
  /** Enables pasting/dropping images: uploads one and resolves to the Markdown that shows it. */
  onUploadImage?: (file: File) => Promise<string>;
}

/** Textarea with a Write / Preview toggle, @mention autocomplete and (optionally) image paste. */
export function MarkdownEditor({
  value, onChange, placeholder, rows = 5, maxLength, members = [], onSubmitShortcut, label, invalid, onUploadImage,
}: Props) {
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const [caret, setCaret] = useState(0);
  const [highlight, setHighlight] = useState(0);
  const [uploading, setUploading] = useState(0);
  const ref = useRef<HTMLTextAreaElement>(null);
  const valueRef = useRef(value);
  valueRef.current = value;

  /** Inserts a placeholder at the caret, uploads, then swaps in the image Markdown. */
  const uploadImages = (files: File[]) => {
    if (!onUploadImage) return false;
    const images = files.filter((f) => f.type.startsWith('image/'));
    if (images.length === 0) return false;
    const at = ref.current?.selectionStart ?? valueRef.current.length;
    images.forEach((file, index) => {
      const marker = `![Uploading ${file.name || 'image'}${index ? ` ${index + 1}` : ''}…]()`;
      const current = valueRef.current;
      const insertAt = Math.min(at, current.length);
      const next = `${current.slice(0, insertAt)}${marker}\n${current.slice(insertAt)}`;
      valueRef.current = next;
      onChange(next);
      setUploading((n) => n + 1);
      onUploadImage(file)
        .then((markdown) => {
          valueRef.current = valueRef.current.replace(marker, markdown);
          onChange(valueRef.current);
        })
        .catch(() => {
          valueRef.current = valueRef.current.replace(`${marker}\n`, '').replace(marker, '');
          onChange(valueRef.current);
        })
        .finally(() => setUploading((n) => n - 1));
    });
    return true;
  };

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
        <span className="md-hint">
          {uploading > 0 ? 'Uploading image…' : onUploadImage ? 'Markdown · paste or drop images' : 'Markdown supported'}
        </span>
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
            onPaste={(e) => {
              if (uploadImages(Array.from(e.clipboardData.files))) e.preventDefault();
            }}
            onDragOver={(e) => {
              if (onUploadImage && Array.from(e.dataTransfer.items).some((i) => i.type.startsWith('image/'))) e.preventDefault();
            }}
            onDrop={(e) => {
              if (uploadImages(Array.from(e.dataTransfer.files))) {
                e.preventDefault();
                e.stopPropagation();
              }
            }}
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
