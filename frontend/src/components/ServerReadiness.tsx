import { useRef, useState } from "react";
import { api } from "../lib/api";
import { useTranslation } from "../lib/i18n";

interface ReadinessReport {
  ready: boolean;
  checks: {
    id: string;
    status: "PASSED" | "FAILED" | "UNKNOWN";
    reason: string;
    nextAction: string;
  }[];
}

const checkActions: Record<string, string> = {
  tools: "reviewTools", configRead: "reviewConfigRead", configValidation: "reviewConfig",
  accountsRead: "reviewAccounts", administration: "reviewAdministration",
};

function isReadinessReport(value: unknown): value is ReadinessReport {
  if (!value || typeof value !== "object") return false;
  const report = value as Partial<ReadinessReport>;
  if (report.ready !== false || !Array.isArray(report.checks) || report.checks.length === 0 || report.checks.length > 5) return false;
  const ids = new Set<string>();
  return report.checks.every(value => {
    if (!value || typeof value !== "object") return false;
    const check = value as Partial<ReadinessReport["checks"][number]>;
    if (typeof check.id !== "string" || !Object.prototype.hasOwnProperty.call(checkActions, check.id)
      || check.nextAction !== checkActions[check.id] || ids.has(check.id)) return false;
    ids.add(check.id);
    if (check.id === "administration") return check.status === "UNKNOWN" && check.reason === "unverified";
    return (check.status === "PASSED" && check.reason === "confirmed")
      || (check.status === "FAILED" && check.reason === "rejected")
      || (check.status === "UNKNOWN" && check.reason === "unavailable");
  });
}

export function ServerReadiness() {
  const { t } = useTranslation();
  const [report, setReport] = useState<ReadinessReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const busy = useRef(false);

  const inspect = async () => {
    if (busy.current) return;
    busy.current = true;
    setLoading(true);
    setError(false);
    setReport(null);
    try {
      const result = await api.get<unknown>("/api/monitoring/readiness");
      if (isReadinessReport(result)) setReport(result);
      else setError(true);
    } catch {
      setError(true);
    } finally {
      busy.current = false;
      setLoading(false);
    }
  };

  return (
    <section aria-labelledby="server-readiness-title" className="dashboard-panel rounded-xl border border-border bg-surface p-5 space-y-4">
      <h2 id="server-readiness-title" className="font-semibold">{t("readiness.title")}</h2>
      <p className="text-sm text-text-muted">{t("readiness.description")}</p>
      <button type="button" onClick={() => void inspect()} disabled={loading}
        className="rounded-lg border border-border-strong px-3 py-2 text-sm font-medium hover:bg-surface-2 disabled:opacity-50">
        {t(loading ? "readiness.loading" : "readiness.run")}
      </button>
      {loading && <p role="status" className="text-sm text-text-muted">{t("readiness.loading")}</p>}
      {error && <p role="alert" className="text-sm text-status-error">{t("readiness.error")}</p>}
      {report && <div aria-live="polite" className="space-y-3">
        <p className="font-medium">{t("readiness.partial")}</p>
        <ul className="grid gap-3 md:grid-cols-2">
          {report.checks.map(check => <li key={check.id} className="rounded-lg border border-border p-3 space-y-2 break-words">
            <h3 className="text-sm font-semibold">{t(`readiness.checks.${check.id}`)}</h3>
            <p className={`text-sm font-medium ${check.status === "FAILED" ? "text-status-error" : "text-text-muted"}`}>{t(`readiness.${check.status}`)}</p>
            <p className="text-sm">{t(`readiness.${check.reason}`)}</p>
            <p className="text-sm text-text-muted">{t(`readiness.${check.nextAction}`)}</p>
          </li>)}
        </ul>
      </div>}
    </section>
  );
}
