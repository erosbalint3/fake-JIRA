import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Images, X } from 'lucide-react';
import { api, ApiError } from '../api';
import { useLiveRefresh } from '../live';
import { useRouteProject } from '../useProject';
import { EmptyState, ErrorBanner, Spinner } from '../components/States';
import { NotFoundPage } from './NotFoundPage';
import type { GalleryImage } from '../types';
import { fileSize, formatDate } from '../format';
import { t } from '../i18n';

const urls = new Map<number, Promise<string>>();
function imageUrl(id: number) {
  let url = urls.get(id);
  if (!url) {
    url = api.attachmentBlob(id).then((blob) => URL.createObjectURL(blob));
    urls.set(id, url);
  }
  return url;
}

function Thumb({ image, onOpen }: { image: GalleryImage; onOpen: () => void }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    imageUrl(image.id).then((u) => alive && setSrc(u)).catch(() => {});
    return () => {
      alive = false;
    };
  }, [image.id]);
  return (
    <figure className="gallery-item">
      <button className="gallery-thumb" onClick={onOpen} aria-label={t('Open {name}', { name: image.filename })}>
        {src ? <img src={src} alt="" loading="lazy" /> : <span className="gallery-placeholder" aria-hidden />}
      </button>
      <figcaption>
        <span className="gallery-name" title={image.filename}>{image.filename}</span>
        <Link to={`/tasks/${image.task.id}`} className="small">{image.task.key}</Link>
      </figcaption>
    </figure>
  );
}

/** Every picture attached in the project: screenshots, mockups and photos, newest first. */
export function GalleryPage() {
  const { project, loading } = useRouteProject();
  const [images, setImages] = useState<GalleryImage[] | null>(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState<number | null>(null);
  const [src, setSrc] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!project) return;
    api.gallery(project.key).then(setImages).catch((e: ApiError) => setError(e.message));
  }, [project]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.projectId === project?.id, load, 1500);

  const current = open === null || !images ? null : images[open];
  useEffect(() => {
    setSrc(null);
    if (current) imageUrl(current.id).then(setSrc).catch(() => {});
  }, [current]);
  useEffect(() => {
    if (open === null || !images) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(null);
      if (e.key === 'ArrowRight') setOpen((i) => (i === null ? i : Math.min(images.length - 1, i + 1)));
      if (e.key === 'ArrowLeft') setOpen((i) => (i === null ? i : Math.max(0, i - 1)));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, images]);

  if (loading) return <div className="page"><Spinner /></div>;
  if (!project) return <NotFoundPage />;
  return (
    <div className="page page-wide">
      <header className="page-header">
        <div>
          <span className="eyebrow">{project.name}</span>
          <h1><Images size={22} aria-hidden /> {t('Gallery')}</h1>
          <p className="muted small">{t('Pictures attached to tasks in this project. Drop images on a task to add more.')}</p>
        </div>
      </header>
      {error && <ErrorBanner message={error} onRetry={load} />}
      {!images ? <Spinner /> : images.length === 0 ? (
        <EmptyState icon={<Images size={28} />} title={t('No pictures yet')}>
          <p className="muted">{t('Screenshots and mockups attached to tasks show up here.')}</p>
        </EmptyState>
      ) : (
        <div className="gallery-grid">
          {images.map((image, i) => <Thumb key={image.id} image={image} onOpen={() => setOpen(i)} />)}
        </div>
      )}
      {current && (
        <div className="lightbox" role="dialog" aria-modal="true" aria-label={current.filename} onClick={() => setOpen(null)}>
          <div className="lightbox-inner" onClick={(e) => e.stopPropagation()}>
            <div className="lightbox-bar">
              <strong>{current.filename}</strong>
              <span className="muted small">{fileSize(current.size)} · {formatDate(current.createdAt)} · {current.uploader.displayName}</span>
              <Link to={`/tasks/${current.task.id}`}>{current.task.key} {current.task.title}</Link>
              <span className="spacer" />
              <button className="icon-button" aria-label={t('Previous')} disabled={open === 0} onClick={() => setOpen((open ?? 1) - 1)}><ChevronLeft size={18} /></button>
              <button className="icon-button" aria-label={t('Next')} disabled={open === images!.length - 1} onClick={() => setOpen((open ?? 0) + 1)}><ChevronRight size={18} /></button>
              <button className="icon-button" aria-label={t('Close')} autoFocus onClick={() => setOpen(null)}><X size={18} /></button>
            </div>
            {src ? <img src={src} alt={current.filename} className="lightbox-image" /> : <Spinner />}
          </div>
        </div>
      )}
    </div>
  );
}
