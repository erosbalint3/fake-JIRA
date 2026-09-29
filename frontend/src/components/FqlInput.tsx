import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { api } from '../api';
import { useProjects } from '../projects';
import type { SearchField, Team } from '../types';

const OPERATORS = ['=', '!=', '~', 'in (', 'not in (', 'is empty', 'is not empty', '>', '>=', '<', '<='];
const JOINERS = ['AND', 'OR', 'NOT', 'ORDER BY'];
const SORT_FIELDS = ['priority', 'due', 'created', 'updated', 'resolved', 'points', 'key', 'status', 'title', 'assignee'];

let fieldsCache: Promise<SearchField[]> | null = null;
let teamsCache: Promise<Team[]> | null = null;

interface Suggestion {
  label: string;
  insert: string;
  hint?: string;
}

/** Tokens before the caret: words, quoted strings, operators and punctuation. */
function tokens(text: string) {
  return [...text.matchAll(/"[^"]*"?|'[^']*'?|!=|>=|<=|!~|[=<>~(),]|[^\s=<>~!(),"']+/g)].map((m) => ({ text: m[0], index: m.index ?? 0 }));
}

interface Props {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  /** Character position of a syntax error, to underline. */
  errorAt?: number | null;
}

/** A one-line query editor with autocomplete for fields, operators and values. */
export function FqlInput({ value, onChange, onSubmit, errorAt }: Props) {
  const { projects } = useProjects();
  const [fields, setFields] = useState<SearchField[]>([]);
  const [teams, setTeams] = useState<Team[]>([]);
  const [caret, setCaret] = useState(value.length);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  // Enter picks a suggestion only after the arrow keys moved through the list; otherwise it runs the query.
  const [navigated, setNavigated] = useState(false);
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    fieldsCache ??= api.searchFields().catch(() => []);
    teamsCache ??= api.teams().catch(() => []);
    fieldsCache.then(setFields);
    teamsCache.then(setTeams);
  }, []);

  const people = useMemo(() => {
    const names = new Map<string, string>();
    (projects ?? []).forEach((p) => p.members.forEach((m) => names.set(m.username, m.displayName)));
    return [...names.entries()];
  }, [projects]);

  const { suggestions, start } = useMemo(() => {
    const before = value.slice(0, caret);
    const partialMatch = /("[^"]*|[^\s=<>~!(),"]*)$/.exec(before);
    const partial = partialMatch ? partialMatch[1] : '';
    const startAt = before.length - partial.length;
    const previous = tokens(before.slice(0, startAt));
    const last = previous[previous.length - 1]?.text.toLowerCase();
    const fieldNames = fields.map((f) => f.name);
    let list: Suggestion[] = [];

    const inOrderBy = /\border\s+by\b/i.test(before.slice(0, startAt));
    const valueFieldIndex = (() => {
      // Walk back over a value list "in (a, b," to the field.
      for (let i = previous.length - 1; i >= 0; i--) {
        const t = previous[i].text.toLowerCase();
        if (['=', '!=', '~', '!~', '>', '>=', '<', '<=', 'in'].includes(t)) {
          return i - 1 - (previous[i - 1]?.text.toLowerCase() === 'not' ? 1 : 0);
        }
        if (!['(', ','].includes(t)) return -1;
      }
      return -1;
    })();

    if (inOrderBy) {
      list = last === 'by' || last === ','
        ? SORT_FIELDS.map((f) => ({ label: f, insert: f }))
        : ['ASC', 'DESC', ','].map((w) => ({ label: w, insert: w }));
    } else if (!last || ['and', 'or', 'not', '('].includes(last)) {
      list = fields.map((f) => ({ label: f.name, insert: f.name, hint: f.hint }));
    } else if (fieldNames.includes(last)) {
      list = OPERATORS.map((o) => ({ label: o, insert: o }));
    } else if (last === 'is') {
      list = ['empty', 'not empty'].map((w) => ({ label: w, insert: w }));
    } else if (valueFieldIndex >= 0) {
      const field = previous[valueFieldIndex]?.text.toLowerCase() ?? '';
      const statics = fields.find((f) => f.name === field)?.values ?? [];
      const values: Suggestion[] = statics.map((v) => ({ label: v, insert: v }));
      if (['assignee', 'reporter', 'watcher'].includes(field)) {
        people.forEach(([username, name]) => values.push({ label: username, insert: username, hint: name }));
        teams.forEach((t) => values.push({ label: `membersOf(${t.handle})`, insert: `membersOf(${t.handle})`, hint: t.name }));
      }
      if (field === 'project') {
        (projects ?? []).forEach((p) => values.push({ label: p.key, insert: p.key, hint: p.name }));
      }
      list = values.filter((v) => v.insert !== 'membersOf(');
    } else {
      list = JOINERS.map((w) => ({ label: w, insert: w }));
    }
    const q = partial.replace(/^"/, '').toLowerCase();
    const filtered = list.filter((s) => s.label.toLowerCase().startsWith(q) && s.label.toLowerCase() !== q).slice(0, 12);
    return { suggestions: filtered, start: startAt };
  }, [value, caret, fields, teams, people, projects]);

  useEffect(() => {
    setActive(0);
    setNavigated(false);
  }, [suggestions.length, start]);

  const accept = (s: Suggestion) => {
    const after = value.slice(caret);
    const needsSpace = !s.insert.endsWith('(') && !after.startsWith(' ');
    const next = value.slice(0, start) + s.insert + (needsSpace ? ' ' : '') + after;
    const position = start + s.insert.length + (needsSpace ? 1 : 0);
    onChange(next);
    requestAnimationFrame(() => {
      ref.current?.setSelectionRange(position, position);
      setCaret(position);
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const showing = open && suggestions.length > 0;
    if (showing && event.key === 'ArrowDown') {
      event.preventDefault();
      setNavigated(true);
      setActive((i) => (navigated ? (i + 1) % suggestions.length : 0));
    } else if (showing && event.key === 'ArrowUp') {
      event.preventDefault();
      setNavigated(true);
      setActive((i) => (i - 1 + suggestions.length) % suggestions.length);
    } else if (showing && (event.key === 'Tab' || (event.key === 'Enter' && navigated))) {
      event.preventDefault();
      accept(suggestions[active]);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      setOpen(false);
      onSubmit();
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className="fql">
      <input
        ref={ref}
        className={`fql-input ${errorAt !== null && errorAt !== undefined ? 'invalid' : ''}`}
        value={value}
        spellCheck={false}
        autoComplete="off"
        role="combobox"
        aria-label="Query"
        aria-autocomplete="list"
        aria-expanded={open && suggestions.length > 0}
        aria-controls="fql-suggestions"
        placeholder='project = WEB AND status != done AND assignee = me ORDER BY priority DESC'
        onChange={(e) => {
          onChange(e.target.value);
          setCaret(e.target.selectionStart ?? e.target.value.length);
          setOpen(true);
        }}
        onKeyDown={onKeyDown}
        onKeyUp={(e) => setCaret(e.currentTarget.selectionStart ?? value.length)}
        onClick={(e) => {
          setCaret(e.currentTarget.selectionStart ?? value.length);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => window.setTimeout(() => setOpen(false), 120)}
      />
      {errorAt !== null && errorAt !== undefined && (
        <div className="fql-error-marker" aria-hidden>
          <span className="fql-error-pad">{value.slice(0, errorAt)}</span>
          <span className="fql-error-caret">^</span>
        </div>
      )}
      {open && suggestions.length > 0 && (
        <ul className="fql-suggestions" id="fql-suggestions" role="listbox">
          {suggestions.map((s, i) => (
            <li key={s.label} role="option" aria-selected={i === active}>
              <button type="button" className={i === active && navigated ? 'active' : ''}
                onMouseDown={(e) => {
                  e.preventDefault();
                  accept(s);
                }}>
                <code>{s.label}</code>
                {s.hint && <span className="muted small">{s.hint}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
