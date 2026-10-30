import { useId, useState } from "react";
import { Modal } from "../../components/ui/modal";
import { api } from "../../lib/api";
import { useTranslation } from "../../lib/i18n";

interface Report {
  share: string;
  username: string;
  findings: { code: string; status: string; evidence: string; nextAction: string }[];
}

export function ShareAccessDialog({ share, usernames, onClose }: {
  share: string; usernames: string[]; onClose: () => void;
}) {
  const { t } = useTranslation();
  const inputId = useId();
  const [username, setUsername] = useState("");
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState(false);
  const valid = /^[a-z_][a-z0-9_.-]{0,31}$/.test(username);

  const check = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || loading) return;
    setLoading(true);
    setError(false);
    setReport(null);
    try {
      setReport(await api.get<Report>(`/api/shares/${encodeURIComponent(share)}/access-check?username=${encodeURIComponent(username)}`));
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  return <Modal isOpen onClose={onClose} title={`${t("diagnostics.title")}: ${share}`} description={t("diagnostics.description")} maxWidth="lg">
    <form onSubmit={check} className="space-y-4">
      <div className="space-y-2">
        <label htmlFor={inputId} className="block text-sm text-foreground">{t("diagnostics.username")}</label>
        <input id={inputId} list={`${inputId}-users`} value={username} disabled={loading} maxLength={32}
          onChange={event => { setUsername(event.target.value); setReport(null); setError(false); }}
          autoComplete="off" aria-describedby={`${inputId}-hint`}
          className="w-full min-w-0 bg-surface text-foreground border border-border-strong rounded-md px-3 py-2" />
        <datalist id={`${inputId}-users`}>{usernames.filter(name => /^[a-z_][a-z0-9_.-]{0,31}$/.test(name)).map(name => <option key={name} value={name} />)}</datalist>
        <p id={`${inputId}-hint`} className="text-sm text-text-muted">{t("diagnostics.identityHint")}</p>
      </div>
      <button type="submit" disabled={!valid || loading} className="px-4 py-2 rounded-md bg-accent text-on-accent disabled:opacity-50">{t("diagnostics.run")}</button>
      {loading && <p role="status" className="text-sm text-text-muted">{t("diagnostics.loading")}</p>}
      {error && <p role="alert" className="text-sm text-status-error">{t("diagnostics.error")}</p>}
    </form>
    {report && <div className="mt-5 space-y-3 break-words" aria-live="polite">
      <p className="font-medium text-foreground">{report.share} · {report.username}</p>
      <p className="text-sm text-text-muted">{t("diagnostics.partial")}</p>
      <ul className="space-y-3">{report.findings.map(finding => <li key={finding.code} className="border border-border rounded-md p-3 space-y-1">
        <h3 className="text-sm font-semibold text-foreground">{t(`diagnostics.checks.${finding.code}`)}</h3>
        <p className={`text-sm font-semibold ${finding.status === "BLOCKING" ? "text-status-error" : "text-text-muted"}`}>{t(`diagnostics.${finding.status}`)}</p>
        <p className="text-sm text-foreground">{t(`diagnostics.${finding.evidence}`)}</p>
        <p className="text-sm text-text-muted">{t(`diagnostics.${finding.nextAction}`)}</p>
      </li>)}</ul>
    </div>}
  </Modal>;
}
