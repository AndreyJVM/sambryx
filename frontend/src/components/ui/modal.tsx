import React, { useId } from 'react';
import { useDialog } from './use-dialog';
import { useTranslation } from '../../lib/i18n';
import { X } from 'lucide-react';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: React.ReactNode;
  maxWidth?: 'sm' | 'md' | 'lg' | 'xl' | '2xl' | '3xl';
  zIndex?: number;
}

export function Modal({ isOpen, onClose, title, description, children, maxWidth = 'md', zIndex = 50 }: ModalProps) {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useDialog(isOpen, onClose);
  const { t } = useTranslation();
  if (!isOpen) return null;

  const maxWidthClass = {
    'sm': 'max-w-sm',
    'md': 'max-w-md',
    'lg': 'max-w-lg',
    'xl': 'max-w-xl',
    '2xl': 'max-w-2xl',
    '3xl': 'max-w-3xl',
  }[maxWidth];

  return (
    <div 
      className="fixed inset-0 flex items-center justify-center p-4 sm:p-6"
      style={{ zIndex }}
    >
      <div 
        className="absolute inset-0 bg-bg/80 backdrop-blur-sm transition-opacity" 
        onClick={onClose} 
      />
      <div 
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={`relative bg-surface w-full ${maxWidthClass} border border-border rounded-lg flex flex-col max-h-[90vh] animate-in zoom-in-95 duration-200`}
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-border">
          <div className="min-w-0 break-words">
            <h2 id={titleId} className="text-lg font-semibold text-foreground tracking-tight">{title}</h2>
            {description && <p id={descriptionId} className="text-[13px] text-text-muted mt-0.5">{description}</p>}
          </div>
          <button 
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="p-1.5 min-h-11 min-w-11 shrink-0 flex items-center justify-center text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>
        <div className="p-6 overflow-y-auto custom-scrollbar">
          {children}
        </div>
      </div>
    </div>
  );
}
