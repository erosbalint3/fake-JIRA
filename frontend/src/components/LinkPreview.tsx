import { useEffect, useState } from 'react';
import { ExternalLink, Play } from 'lucide-react';
import { api } from '../api';
import type { LinkPreviewData } from '../types';
import { t } from '../i18n';

const cache = new Map<string, Promise<LinkPreviewData | null>>();

function load(url: string) {
  let promise = cache.get(url);
  if (!promise) {
    promise = api.linkPreview(url).catch(() => null);
    cache.set(url, promise);
  }
  return promise;
}

const EMBED_LABEL: Record<string, string> = {
  figma: 'Figma', youtube: 'YouTube', 'google-document': 'Google Docs', 'google-spreadsheets': 'Google Sheets',
  'google-presentation': 'Google Slides',
};

/** A card for a link on its own line: title, description and image, and an inline player for known services. */
export function LinkPreview({ url, children }: { url: string; children: React.ReactNode }) {
  const [data, setData] = useState<LinkPreviewData | null | undefined>(undefined);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let active = true;
    load(url).then((d) => active && setData(d));
    return () => {
      active = false;
    };
  }, [url]);

  // Until it loads (or if nothing useful came back) it is a plain link.
  if (!data || (!data.title && !data.embed)) {
    return <p><a href={url} target="_blank" rel="noopener noreferrer nofollow">{children}</a></p>;
  }
  const label = EMBED_LABEL[data.kind];
  return (
    <div className="link-card">
      <a className="link-card-body" href={url} target="_blank" rel="noopener noreferrer nofollow">
        {data.image && <img src={data.image} alt="" loading="lazy" referrerPolicy="no-referrer" />}
        <span className="link-card-text">
          <span className="muted small">{label ?? data.siteName}</span>
          <strong>{data.title ?? url}</strong>
          {data.description && <span className="small">{data.description}</span>}
        </span>
        <ExternalLink size={14} aria-hidden className="link-card-icon" />
      </a>
      {data.embed && (
        open ? (
          <iframe className={`link-embed embed-${data.kind}`} src={data.embed} title={data.title ?? label ?? url} loading="lazy"
            allow="fullscreen; clipboard-write; encrypted-media; picture-in-picture" allowFullScreen
            sandbox="allow-scripts allow-same-origin allow-popups allow-presentation allow-forms" referrerPolicy="no-referrer" />
        ) : (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(true)}>
            <Play size={14} /> {t('Show {what} here', { what: label ?? t('preview') })}
          </button>
        )
      )}
    </div>
  );
}
