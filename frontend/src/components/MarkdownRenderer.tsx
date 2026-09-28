import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Renders user-written Markdown (GitHub flavour). Raw HTML is not rendered,
 * so user content cannot inject markup or scripts.
 */
export default function MarkdownRenderer({ children, className = '' }: { children: string; className?: string }) {
  return (
    <div className={`markdown ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children: text }) => (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">{text}</a>
          ),
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
