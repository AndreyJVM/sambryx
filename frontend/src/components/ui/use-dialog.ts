import { useEffect, useRef } from "react";

const dialogs: symbol[] = [];

export function useDialog(open: boolean, onDismiss: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  const dismiss = useRef(onDismiss);
  useEffect(() => { dismiss.current = onDismiss; }, [onDismiss]);
  useEffect(() => {
    if (!open || !ref.current) return;
    const element = ref.current;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const id = Symbol();
    dialogs.push(id);
    const focusable = () => Array.from(element.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex="0"]'
    )).filter(item => item.getClientRects().length > 0);
    if (!element.contains(document.activeElement)) (focusable()[0] || element).focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (dialogs[dialogs.length - 1] !== id) return;
      if (event.key === "Escape" && !event.defaultPrevented) {
        event.preventDefault();
        dismiss.current();
      } else if (event.key === "Tab") {
        const items = focusable();
        const first = items[0], last = items[items.length - 1];
        if (!first || !last) { event.preventDefault(); element.focus(); return; }
        if (event.shiftKey && (document.activeElement === first || !element.contains(document.activeElement))) {
          event.preventDefault(); last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !element.contains(document.activeElement))) {
          event.preventDefault(); first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      const wasTop = dialogs[dialogs.length - 1] === id;
      dialogs.splice(dialogs.indexOf(id), 1);
      if (wasTop && previous?.isConnected) previous.focus();
    };
  }, [open]);
  return ref;
}
