import { useEffect, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { t } from '../i18n';

interface ModalProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  /** False for dialogs that must be completed (no close button, Esc or backdrop click). */
  dismissible?: boolean;
}

export function Modal({ title, onClose, children, footer, wide, dismissible = true }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = dismissible ? onClose : () => {};

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  // Focus the first field once, when the dialog opens.
  useEffect(() => {
    dialogRef.current?.querySelector<HTMLElement>('input, textarea, select, button.btn')?.focus();
  }, []);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && closeRef.current()}>
      <div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={dialogRef}>
        <header className="modal-header">
          <h2>{title}</h2>
          {dismissible && (
            <button className="icon-button" onClick={onClose} aria-label={t("Close")}>
              <X size={18} />
            </button>
          )}
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>
  );
}

interface ConfirmProps {
  title: string;
  message: string;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  children?: ReactNode;
}

export function ConfirmDialog({ title, message, confirmLabel, danger, busy, onConfirm, onClose, children }: ConfirmProps) {
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
          <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </button>
        </>
      }
    >
      <p className="muted">{message}</p>
      {children && <div className="confirm-extra">{children}</div>}
    </Modal>
  );
}
