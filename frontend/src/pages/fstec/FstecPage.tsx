import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback } from "react";
import {
  ShieldCheck,
  RefreshCw,
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  AlertTriangle,
  Info,
  CheckCircle2,
  XCircle,
  HelpCircle,
  FileText
} from "lucide-react";
import { api } from "../../lib/api";
import { useToast } from "../../components/ui/toast";
import { useTranslation } from "../../lib/i18n";

export type FstecStatus = "OK" | "FAILED" | "NOT_APPLICABLE" | "MANUAL";

export interface ShareResult {
  shareName: string;
  status: FstecStatus;
  currentValue: string;
}

export interface FstecRuleResult {
  id: string;
  section: string;
  title: string;
  severity: "HIGH" | "MEDIUM" | "LOW" | string;
  status: FstecStatus;
  currentValue: string;
  expectedValue: string;
  howToFix: string;
  consequences?: string;
  shareResults?: ShareResult[];
}

export interface FstecReportDto {
  checkedAt: string;
  compliancePercent: number;
  okCount: number;
  failedCount: number;
  manualCount: number;
  notApplicableCount: number;
  rules: FstecRuleResult[];
}

export default function FstecPage() {
  const [report, setReport] = useState<FstecReportDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [expandedRules, setExpandedRules] = useState<Record<string, boolean>>({});
  const [statusFilter, setStatusFilter] = useState<"ALL" | "FAILED" | "OK" | "MANUAL">("ALL");
  const [copiedRuleId, setCopiedRuleId] = useState<string | null>(null);
  const [copiedAll, setCopiedAll] = useState(false);

  const { error: toastError, success: toastSuccess } = useToast();
  const { t } = useTranslation();

  const fetchReport = useCallback(async () => {
    try {
      setLoading(true);
      setLoadError("");
      const res = await api.get<FstecReportDto>("/api/fstec/check");
      setReport(res);
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const toggleExpand = (id: string) => {
    setExpandedRules(prev => ({ ...prev, [id]: !prev[id] }));
  };

  const copyToClipboard = async (text: string, ruleId?: string) => {
    try {
      await navigator.clipboard.writeText(text);
      if (ruleId) {
        setCopiedRuleId(ruleId);
        setTimeout(() => setCopiedRuleId(null), 2000);
      } else {
        setCopiedAll(true);
        setTimeout(() => setCopiedAll(false), 2000);
      }
      toastSuccess(t("fstec.copied"), "");
    } catch {
      toastError("Copy Error", "Failed to copy text to clipboard");
    }
  };

  const copyAllFailedFixes = () => {
    if (!report) return;
    const failedRules = report.rules.filter(r => r.status === "FAILED");
    if (failedRules.length === 0) return;

    const snippet = failedRules
      .map(r => `# [${r.section}] ${r.title}\n${r.howToFix.trim()}`)
      .join("\n\n");

    copyToClipboard(snippet);
  };

  const filteredRules = (report?.rules || []).filter(rule => {
    if (statusFilter === "ALL") return true;
    if (statusFilter === "FAILED") return rule.status === "FAILED";
    if (statusFilter === "OK") return rule.status === "OK";
    if (statusFilter === "MANUAL") return rule.status === "MANUAL";
    return true;
  });

  const getStatusBadge = (status: FstecStatus) => {
    switch (status) {
      case "OK":
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-status-active/15 text-status-active border border-status-active/30">
            <CheckCircle2 className="w-3.5 h-3.5" />
            {t("fstec.statusOk")}
          </span>
        );
      case "FAILED":
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-status-error/15 text-status-error border border-status-error/30">
            <XCircle className="w-3.5 h-3.5" />
            {t("fstec.statusFailed")}
          </span>
        );
      case "MANUAL":
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-status-warning/15 text-status-warning border border-status-warning/30">
            <AlertTriangle className="w-3.5 h-3.5" />
            {t("fstec.statusManual")}
          </span>
        );
      case "NOT_APPLICABLE":
      default:
        return (
          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-status-disabled/15 text-status-disabled border border-border">
            <HelpCircle className="w-3.5 h-3.5" />
            {t("fstec.statusNotApplicable")}
          </span>
        );
    }
  };

  const getSeverityBadge = (severity: string) => {
    const sev = severity.toUpperCase();
    if (sev === "HIGH") {
      return (
        <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-status-error/10 text-status-error border border-status-error/20">
          {t("fstec.severityHigh")}
        </span>
      );
    }
    if (sev === "MEDIUM") {
      return (
        <span className="px-2 py-0.5 rounded text-[11px] font-semibold bg-status-warning/10 text-status-warning border border-status-warning/20">
          {t("fstec.severityMedium")}
        </span>
      );
    }
    return (
      <span className="px-2 py-0.5 rounded text-[11px] font-medium bg-surface-2 text-text-muted border border-border">
        {t("fstec.severityLow")}
      </span>
    );
  };

  return (
    <div className="fstec-page space-y-6 animate-in fade-in duration-300">
      {/* Header section */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-foreground flex items-center gap-2.5">
            <ShieldCheck className="w-7 h-7 text-status-active" />
            {t("fstec.title")}
          </h1>
          <p className="text-sm text-text-muted mt-1">
            {t("fstec.subtitle")}
          </p>
        </div>

        <div className="flex items-center gap-2.5 flex-wrap">
          <button
            onClick={fetchReport}
            disabled={loading}
            className="flex items-center gap-2 px-3.5 py-2 text-sm font-medium bg-surface hover:bg-surface-2 border border-border rounded-lg text-foreground transition-colors disabled:opacity-50 shadow-sm"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? "animate-spin" : ""}`} />
            {t("fstec.recheck")}
          </button>

          {report && report.failedCount > 0 && (
            <button
              onClick={copyAllFailedFixes}
              className="flex items-center gap-2 px-3.5 py-2 text-sm font-medium bg-surface hover:bg-surface-2 border border-border rounded-lg text-foreground transition-colors shadow-sm"
            >
              {copiedAll ? <Check className="w-4 h-4 text-status-active" /> : <Copy className="w-4 h-4" />}
              {t("fstec.copyFixSnippet")}
            </button>
          )}
        </div>
      </div>

      {/* Info notice */}
      {loadError && <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p>{t(report ? "fstec.staleData" : "fstec.loadError")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}
      <div className="p-4 rounded-xl border border-border bg-surface flex items-start gap-3 shadow-sm">
        <Info className="w-5 h-5 text-text-muted shrink-0 mt-0.5" />
        <p className="text-xs text-text-muted leading-relaxed">
          {t("fstec.notice")}
        </p>
      </div>

      {/* Loading Skeleton */}
      {loading && !report && (
        <div role="status" aria-busy="true" aria-label={t("sidebar.wait")} className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="h-24 bg-surface border border-border rounded-xl animate-pulse" />
            ))}
          </div>
          <div className="h-96 bg-surface border border-border rounded-xl animate-pulse" />
        </div>
      )}

      {/* Report Dashboard Cards */}
      {report && (
        <>
          <div className="fstec-metrics grid grid-cols-2 xl:grid-cols-5 gap-3.5">
            {/* Compliance Percent Card */}
            <div className="p-4 rounded-xl border border-border bg-surface flex flex-col justify-between col-span-2 xl:col-span-1 shadow-sm">
              <span className="text-xs font-medium text-text-muted">{t("fstec.complianceScore")}</span>
              <div className="my-2">
                <div className="flex items-baseline gap-2">
                  <span className="text-3xl font-extrabold tracking-tight text-foreground">
                    {report.compliancePercent}%
                  </span>
                </div>
                <div role="progressbar" aria-label={t("fstec.complianceScore")} aria-valuemin={0} aria-valuemax={100} aria-valuenow={report.compliancePercent} className="w-full bg-track h-2 rounded-full overflow-hidden mt-2">
                  <div
                    className={`h-full transition-all duration-500 rounded-full ${
                      report.compliancePercent >= 80
                        ? "bg-status-active"
                        : report.compliancePercent >= 50
                        ? "bg-status-warning"
                        : "bg-status-error"
                    }`}
                    style={{ width: `${report.compliancePercent}%` }}
                  />
                </div>
              </div>
              <span className="text-[11px] text-text-muted">
                {t("fstec.checkedAt")}: {new Date(report.checkedAt).toLocaleTimeString()}
              </span>
            </div>

            {/* OK count card */}
            <div className="p-4 rounded-xl border border-border bg-surface flex flex-col justify-between shadow-sm">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">{t("fstec.statusOk")}</span>
                <CheckCircle2 className="w-4 h-4 text-status-active" />
              </div>
              <span className="text-2xl font-bold text-foreground mt-2">{report.okCount}</span>
              <span className="text-[11px] text-status-active font-medium">{t("fstec.okCaption")}</span>
            </div>

            {/* FAILED count card */}
            <div className="p-4 rounded-xl border border-border bg-surface flex flex-col justify-between shadow-sm">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">{t("fstec.statusFailed")}</span>
                <XCircle className="w-4 h-4 text-status-error" />
              </div>
              <span className="text-2xl font-bold text-foreground mt-2">{report.failedCount}</span>
              <span className="text-[11px] text-status-error font-medium">{t("fstec.failedCaption")}</span>
            </div>

            {/* MANUAL count card */}
            <div className="p-4 rounded-xl border border-border bg-surface flex flex-col justify-between shadow-sm">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">{t("fstec.statusManual")}</span>
                <AlertTriangle className="w-4 h-4 text-status-warning" />
              </div>
              <span className="text-2xl font-bold text-foreground mt-2">{report.manualCount}</span>
              <span className="text-[11px] text-status-warning font-medium">{t("fstec.manualCaption")}</span>
            </div>

            {/* NOT_APPLICABLE count card */}
            <div className="p-4 rounded-xl border border-border bg-surface flex flex-col justify-between shadow-sm">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-text-muted">{t("fstec.statusNotApplicable")}</span>
                <HelpCircle className="w-4 h-4 text-text-muted" />
              </div>
              <span className="text-2xl font-bold text-foreground mt-2">{report.notApplicableCount}</span>
              <span className="text-[11px] text-text-muted">{t("fstec.naCaption")}</span>
            </div>
          </div>

          {/* Filter Pills */}
          <div role="group" aria-label={t("fstec.filters")} className="fstec-filters flex flex-wrap items-center gap-2 border-b border-border pb-3">
            <button
              onClick={() => setStatusFilter("ALL")}
              aria-pressed={statusFilter === "ALL"}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === "ALL"
                  ? "bg-foreground text-bg"
                  : "bg-surface text-text-muted hover:text-foreground hover:bg-surface-2 border border-border"
              }`}
            >
              {t("fstec.filterAll")} ({report.rules.length})
            </button>
            <button
              onClick={() => setStatusFilter("FAILED")}
              aria-pressed={statusFilter === "FAILED"}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === "FAILED"
                  ? "bg-status-error text-white"
                  : "bg-surface text-text-muted hover:text-foreground hover:bg-surface-2 border border-border"
              }`}
            >
              {t("fstec.filterFailed")} ({report.failedCount})
            </button>
            <button
              onClick={() => setStatusFilter("OK")}
              aria-pressed={statusFilter === "OK"}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === "OK"
                  ? "bg-status-active text-white"
                  : "bg-surface text-text-muted hover:text-foreground hover:bg-surface-2 border border-border"
              }`}
            >
              {t("fstec.filterOk")} ({report.okCount})
            </button>
            <button
              onClick={() => setStatusFilter("MANUAL")}
              aria-pressed={statusFilter === "MANUAL"}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === "MANUAL"
                  ? "bg-status-warning text-white"
                  : "bg-surface text-text-muted hover:text-foreground hover:bg-surface-2 border border-border"
              }`}
            >
              {t("fstec.filterManual")} ({report.manualCount})
            </button>
          </div>

          {/* Rules Table / Cards */}
          <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
            {filteredRules.length === 0 ? (
              <div className="p-12 text-center text-text-muted">
                <FileText className="w-10 h-10 mx-auto mb-2 opacity-40" />
                <p className="text-sm">{t("fstec.emptyRules")}</p>
              </div>
            ) : (
              <div className="divide-y divide-border">
                {filteredRules.map(rule => {
                  const isExpanded = !!expandedRules[rule.id];
                  return (
                    <div key={rule.id} className="transition-colors hover:bg-surface-2/40">
                      {/* Main Rule Row */}
                      <button
                        type="button"
                        aria-expanded={isExpanded}
                        aria-controls={`fstec-rule-${encodeURIComponent(rule.id)}`}
                        onClick={() => toggleExpand(rule.id)}
                        className="fstec-rule-toggle w-full text-left p-4 flex flex-col md:flex-row md:items-center justify-between gap-3 cursor-pointer select-none"
                      >
                        <div className="flex items-start md:items-center gap-3 min-w-0">
                          <div className="shrink-0 pt-0.5 md:pt-0">
                            {getStatusBadge(rule.status)}
                          </div>

                          <div className="min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="font-mono text-xs font-bold text-text-muted">
                                § {rule.section}
                              </span>
                              <span className="font-semibold text-sm text-foreground">
                                {rule.title}
                              </span>
                              {getSeverityBadge(rule.severity)}
                            </div>

                            <div className="mt-1 flex items-center gap-2 text-xs text-text-muted flex-wrap">
                              <span>
                                {t("fstec.currentLabel")}: <span className="font-mono text-foreground font-medium">{rule.currentValue}</span>
                              </span>
                              <span>→</span>
                              <span>
                                {t("fstec.expectedLabel")}: <span className="font-mono text-foreground font-medium">{rule.expectedValue}</span>
                              </span>
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-2 self-end md:self-center shrink-0">
                          <span
                            className="p-1 text-text-muted hover:text-foreground transition-colors"
                            aria-hidden="true"
                          >
                            {isExpanded ? <ChevronUp className="w-5 h-5" /> : <ChevronDown className="w-5 h-5" />}
                          </span>
                        </div>
                      </button>

                      {/* Expanded Details */}
                      {isExpanded && (
                        <div id={`fstec-rule-${encodeURIComponent(rule.id)}`} className="fstec-rule-details px-4 pb-4 pt-1 bg-surface-2/60 border-t border-border/60 space-y-3.5">
                          {/* How to fix */}
                          <div>
                            <div className="flex items-center justify-between mb-1.5">
                              <span className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                                <FileText className="w-3.5 h-3.5 text-text-muted" />
                                {t("fstec.howToFix")}:
                              </span>
                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  copyToClipboard(rule.howToFix, rule.id);
                                }}
                                className="inline-flex items-center gap-1 text-[11px] font-medium text-text-muted hover:text-foreground px-2 py-0.5 rounded bg-surface border border-border transition-colors"
                              >
                                {copiedRuleId === rule.id ? (
                                  <>
                                    <Check className="w-3 h-3 text-status-active" />
                                    <span>{t("fstec.copied")}</span>
                                  </>
                                ) : (
                                  <>
                                    <Copy className="w-3 h-3" />
                                    <span>{t("fstec.copyRuleFix")}</span>
                                  </>
                                )}
                              </button>
                            </div>
                            <pre className="p-3 rounded-lg bg-surface border border-border font-mono text-xs text-foreground overflow-x-auto whitespace-pre-wrap selection:bg-accent selection:text-on-accent">
                              {rule.howToFix.trim()}
                            </pre>
                          </div>

                          {/* Consequences */}
                          {rule.consequences && (
                            <div className="p-3 rounded-lg bg-status-warning/10 border border-status-warning/20 flex items-start gap-2.5">
                              <AlertTriangle className="w-4 h-4 text-status-warning shrink-0 mt-0.5" />
                              <div className="text-xs leading-relaxed text-foreground">
                                <span className="font-semibold text-status-warning mr-1">
                                  {t("fstec.consequences")}:
                                </span>
                                {rule.consequences}
                              </div>
                            </div>
                          )}

                          {/* Per-share results if present */}
                          {rule.shareResults && rule.shareResults.length > 0 && (
                            <div>
                              <span className="text-xs font-semibold text-foreground block mb-1.5">
                                {t("fstec.sharesDetails")} ({rule.shareResults.length}):
                              </span>
                              <div className="rounded-lg border border-border bg-surface overflow-x-auto">
                                <table className="w-full text-left text-xs">
                                  <thead className="bg-surface-2 border-b border-border text-text-muted font-medium">
                                    <tr>
                                      <th className="px-3 py-2">{t("fstec.shareName")}</th>
                                      <th className="px-3 py-2">{t("fstec.shareStatus")}</th>
                                      <th className="px-3 py-2">{t("fstec.shareValue")}</th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-border font-mono">
                                    {rule.shareResults.map(share => (
                                      <tr key={share.shareName} className="hover:bg-surface-2/40">
                                        <td className="px-3 py-2 font-semibold text-foreground">
                                          [{share.shareName}]
                                        </td>
                                        <td className="px-3 py-2">
                                          {getStatusBadge(share.status)}
                                        </td>
                                        <td className="px-3 py-2 text-text-muted">
                                          {share.currentValue}
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
