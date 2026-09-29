import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, ClipboardCopy, FileText, Package, PackageCheck, Pencil, Plus, Rocket, Trash2, Undo2 } from 'lucide-react';
import { api, ApiError, type ReleaseInput } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Avatar } from '../components/Avatar';
import { PointsBadge, StatusBadge, TypeIcon } from '../components/Badges';
import { ConfirmDialog, Modal } from '../components/Modal';
import { Markdown } from '../components/Markdown';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDay, todayIso } from '../format';
import type { Release, Task } from '../types';

export function ReleasesPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const toast = useToast();
  const [releases, setReleases] = useState<Release[] | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<Release | 'new' | null>(null);
  const [shipping, setShipping] = useState<Release | null>(null);
  const [deleting, setDeleting] = useState<Release | null>(null);
  const [notes, setNotes] = useState<{ release: Release; markdown: string } | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.releases(key).then(setReleases).catch((e: ApiError) => setError(e.message));
  }, [key, project]);
  useEffect(load, [load]);
  useLiveRefresh((m) => (m.type === 'task' || m.type === 'project') && m.data.projectId === project?.id, load, 600);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const run = async (action: () => Promise<unknown>, message: string) => {
    try {
      await action();
      toast(message);
      load();
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  const upcoming = (releases ?? []).filter((r) => !r.released);
  const shipped = (releases ?? []).filter((r) => r.released).reverse();

  const card = (release: Release) => {
    const percent = release.taskCount ? Math.round((release.doneCount / release.taskCount) * 100) : 0;
    const late = !release.released && release.releaseDate && release.releaseDate < todayIso();
    return (
      <li key={release.id} className={`release ${release.released ? 'is-released' : ''}`}>
        <div className="release-main">
          <button className="release-toggle" aria-expanded={open === release.id}
            onClick={() => setOpen(open === release.id ? null : release.id)}>
            <ChevronDown size={16} className={open === release.id ? '' : 'rot'} />
            {release.released ? <PackageCheck size={18} /> : <Package size={18} />}
            <strong>{release.name}</strong>
          </button>
          <span className={`muted small ${late ? 'overdue-text' : ''}`}>
            {release.released
              ? `Released ${formatDay((release.releasedAt ?? '').slice(0, 10) || release.releaseDate || '')}`
              : release.releaseDate ? `${late ? 'Was due' : 'Due'} ${formatDay(release.releaseDate)}` : 'No date'}
          </span>
          <span className="release-progress" title={`${release.doneCount} of ${release.taskCount} tasks done`}>
            <span style={{ width: `${percent}%` }} />
          </span>
          <span className="muted small">{release.doneCount}/{release.taskCount} done{release.points ? ` · ${release.donePoints}/${release.points} pts` : ''}</span>
          <span className="spacer" />
          <button className="btn btn-ghost btn-sm" onClick={() => api.releaseNotes(release.id).then(setNotes)
            .catch((e: ApiError) => toast(e.message, 'error'))}><FileText size={15} /> Notes</button>
          {canEdit && !release.released && (
            <button className="btn btn-soft btn-sm" onClick={() => setShipping(release)}><Rocket size={15} /> Release</button>
          )}
          {canEdit && release.released && (
            <button className="icon-button" aria-label={`Mark ${release.name} unreleased`} title="Mark unreleased"
              onClick={() => run(() => api.unshipRelease(release.id), `${release.name} marked unreleased`)}><Undo2 size={16} /></button>
          )}
          {canEdit && (
            <>
              <button className="icon-button" aria-label={`Edit ${release.name}`} onClick={() => setEditing(release)}><Pencil size={16} /></button>
              <button className="icon-button" aria-label={`Delete ${release.name}`} onClick={() => setDeleting(release)}><Trash2 size={16} /></button>
            </>
          )}
        </div>
        {release.description && <p className="muted release-description">{release.description}</p>}
        {open === release.id && <ReleaseTasks id={release.id} />}
      </li>
    );
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>Releases</h1>
          <p className="muted">Group work into versions, ship them and share release notes. Set a task's release on its page.</p>
        </div>
        {canEdit && (
          <div className="header-actions">
            <button className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={18} /> New release</button>
          </div>
        )}
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!releases && !error && <Spinner />}
      {releases && releases.length === 0 && (
        <EmptyState icon={<Package size={28} />} title="No releases yet">
          {canEdit ? <button className="link" onClick={() => setEditing('new')}>Create the first release</button>
            : 'The project owner has not planned any releases.'}
        </EmptyState>
      )}
      {upcoming.length > 0 && (
        <section>
          <h2 className="section-title">Unreleased</h2>
          <ul className="release-list">{upcoming.map(card)}</ul>
        </section>
      )}
      {shipped.length > 0 && (
        <section>
          <h2 className="section-title">Released</h2>
          <ul className="release-list">{shipped.map(card)}</ul>
        </section>
      )}

      {editing && (
        <ReleaseModal projectKey={key} release={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
          onSaved={(name) => {
            setEditing(null);
            toast(editing === 'new' ? `${name} created` : `${name} saved`);
            load();
          }} />
      )}
      {shipping && (
        <ShipModal release={shipping} others={upcoming.filter((r) => r.id !== shipping.id)} onClose={() => setShipping(null)}
          onShip={(target) => {
            const r = shipping;
            setShipping(null);
            run(() => api.shipRelease(r.id, target), `${r.name} released 🚀`);
          }} />
      )}
      {deleting && (
        <ConfirmDialog title={`Delete ${deleting.name}?`} message="Its tasks stay; they just no longer belong to a release."
          confirmLabel="Delete release" danger onClose={() => setDeleting(null)}
          onConfirm={() => {
            const r = deleting;
            setDeleting(null);
            run(() => api.deleteRelease(r.id), `${r.name} deleted`);
          }} />
      )}
      {notes && <NotesModal notes={notes} onClose={() => setNotes(null)} />}
    </div>
  );
}

function ReleaseTasks({ id }: { id: number }) {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  useEffect(() => {
    api.releaseTasks(id).then(setTasks).catch(() => setTasks([]));
  }, [id]);
  if (!tasks) return <Spinner />;
  if (tasks.length === 0) return <p className="muted release-tasks">No tasks yet. Open a task and pick this release.</p>;
  return (
    <ul className="review-list release-tasks">
      {tasks.map((t) => (
        <li key={t.id}>
          <TypeIcon type={t.type} />
          <Link to={`/tasks/${t.id}`}><span className="task-key">{t.key}</span> {t.title}</Link>
          <span className="spacer" />
          <StatusBadge status={t.status} />
          <PointsBadge points={t.storyPoints} />
          {t.assignee && <Avatar user={t.assignee} size={20} />}
        </li>
      ))}
    </ul>
  );
}

function ReleaseModal({ projectKey, release, onClose, onSaved }: {
  projectKey: string; release: Release | null; onClose: () => void; onSaved: (name: string) => void;
}) {
  const [name, setName] = useState(release?.name ?? '');
  const [description, setDescription] = useState(release?.description ?? '');
  const [releaseDate, setReleaseDate] = useState(release?.releaseDate ?? '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    const input: ReleaseInput = { name: name.trim(), description, releaseDate: releaseDate || null };
    try {
      if (release) await api.updateRelease(release.id, input);
      else await api.createRelease(projectKey, input);
      onSaved(input.name);
    } catch (e) {
      setError((e as ApiError).message);
      setBusy(false);
    }
  };
  return (
    <Modal title={release ? `Edit ${release.name}` : 'New release'} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" form="release-form" disabled={busy || !name.trim()}>{release ? 'Save' : 'Create'}</button>
      </>
    }>
      <form id="release-form" className="form" onSubmit={submit}>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>Version</span>
          <input value={name} maxLength={40} placeholder="1.4.0" autoFocus onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">
          <span>Release date <span className="muted">(optional)</span></span>
          <input type="date" value={releaseDate} onChange={(e) => setReleaseDate(e.target.value)} />
        </label>
        <label className="field">
          <span>Description <span className="muted">(shown in the release notes)</span></span>
          <textarea rows={3} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}

function ShipModal({ release, others, onClose, onShip }: {
  release: Release; others: Release[]; onClose: () => void; onShip: (moveTo: number | null) => void;
}) {
  const open = release.taskCount - release.doneCount;
  const [target, setTarget] = useState<string>(others[0] ? String(others[0].id) : '');
  return (
    <Modal title={`Release ${release.name}`} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => onShip(open && target ? Number(target) : null)}>
          <Rocket size={16} /> Release
        </button>
      </>
    }>
      {open === 0 ? <p>All {release.taskCount} tasks are done. Ready to ship!</p> : (
        <>
          <p>{open} task{open === 1 ? ' is' : 's are'} not done yet.</p>
          <label className="field">
            <span>Move unfinished tasks to</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)}>
              {others.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
              <option value="">No release</option>
            </select>
          </label>
        </>
      )}
    </Modal>
  );
}

function NotesModal({ notes, onClose }: { notes: { release: Release; markdown: string }; onClose: () => void }) {
  const toast = useToast();
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(notes.markdown);
      toast('Release notes copied');
    } catch {
      toast('Could not copy — your browser blocked the clipboard', 'error');
    }
  };
  return (
    <Modal title={`Release notes · ${notes.release.name}`} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>Close</button>
        <button className="btn btn-primary" onClick={copy}><ClipboardCopy size={16} /> Copy Markdown</button>
      </>
    }>
      <div className="release-notes"><Markdown>{notes.markdown}</Markdown></div>
    </Modal>
  );
}
