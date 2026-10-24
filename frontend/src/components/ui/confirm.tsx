import React, { createContext, useContext, useState, useId } from 'react';
import { useDialog } from './use-dialog';
import { useTranslation } from '../../lib/i18n';

interface ConfirmOptions {
  title: string;
  message: string;
  destructive?: boolean;
  confirmText?: string;
  cancelText?: string;
}

interface ConfirmContextType {
  confirm: (options: ConfirmOptions) => Promise<boolean>;
}

const ConfirmContext = createContext<ConfirmContextType | undefined>(undefined);

export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [options, setOptions] = useState<ConfirmOptions | null>(null);
  const [resolveFn, setResolveFn] = useState<((value: boolean) => void) | null>(null);

  const { t } = useTranslation();

  const confirm = (opts: ConfirmOptions): Promise<boolean> => {
    setOptions(opts);
    setIsOpen(true);
    return new Promise((resolve) => {
      setResolveFn(() => resolve);
    });
  };

  const handleClose = (value: boolean) => {
    setIsOpen(false);
    if (resolveFn) resolveFn(value);
  };
  const titleId = useId();
  const messageId = useId();
  const dialogRef = useDialog(isOpen, () => handleClose(false));

  return (
    <ConfirmContext.Provider value={{ confirm }}>
      {children}
      {isOpen && options && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-bg/80 backdrop-blur-sm" onClick={() => handleClose(false)} />
          <div ref={dialogRef} role="alertdialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={messageId} tabIndex={-1} className="relative bg-surface border border-border rounded-lg max-w-sm w-full p-6 max-h-[90svh] overflow-y-auto break-words animate-in zoom-in-95 duration-200">
            <h2 id={titleId} className="text-lg font-semibold text-foreground mb-2">{options.title}</h2>
            <p id={messageId} className="text-sm text-text-muted mb-6">{options.message}</p>
            <div className="flex flex-wrap justify-end gap-3">
              <button
                onClick={() => handleClose(false)}
                className="px-4 py-2 text-[13px] font-medium text-foreground bg-surface border border-border-strong rounded-md hover:bg-surface-2 transition-colors"
              >
                {options.cancelText || t("common.cancel")}
              </button>
              <button
                onClick={() => handleClose(true)}
                className={`px-4 py-2 text-[13px] font-medium rounded-md transition-colors ${
                  options.destructive
                    /* TODO: token for status-error is not in theme palette, keeping functional status color and text-white */
                    ? 'bg-status-error hover:bg-status-error/90 text-white'
                    : 'bg-accent hover:opacity-90 text-on-accent'
                }`}
              >
                {options.confirmText || t("common.confirm")}
              </button>
            </div>
          </div>
        </div>
      )}
    </ConfirmContext.Provider>
  );
}

export function useConfirm() {
  const context = useContext(ConfirmContext);
  if (!context) throw new Error('useConfirm must be used within ConfirmProvider');
  return context;
}
