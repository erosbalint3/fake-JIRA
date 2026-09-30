import { useEffect, useState } from 'react';
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api } from '../api';
import { t } from '../i18n';

/** Object URLs of attachment images already loaded in this tab. */
const loaded = new Map<number, Promise<string>>();

/** An image stored as a task attachment; fetched with the sign-in token since it is not public. */
function AttachmentImage({ id, alt }: { id: number; alt: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let promise = loaded.get(id);
    if (!promise) {
      promise = api.attachmentBlob(id).then((blob) => URL.createObjectURL(blob));
      loaded.set(id, promise);
      promise.catch(() => loaded.delete(id));
    }
    let active = true;
    promise.then((url) => active && setSrc(url)).catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [id]);

  if (failed) return <span className="muted small">[image unavailable: {alt || `attachment ${id}`}]</span>;
  if (!src) return <span className="img-loading" aria-label={`Loading ${alt}`} />;
  return (
    <a href={src} target="_blank" rel="noopener noreferrer">
      <img src={src} alt={alt} loading="lazy" />
    </a>
  );
}

/**
 * Renders user-written Markdown (GitHub flavour). Raw HTML is not rendered,
 * so user content cannot inject markup or scripts.
 */
export default function MarkdownRenderer({ children, className = '' }: { children: string; className?: string }) {
  return (
    <div className={`markdown ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        // attachment:<id> points at an uploaded image; everything else goes through the safe default.
        urlTransform={(url) => (/^attachment:\d+$/.test(url) ? url : defaultUrlTransform(url))}
        components={{
          a: ({ href, children: text }) => (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">{text}</a>
          ),
          // GitHub task-list items: read-only checkboxes need a name for screen readers.
          input: ({ type, checked }) => (type === 'checkbox'
            ? <input type="checkbox" checked={!!checked} disabled aria-label={checked ? t('Done') : t('Not done')} />
            : null),
          img: ({ src, alt }) => {
            const match = typeof src === 'string' ? /^attachment:(\d+)$/.exec(src) : null;
            if (match) return <AttachmentImage id={Number(match[1])} alt={alt ?? ''} />;
            return src ? <img src={String(src)} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" /> : null;
          },
        }}
      >
        {highlightMentions(children)}
      </ReactMarkdown>
    </div>
  );
}

/** Turns @username into bold text outside code, so mentions stand out. */
function highlightMentions(text: string) {
  return text
    .split(/(```[\s\S]*?```|`[^`]*`)/g)
    .map((part, index) => (index % 2 === 1 ? part : part.replace(/(^|[^\w.`*])@([A-Za-z0-9._-]{2,40})/g, '$1**@$2**')))
    .join('');
}
