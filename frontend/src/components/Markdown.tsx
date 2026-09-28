import { lazy, Suspense } from 'react';

// The Markdown parser is the largest dependency, so it loads on first use.
const MarkdownRenderer = lazy(() => import('./MarkdownRenderer'));

export function Markdown({ children, className = '' }: { children: string; className?: string }) {
  return (
    <Suspense fallback={<div className={`markdown ${className}`} style={{ whiteSpace: 'pre-wrap' }}>{children}</div>}>
      <MarkdownRenderer className={className}>{children}</MarkdownRenderer>
    </Suspense>
  );
}
