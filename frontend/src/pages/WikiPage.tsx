import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { BookOpen, FilePlus2, History, Pencil, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useToast } from '../toast';
import { useRouteProject } from '../useProject';
import { Markdown } from '../components/Markdown';
import { MarkdownEditor } from '../components/MarkdownEditor';
import { ConfirmDialog, Modal } from '../components/Modal';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import { formatDate, timeAgo } from '../format';
import type { WikiPage as Page, WikiPageSummary, WikiRevision } from '../types';
import { t } from '../i18n';

interface TreeNode extends WikiPageSummary {
  children: TreeNode[];
}

function buildTree(pages: WikiPageSummary[]): TreeNode[] {
  const byId = new Map<number, TreeNode>(pages.map((p) => [p.id, { ...p, children: [] }]));
  const roots: TreeNode[] = [];
  byId.forEach((node) => {
    const parent = node.parentId ? byId.get(node.parentId) : undefined;
    (parent ? parent.children : roots).push(node);
  });
  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => a.title.localeCompare(b.title));
    list.forEach((n) => sort(n.children));
  };
  sort(roots);
  return roots;
}

/** Project wiki: a page tree on the left, the page (or its editor) on the right. */
export function WikiPage() {
  const { key, project, loading, canEdit } = useRouteProject();
  const { slug } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const [pages, setPages] = useState<WikiPageSummary[] | null>(null);
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<'new' | 'edit' | null>(null);
  const [history, setHistory] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const loadPages = useCallback(() => {
    if (!project) return;
    api.wikiPages(key).then(setPages).catch((e: ApiError) => setError(e.message));
  }, [key, project]);
  const loadPage = useCallback(() => {
    if (!project || !slug) {
      setPage(null);
      return;
    }
    api.wikiPage(key, slug).then((p) => {
      setPage(p);
      setError('');
    }).catch((e: ApiError) => setError(e.status === 404 ? t('That page does not exist.') : e.message));
  }, [key, project, slug]);
  useEffect(loadPages, [loadPages]);
  useEffect(loadPage, [loadPage]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === project?.id && !editing, () => {
    loadPages();
    loadPage();
  }, 800);

  // Without a page in the URL, open the first top-level page.
  useEffect(() => {
    if (!slug && pages && pages.length > 0) {
      const first = buildTree(pages)[0];
      navigate(`/p/${key}/wiki/${first.slug}`, { replace: true });
    }
  }, [slug, pages, key, navigate]);

  const tree = useMemo(() => buildTree(pages ?? []), [pages]);

  if (!loading && !project) return <NotFoundPage />;
  if (!project) return <div className="page"><Spinner /></div>;

  const renderTree = (nodes: TreeNode[]) => (
    <ul>
      {nodes.map((n) => (
        <li key={n.id}>
          <Link to={`/p/${key}/wiki/${n.slug}`} className={`wiki-link ${n.slug === slug ? 'active' : ''}`}
            aria-current={n.slug === slug ? 'page' : undefined}>{n.title}</Link>
          {n.children.length > 0 && renderTree(n.children)}
        </li>
      ))}
    </ul>
  );

  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1>{t('Wiki')}</h1>
          <p className="muted">{t('Docs for the project. Mention task keys like {key}-12 and the task lists the page.', { key })}</p>
        </div>
        {canEdit && (
          <div className="header-actions">
            <button className="btn btn-primary" onClick={() => setEditing('new')}><FilePlus2 size={16} /> {t('New page')}</button>
          </div>
        )}
      </header>
      {error && <ErrorBanner message={error} onRetry={() => { loadPages(); loadPage(); }} />}
      {pages && pages.length === 0 && !editing && (
        <EmptyState icon={<BookOpen size={28} />} title={t('No pages yet')}>
          {canEdit ? <button className="link" onClick={() => setEditing('new')}>{t('Write the first page')}</button> : null}
        </EmptyState>
      )}
      {pages && pages.length > 0 && (
        <div className="wiki">
          <nav className="wiki-tree panel" aria-label={t('Pages')}>{renderTree(tree)}</nav>
          <article className="wiki-page panel">
            {!page && !error && <Spinner />}
            {page && (
              <>
                {page.path.length > 0 && (
                  <div className="breadcrumb muted small">
                    {page.path.map((p) => <span key={p.id}><Link to={`/p/${key}/wiki/${p.slug}`}>{p.title}</Link> / </span>)}
                  </div>
                )}
                <header className="wiki-head">
                  <h2>{page.title}</h2>
                  <div className="header-actions">
                    <button className="btn btn-ghost btn-sm" onClick={() => setHistory(true)}><History size={15} /> {t('History')}</button>
                    {page.canEdit && <button className="btn btn-soft btn-sm" onClick={() => setEditing('edit')}><Pencil size={15} /> {t('Edit')}</button>}
                    {page.canEdit && (
                      <button className="icon-button" aria-label={t('Delete page')} onClick={() => setDeleting(true)}><Trash2 size={16} /></button>
                    )}
                  </div>
                </header>
                <p className="muted small">{t('Version {n} · edited {when} by {name}', {
                  n: page.version, when: timeAgo(page.updatedAt), name: page.updatedBy.displayName })}</p>
                {page.body ? <Markdown>{page.body}</Markdown> : <p className="muted">{t('This page is empty.')}</p>}
                {page.children.length > 0 && (
                  <section className="wiki-children">
                    <h3>{t('Pages inside')}</h3>
                    <ul>{page.children.map((c) => <li key={c.id}><Link to={`/p/${key}/wiki/${c.slug}`}>{c.title}</Link></li>)}</ul>
                  </section>
                )}
              </>
            )}
          </article>
        </div>
      )}
      {editing && (
        <PageEditor projectKey={key} page={editing === 'edit' ? page : null} pages={pages ?? []}
          defaultParent={editing === 'new' ? page?.id ?? null : null}
          onClose={() => setEditing(null)} onSaved={(saved) => {
            setEditing(null);
            loadPages();
            setPage(saved);
            navigate(`/p/${key}/wiki/${saved.slug}`);
          }} />
      )}
      {history && page && <HistoryModal page={page} canEdit={page.canEdit} onClose={() => setHistory(false)}
        onRestored={(restored) => {
          setHistory(false);
          setPage(restored);
          toast(t('Restored version as {n}', { n: restored.version }));
        }} />}
      {deleting && page && (
        <ConfirmDialog title={t('Delete {name}?', { name: page.title })} danger confirmLabel={t('Delete')}
          message={t('The page and its history are deleted. Pages inside it move up a level.')}
          onClose={() => setDeleting(false)} onConfirm={async () => {
            await api.deleteWikiPage(page.id).catch((e: ApiError) => toast(e.message, 'error'));
            setDeleting(false);
            setPage(null);
            loadPages();
            navigate(`/p/${key}/wiki`);
          }} />
      )}
    </div>
  );
}

