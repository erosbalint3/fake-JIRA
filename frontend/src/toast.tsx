import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import { CheckCircle2, AlertCircle, X } from 'lucide-react';

type ToastKind = 'success' | 'error';

export interface ToastOptions {
  /** A button in the toast, e.g. Undo. */
  action?: { label: string; onClick: () => void };
  /** How long the toast stays, in ms (default 4s, 7s with an action). */
  duration?: number;
}

interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
  action?: ToastOptions['action'];
}

type Show = (message: string, kind?: ToastKind, options?: ToastOptions) => void;

const ToastContext = createContext<Show>(() => {});

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const show = useCallback<Show>((message, kind = 'success', options = {}) => {
    const id = nextId++;
    setToasts((current) => [...current.slice(-3), { id, kind, message, action: options.action }]);
    window.setTimeout(() => dismiss(id), options.duration ?? (options.action ? 7000 : 4000));
  }, [dismiss]);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.kind}`}>
            {toast.kind === 'success' ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
            <span>{toast.message}</span>
            {toast.action && (
              <button className="toast-action" onClick={() => {
                dismiss(toast.id);
                toast.action!.onClick();
              }}>{toast.action.label}</button>
            )}
            <button className="icon-button" onClick={() => dismiss(toast.id)} aria-label="Dismiss">
              <X size={16} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);
