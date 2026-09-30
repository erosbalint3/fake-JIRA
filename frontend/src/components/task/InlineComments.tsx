import { useEffect, useRef, useState, type ReactNode } from 'react';
import { MessageSquarePlus } from 'lucide-react';
import type { Comment } from '../../types';
import { t } from '../../i18n';

const HIGHLIGHT = 'inline-anchor';

/** Finds {@code quote} in {@code text} treating any run of whitespace as one space (quotes are stored collapsed). */
function findCollapsed(text: string, quote: string) {
  let collapsed = '';
  const offsets: number[] = [];
  for (let i = 0; i < text.length; i++) {
    const space = /\s/.test(text[i]);
    if (space && collapsed.endsWith(' ')) continue;
    collapsed += space ? ' ' : text[i];
    offsets.push(i);
  }
  const at = collapsed.indexOf(quote);
  if (at < 0) return null;
  return { start: offsets[at], end: offsets[at + quote.length - 1] + 1 };
}

/**
 * Wraps the rendered description: selecting text offers "Comment", and passages that inline comments refer
 * to are highlighted with the CSS Custom Highlight API (no changes to the rendered DOM, which React owns).
 * Clicking highlighted text jumps to its comment.
 */
export function InlineComments({ children, comments, onComment, canComment }: {
  children: ReactNode;
  comments: Comment[];
  onComment: (quote: string) => void;
  canComment: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const ranges = useRef<{ range: Range; commentId: number }[]>([]);
  const [offer, setOffer] = useState<{ quote: string; x: number; y: number } | null>(null);
  const anchorKey = comments.filter((c) => c.anchor && !c.parentId).map((c) => `${c.id}:${c.anchor}`).join('|');

  useEffect(() => {
    const root = ref.current;
    const registry = (globalThis as { CSS?: { highlights?: Map<string, unknown> } }).CSS?.highlights;
    const HighlightCtor = (globalThis as { Highlight?: new (...r: Range[]) => unknown }).Highlight;
    if (!root) return;
    const anchored = comments.filter((c) => c.anchor && !c.parentId);
    const compute = () => {
      const found: { range: Range; commentId: number }[] = [];
      for (const comment of anchored) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
          // Quotes spanning several text nodes (e.g. across bold text) are shown on the comment only.
          const match = findCollapsed(node.data, comment.anchor!);
          if (!match) continue;
          const range = document.createRange();
          range.setStart(node, match.start);
          range.setEnd(node, match.end);
          found.push({ range, commentId: comment.id });
          break;
        }
      }
      ranges.current = found;
      if (registry && HighlightCtor) {
        if (found.length) registry.set(HIGHLIGHT, new HighlightCtor(...found.map((f) => f.range)));
        else registry.delete(HIGHLIGHT);
      }
    };
    compute();
    // The Markdown renders lazily and re-renders on edits: recompute when the text changes.
    const observer = new MutationObserver(compute);
    observer.observe(root, { childList: true, subtree: true, characterData: true });
    return () => {
      observer.disconnect();
      registry?.delete(HIGHLIGHT);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [anchorKey]);

  useEffect(() => {
    const hide = () => setOffer(null);
    document.addEventListener('scroll', hide, true);
    return () => document.removeEventListener('scroll', hide, true);
  }, []);

  return (
    <div className={`inline-comments ${anchorKey ? 'has-anchors' : ''}`} ref={ref}
      onClick={(e) => {
        const hit = ranges.current.find(({ range }) => Array.from(range.getClientRects())
          .some((r) => e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom));
        if (hit) document.getElementById(`comment-${hit.commentId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }}
      onMouseUp={() => {
        if (!canComment) return;
        const selection = window.getSelection();
        const quote = selection?.toString().replace(/\s+/g, ' ').trim() ?? '';
        if (!selection || selection.rangeCount === 0 || quote.length < 3 || !ref.current?.contains(selection.anchorNode)) {
          setOffer(null);
          return;
        }
        const rect = selection.getRangeAt(0).getBoundingClientRect();
        const box = ref.current.getBoundingClientRect();
        setOffer({ quote: quote.slice(0, 300), x: rect.left - box.left + rect.width / 2, y: rect.top - box.top });
      }}>
      {children}
      {offer && (
        <button className="btn btn-primary btn-sm inline-comment-button" style={{ left: offer.x, top: offer.y }}
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            onComment(offer.quote);
            setOffer(null);
            window.getSelection()?.removeAllRanges();
          }}>
          <MessageSquarePlus size={14} /> {t('Comment')}
        </button>
      )}
    </div>
  );
}