function PageEditor({ projectKey, page, pages, defaultParent, onClose, onSaved }: {
  projectKey: string;
  page: Page | null;
  pages: WikiPageSummary[];
  defaultParent: number | null;
  onClose: () => void;
  onSaved: (page: Page) => void;
}) {
  const [title, setTitle] = useState(page?.title ?? '');
  const [body, setBody] = useState(page?.body ?? '');
  const [parent, setParent] = useState<number | null>(page ? page.parentId : defaultParent);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      onSaved(page
        ? await api.updateWikiPage(page.id, { title: title.trim(), body, parentId: parent, baseVersion: page.version })
        : await api.createWikiPage(projectKey, { title: title.trim(), body, parentId: parent }));
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal wide title={page ? t('Edit {name}', { name: page.title }) : t('New page')} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t('Cancel')}</button>
        <button className="btn btn-primary" form="wiki-form" disabled={busy || !title.trim()}>{t('Save page')}</button>
      </>
    }>
      <form id="wiki-form" className="form" onSubmit={submit}>
        {error && <div className="alert" role="alert">{error}</div>}
        <label className="field"><span>{t('Title')}</span>
          <input value={title} maxLength={120} autoFocus onChange={(e) => setTitle(e.target.value)} /></label>
        <label className="field"><span>{t('Inside')}</span>
          <select value={parent ?? ''} onChange={(e) => setParent(e.target.value ? Number(e.target.value) : null)}>
            <option value="">{t('Top level')}</option>
            {pages.filter((p) => p.id !== page?.id).map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
          </select></label>
        <div className="field">
          <span>{t('Content')}</span>
          <MarkdownEditor value={body} onChange={setBody} rows={16} maxLength={100000} label={t('Content')}
            placeholder={t('Write in Markdown. Link tasks by key, e.g. {key}-12.', { key: projectKey })} />
        </div>
      </form>
    </Modal>
  );
}

function HistoryModal({ page, canEdit, onClose, onRestored }: {
  page: Page;
  canEdit: boolean;
  onClose: () => void;
  onRestored: (page: Page) => void;
}) {
  const toast = useToast();
  const [revisions, setRevisions] = useState<WikiRevision[] | null>(null);
  const [open, setOpen] = useState<WikiRevision | null>(null);
  useEffect(() => {
    api.wikiHistory(page.id).then(setRevisions).catch(() => setRevisions([]));
  }, [page.id]);
  return (
    <Modal wide title={t('History of {name}', { name: page.title })} onClose={onClose}>
      {!revisions ? <Spinner /> : (
        <div className="wiki-history">
          <ul className="mini-list">
            {revisions.map((r) => (
              <li key={r.version}>
                <button className={`link ${open?.version === r.version ? 'active' : ''}`}
                  onClick={() => api.wikiRevision(page.id, r.version).then(setOpen).catch(() => {})}>
                  {t('Version {n}', { n: r.version })}</button>
                <span className="muted small"> {r.author.displayName} · {formatDate(r.createdAt)}</span>
              </li>
            ))}
          </ul>
          {open && (
            <div className="wiki-revision">
              <div className="panel-head">
                <strong>{t('Version {n}', { n: open.version })}: {open.title}</strong>
                {canEdit && open.version !== page.version && (
                  <button className="btn btn-soft btn-sm" onClick={async () => {
                    try {
                      onRestored(await api.restoreWikiRevision(page.id, open.version));
                    } catch (e) {
                      toast((e as ApiError).message, 'error');
                    }
                  }}>{t('Restore this version')}</button>
                )}
              </div>
              <Markdown>{open.body ?? ''}</Markdown>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
