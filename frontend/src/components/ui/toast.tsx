import { createContext, useContext, useState, useCallback, useMemo, ReactNode } from "react";
import { AlertCircle, CheckCircle, Info, X } from "lucide-react";
import { useTranslation } from "../../lib/i18n";

export type ToastType = "success" | "error" | "info";

export interface ToastMessage {
  id: string;
  type: ToastType;
  title: string;
  message?: string;
}

interface ToastContextType {
  toast: (type: ToastType, title: string, message?: string) => void;
  success: (title: string, message?: string) => void;
  error: (title: string, message?: string) => void;
  info: (title: string, message?: string) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export const ToastProvider = ({ children }: { children: ReactNode }) => {
  const { t } = useTranslation();
  const tClose = t("common.close");
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const addToast = useCallback((type: ToastType, title: string, message?: string) => {
    const id = Math.random().toString(36).substring(2, 9);
    setToasts((prev) => [...prev, { id, type, title, message }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 5000);
  }, []);

  const removeToast = (id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  };

  const contextValue = useMemo(() => ({
    toast: addToast,
    success: (title: string, message?: string) => addToast("success", title, message),
    error: (title: string, message?: string) => addToast("error", title, message),
    info: (title: string, message?: string) => addToast("info", title, message),
  }), [addToast]);

  return (
    <ToastContext.Provider value={contextValue}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 pointer-events-none w-[calc(100%-2rem)] max-w-sm max-h-[calc(100svh-2rem)] overflow-y-auto">
        {toasts.map((t) => (
          <div
            key={t.id}
            role={t.type === "error" ? "alert" : "status"}
            className={`pointer-events-auto flex items-start gap-3 p-4 rounded-xl border animate-in slide-in-from-bottom-5 fade-in duration-300 ${
              t.type === "success"
                ? "bg-surface-tint border-border text-accent-text"
                : t.type === "error"
                /* TODO: token for status-error is not in theme palette, keeping functional status color */
                ? "bg-surface border-status-error/30 text-status-error"
                : "bg-surface border-border text-foreground"
            }`}
          >
            {t.type === "success" && <CheckCircle className="w-5 h-5 mt-0.5 text-accent-text shrink-0" />}
            {t.type === "error" && <AlertCircle className="w-5 h-5 mt-0.5 text-status-error shrink-0" />}
            {t.type === "info" && <Info className="w-5 h-5 mt-0.5 text-text-muted shrink-0" />}
            
            <div className="flex-1 min-w-0 break-words">
              <h4 className="text-[14px] font-bold">{t.title}</h4>
              {t.message && <p className="text-[13px] mt-0.5 opacity-90">{t.message}</p>}
            </div>
            
            <button
              onClick={() => removeToast(t.id)}
              aria-label={tClose}
              className="p-1 shrink-0 rounded-md opacity-50 hover:opacity-100 transition-opacity text-foreground"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};

export const useToast = () => {
  const context = useContext(ToastContext);
  if (!context) throw new Error("useToast must be used within ToastProvider");
  return context;
};
