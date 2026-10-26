import { useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import { useTranslation } from "../lib/i18n";
import { Modal } from "./ui/modal";

interface SessionSnapshot { serverTime: number; deadline: number }

export default function SessionWarning() {
  const { t } = useTranslation();
  const [remaining, setRemaining] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const [failed, setFailed] = useState(false);
  const renew = useRef<() => void>(() => {});

  useEffect(() => {
    let mounted = true;
    let deadline: number | null = null;
    let reading = false;
    let renewing = false;
    let lastServerDeadline = 0;
    const channel = new BroadcastChannel("sambryx-session");
    const apply = (snapshot: SessionSnapshot, started: number) => {
      if (!mounted || snapshot.deadline < lastServerDeadline) return;
      lastServerDeadline = snapshot.deadline;
      // Account conservatively for transit time; browser time never authorizes server access.
      deadline = started + Math.max(0, snapshot.deadline - snapshot.serverTime);
      setRemaining(Math.max(0, deadline - Date.now()));
      setFailed(false);
    };
    const read = async () => {
      if (reading) return;
      reading = true;
      const started = Date.now();
      try { apply(await api.get<SessionSnapshot>("/api/auth/session"), started); }
      catch { if (mounted) setFailed(true); }
      finally { reading = false; }
    };
    const activity = async () => {
      if (renewing) return;
      renewing = true;
      if (mounted) setPending(true);
      const started = Date.now();
      try {
        apply(await api.post<SessionSnapshot>("/api/auth/activity"), started);
        channel.postMessage("changed");
      } catch { if (mounted) setFailed(true); }
      finally { renewing = false; if (mounted) setPending(false); }
    };
    renew.current = () => { void activity(); };
    const input = (event: Event) => {
      if (event.isTrusted && event.target instanceof HTMLElement &&
          event.target.matches("input, textarea, select")) void activity();
    };
    const click = (event: MouseEvent) => {
      if (!event.isTrusted || !(event.target instanceof Element)) return;
      const link = event.target.closest("a[href]");
      const url = link instanceof HTMLAnchorElement ? new URL(link.href) : null;
      const navigation = url?.origin === window.location.origin && url.pathname.startsWith("/ui/") &&
        url.pathname !== window.location.pathname;
      if (navigation || event.target.closest("[data-session-activity]")) void activity();
    };
    const check = () => { void read(); };
    const notify = () => { channel.postMessage("changed"); void read(); };
    channel.onmessage = check;
    document.addEventListener("input", input);
    document.addEventListener("click", click);
    document.addEventListener("visibilitychange", check);
    window.addEventListener("focus", check);
    window.addEventListener("sambryx:session-change", notify);
    const ticker = window.setInterval(() => {
      if (deadline === null) return;
      const left = Math.max(0, deadline - Date.now());
      setRemaining(left);
      if (left === 0) void read();
    }, 1000);
    const statusPoll = window.setInterval(check, 30_000);
    void read();
    return () => {
      mounted = false;
      renew.current = () => {};
      channel.close();
      clearInterval(ticker);
      clearInterval(statusPoll);
      document.removeEventListener("input", input);
      document.removeEventListener("click", click);
      document.removeEventListener("visibilitychange", check);
      window.removeEventListener("focus", check);
      window.removeEventListener("sambryx:session-change", notify);
    };
  }, []);

  return <Modal isOpen={remaining !== null && remaining <= 60_000} onClose={() => {}}
    title={t("session.warningTitle")} description={t("session.warningDescription")} maxWidth="sm" zIndex={100} showCloseButton={false}>
    <p className="text-sm text-text-muted mb-4" role="status">
      {failed ? t("session.unavailable") : `${t("session.remaining")} ${Math.ceil((remaining ?? 0) / 1000)}`}
    </p>
    <button type="button" disabled={pending} onClick={() => renew.current()}
      className="w-full min-h-11 px-4 py-2 rounded-md bg-accent text-on-accent font-semibold disabled:opacity-50">
      {t("session.continue")}
    </button>
  </Modal>;
}
