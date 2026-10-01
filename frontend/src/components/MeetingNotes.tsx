import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ListChecks, NotebookPen, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useToast } from '../toast';
import { useLiveRefresh } from '../live';
import { Avatar } from './Avatar';
import { Markdown } from './Markdown';
import { MarkdownEditor } from './MarkdownEditor';
import { ConfirmDialog, Modal } from './Modal';
import { EmptyState, Spinner } from './States';
import { formatDay, todayIso } from '../format';
import { isReadOnlyRole, type MeetingKind, type MeetingNote, type Member, type Sprint } from '../types';
import { t } from '../i18n';

const KINDS: { value: MeetingKind; label: string }[] = [
  { value: 'STANDUP', label: 'Stand-up' },
  { value: 'PLANNING', label: 'Planning' },
  { value: 'REVIEW', label: 'Review' },
  { value: 'RETRO', label: 'Retrospective' },
  { value: 'OTHER', label: 'Other' },
];
const KIND_LABEL = Object.fromEntries(KINDS.map((k) => [k.value, k.label])) as Record<MeetingKind, string>;

/** Meeting notes (for the project, or one sprint) with action items that become tasks in one click. */
export function MeetingNotes({ projectKey, projectId, members, canEdit, sprintId, sprints }: {
  projectKey: string;
  projectId: number;
  members: Member[];
  canEdit: boolean;
  sprintId?: number;
  sprints?: Sprint[];
}) {
  const toast = useToast();
  const [notes, setNotes] = useState<MeetingNote[] | null>(null);
  const [editing, setEditing] = useState<MeetingNote | 'new' | null>(null);
  const [deleting, setDeleting] = useState<MeetingNote | null>(null);
  const [actionText, setActionText] = useState<Record<number, string>>({});
  const [actionWho, setActionWho] = useState<Record<number, string>>({});
  const workers = members.filter((m) => !isReadOnlyRole(m.role));

  const load = useCallback(() => {
    api.meetings(projectKey, sprintId).then(setNotes).catch(() => setNotes([]));
  }, [projectKey, sprintId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === projectId, load, 800);

  const replace = (note: MeetingNote) => setNotes((list) => list?.map((n) => (n.id === note.id ? note : n)) ?? null);
  const act = async (action: () => Promise<MeetingNote>, message?: string) => {
    try {
      replace(await action());
      if (message) toast(message);
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <div className="meetings">
      {canEdit && (
        <div className="button-row">
          <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}><Plus size={15} /> {t('New meeting notes')}</button>
        </div>
      )}
      {!notes && <Spinner />}
      {notes && notes.length === 0 && (
        <EmptyState icon={<NotebookPen size={28} />} title={t('No meeting notes yet')}>
          {t('Write down what was agreed; action items become tasks in one click.')}
        </EmptyState>
      )}
      {notes?.map((note) => {
        const open = note.actions.filter((a) => !a.task).length;
        return (
          <article key={note.id} className="panel meeting">
            <header className="meeting-head">
              <div>
                <span className="chip chip-sm">{t(KIND_LABEL[note.kind])}</span>
                <h3>{note.title}</h3>
                <span className="muted small">{formatDay(note.date)}{note.sprintName ? ` · ${note.sprintName}` : ''} · <Avatar user={note.createdBy} size={16} /> {note.createdBy.displayName}</span>
              </div>
              {canEdit && (
                <div className="header-actions">
                  <button className="icon-button" aria-label={t('Edit {name}', { name: note.title })} onClick={() => setEditing(note)}><Pencil size={15} /></button>
                  <button className="icon-button" aria-label={t('Delete {name}', { name: note.title })} onClick={() => setDeleting(note)}><Trash2 size={15} /></button>
                </div>
              )}
            </header>
            {note.body && <Markdown>{note.body}</Markdown>}
            <section className="actions-block">
              <h4><ListChecks size={14} /> {t('Action items')}</h4>
              {note.actions.length === 0 && <p className="muted small">{t('None yet. Lines like “- [ ] Update the docs @anna” in the notes can be picked up.')}</p>}
              <ul className="action-list">
                {note.actions.map((a) => (
                  <li key={a.id}>
                    <span>{a.text}</span>
                    {a.assignee && <span className="person small"><Avatar user={a.assignee} size={18} /> {a.assignee.displayName}</span>}
                    <span className="spacer" />
                    {a.task
                      ? <Link to={`/tasks/${a.task.id}`} className="task-key">{a.task.key}</Link>
                      : canEdit && <button className="btn btn-ghost btn-sm" onClick={() => act(() => api.actionTasks(note.id, a.id), t('Task created'))}>{t('Create task')}</button>}
                    {canEdit && <button className="icon-button sm" aria-label={t('Remove {name}', { name: a.text })}
                      onClick={() => act(() => api.deleteAction(a.id))}><Trash2 size={13} /></button>}
                  </li>
                ))}
              </ul>
              {canEdit && (
                <div className="inline-form">
                  <form className="inline-form" onSubmit={(e: FormEvent) => {
                    e.preventDefault();
                    const text = (actionText[note.id] ?? '').trim();
                    if (!text) return;
                    act(() => api.addAction(note.id, text, actionWho[note.id] ? Number(actionWho[note.id]) : null));
                    setActionText({ ...actionText, [note.id]: '' });
                  }}>
                    <input value={actionText[note.id] ?? ''} maxLength={200} placeholder={t('New action item')}
                      aria-label={t('New action item')} onChange={(e) => setActionText({ ...actionText, [note.id]: e.target.value })} />
                    <select value={actionWho[note.id] ?? ''} aria-label={t('Owner')} onChange={(e) => setActionWho({ ...actionWho, [note.id]: e.target.value })}>
                      <option value="">{t('Nobody yet')}</option>
                      {workers.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
                    </select>
                    <button className="btn btn-soft btn-sm">{t('Add')}</button>
                  </form>
                  <button className="btn btn-ghost btn-sm" onClick={() => act(() => api.actionsFromNotes(note.id))}>{t('Pick up from notes')}</button>
                  {open > 0 && <button className="btn btn-soft btn-sm" onClick={() => act(() => api.actionTasks(note.id), t('{n} tasks created', { n: open }))}>
                    {t('Create {n} tasks', { n: open })}</button>}
                </div>
              )}
            </section>
          </article>
        );
      })}
      {editing && <NoteModal projectKey={projectKey} note={editing === 'new' ? null : editing} sprintId={sprintId} sprints={sprints}
        onClose={() => setEditing(null)} onSaved={() => {
          setEditing(null);
          load();
        }} />}
      {deleting && (
        <ConfirmDialog title={t('Delete {name}?', { name: deleting.title })} danger confirmLabel={t('Delete')}
          message={t('The notes and their action items are deleted. Tasks already created stay.')}
          onClose={() => setDeleting(null)} onConfirm={async () => {
            await api.deleteMeeting(deleting.id).catch((e: ApiError) => toast(e.message, 'error'));
            setDeleting(null);
            load();
          }} />
      )}
    </div>
  );
}

function NoteModal({ projectKey, note, sprintId, sprints, onClose, onSaved }: {
  projectKey: string;
  note: MeetingNote | null;
  sprintId?: number;
  sprints?: Sprint[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [kind, setKind] = useState<MeetingKind>(note?.kind ?? 'STANDUP');
  const [title, setTitle] = useState(note?.title ?? '');
  const [date, setDate] = useState(note?.date ?? todayIso());
  const [body, setBody] = useState(note?.body ?? '');
  const [sprint, setSprint] = useState<number | null>(note?.sprintId ?? sprintId ?? null);
  const [error, setError] = useState('');
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const input = { kind, title: title.trim() || `${t(KIND_LABEL[kind])} ${date}`, date, body, sprintId: sprint };
    try {
      if (note) await api.updateMeeting(note.id, input);
      else await api.createMeeting(projectKey, input);
      onSaved();
    } catch (e) {
      setError((e as ApiError).message);
    }
  };
  return (
    <Modal wide title={note ? t('Edit meeting notes') : t('New meeting notes')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" form="note-form">{t('Save')}</button>
      </>
    }>
      <form id="note-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <div className="form-grid three">
          <label className="field"><span>{t('Meeting')}</span>
            <select value={kind} onChange={(e) => setKind(e.target.value as MeetingKind)}>
              {KINDS.map((k) => <option key={k.value} value={k.value}>{t(k.label)}</option>)}
            </select></label>
          <label className="field"><span>{t('Date')}</span><input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></label>
          {sprints && sprints.length > 0 && (
            <label className="field"><span>{t('Sprint')}</span>
              <select value={sprint ?? ''} onChange={(e) => setSprint(e.target.value ? Number(e.target.value) : null)}>
                <option value="">{t('None')}</option>
                {sprints.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select></label>
          )}
        </div>
        <label className="field"><span>{t('Title')}</span>
          <input value={title} maxLength={120} placeholder={`${t(KIND_LABEL[kind])} ${date}`} onChange={(e) => setTitle(e.target.value)} /></label>
        <div className="field"><span>{t('Notes')}</span>
          <MarkdownEditor value={body} onChange={setBody} rows={10} maxLength={20000} label={t('Notes')}
            placeholder={t('What was discussed and agreed. “- [ ] Do something @username” lines become action items.')} /></div>
      </form>
    </Modal>
  );
}
