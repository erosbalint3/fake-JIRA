import type { ReactNode } from 'react';
import { t } from '../i18n';

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="spinner-wrap" role="status">
      <span className="spinner" aria-hidden />
      <span className="muted">{t(label)}</span>
    </div>
  );
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="alert">
      <span>{message}</span>
      {onRetry && <button className="btn btn-ghost btn-sm" onClick={onRetry}>{t("Retry")}</button>}
    </div>
  );
}
