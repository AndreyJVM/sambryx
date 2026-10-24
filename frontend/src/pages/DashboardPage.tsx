import { getErrorMessage } from "../lib/errors";
import {
  useEffect,
  useState,
  useCallback,
  useRef,
  type ReactNode,
} from "react";
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  ChevronDown,
  FileStack,
  FolderOpen,
  HardDrive,
  Play,
  RefreshCw,
  Server,
  Square,
  Users,
} from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../lib/api";
import { useToast } from "../components/ui/toast";
import { useConfirm } from "../components/ui/confirm";
import { useTranslation } from "../lib/i18n";
import { FileManager } from "../components/FileManager";

interface DashboardData {
  isRunning: boolean;
  connections: { pid: string; user: string; machine: string }[];
  openFiles: { pid: string; rw: string; file: string }[];
}

interface DiskUsage {
  total: string;
  used: string;
  available: string;
  usePercent: number;
  mountPoint: string;
}
type ServiceAction = "start" | "stop" | "restart";

const panel = "dashboard-panel rounded-xl border border-border bg-surface";
const button =
  "inline-flex items-center justify-center gap-2 rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-surface-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-text disabled:cursor-not-allowed disabled:opacity-50";

function Metric({
  label,
  value,
  detail,
  icon,
}: {
  label: string;
  value: ReactNode;
  detail: string;
  icon: ReactNode;
}) {
  return (
    <div className={`${panel} dashboard-metric p-5`}>
      <div className="flex items-center justify-between gap-3 text-text-muted">
        <span className="text-sm">{label}</span>
        <span className="dashboard-metric-icon">{icon}</span>
      </div>
      <div className="mt-4 text-3xl font-semibold tracking-tight tabular-nums">
        {value}
      </div>
      <p className="mt-2 text-xs text-text-muted">{detail}</p>
    </div>
  );
}

export default function DashboardPage() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [disk, setDisk] = useState<DiskUsage | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [dashboardError, setDashboardError] = useState("");
  const [diskError, setDiskError] = useState("");
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [pendingAction, setPendingAction] = useState<ServiceAction | null>(
    null,
  );
  const [explorerOpen, setExplorerOpen] = useState(false);
  const [explorerLoaded, setExplorerLoaded] = useState(false);
  const mounted = useRef(false);
  const fetching = useRef(false);
  const controlling = useRef(false);
  const { info, error: toastError } = useToast();
  const { confirm } = useConfirm();
  const { t, lang } = useTranslation();

  const refresh = useCallback(async () => {
    if (fetching.current) return;
    fetching.current = true;
    if (mounted.current) setRefreshing(true);
    try {
      const [overview, storage] = await Promise.allSettled([
        api.get<DashboardData>("/api/monitoring/dashboard"),
        api.get<DiskUsage>("/api/fs/disk-usage?path=/"),
      ]);
      if (!mounted.current) return;
      if (overview.status === "fulfilled") {
        setData(overview.value);
        setUpdatedAt(new Date());
        setDashboardError("");
      } else {
        setDashboardError(getErrorMessage(overview.reason));
      }
      if (storage.status === "fulfilled") {
        setDisk(storage.value);
        setDiskError("");
      } else {
        setDiskError(getErrorMessage(storage.reason));
      }
    } finally {
      fetching.current = false;
      if (mounted.current) {
        setInitialLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const interval = setInterval(() => {
      void refresh();
    }, 10000);
    return () => {
      mounted.current = false;
      clearInterval(interval);
    };
  }, [refresh]);

  const handleServiceControl = async (action: ServiceAction) => {
    if (controlling.current) return;
    controlling.current = true;
    setPendingAction(action);
    try {
      if (
        action !== "start" &&
        !(await confirm({
          title: t(
            action === "stop"
              ? "dashboard.confirmStop"
              : "dashboard.confirmRestart",
          ),
          message: t("dashboard.serviceImpact"),
          destructive: action === "stop",
          confirmText: t(
            action === "stop" ? "dashboard.stop" : "dashboard.restart",
          ),
        }))
      )
        return;
      if (!mounted.current) return;
      await api.post(`/api/monitoring/control?action=${action}`);
      if (!mounted.current) return;
      info(
        t("dashboard.commandCompleted"),
        t(
          `dashboard.${action === "start" ? "started" : action === "stop" ? "stopped" : "restarted"}`,
        ),
      );
      await refresh();
    } catch (err) {
      if (mounted.current)
        toastError(t("dashboard.controlError"), getErrorMessage(err));
    } finally {
      controlling.current = false;
      if (mounted.current) setPendingAction(null);
    }
  };

  const percentage = disk ? Math.max(0, Math.min(100, disk.usePercent)) : 0;
  const diskTone =
    percentage >= 90
      ? "bg-status-error"
      : percentage >= 70
        ? "bg-status-warning"
        : "bg-accent";
  const connections = data?.connections ?? [];
  const openFiles = data?.openFiles ?? [];
  const missing = initialLoading ? "…" : "—";

  return (
    <div className="dashboard-page space-y-6 animate-in fade-in duration-300">
      <header className="dashboard-heading flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="mb-2 flex items-center gap-2 text-xs font-medium uppercase tracking-widest text-text-muted">
            <Activity className="h-3.5 w-3.5" />
            Samba · SSH
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            {t("dashboard.title")}
          </h1>
          <p className="mt-1 text-sm text-text-muted">
            {t("dashboard.subtitle")}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3 sm:justify-end">
          <div className="text-xs text-text-muted sm:text-right" role="status">
            <p>{t("dashboard.autoRefresh")}</p>
            <p className="mt-1 tabular-nums">
              {updatedAt
                ? `${t("dashboard.updated")} ${updatedAt.toLocaleTimeString(lang, { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`
                : t("dashboard.notUpdated")}
            </p>
          </div>
          <button
            className={button}
            onClick={() => {
              void refresh();
            }}
            disabled={refreshing || pendingAction !== null}
            aria-label={t("dashboard.refresh")}
          >
            <RefreshCw
              className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`}
            />
            {t("dashboard.refresh")}
          </button>
        </div>
      </header>

      {dashboardError && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-xl border border-status-warning/40 bg-surface p-4"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-status-warning" />
          <div className="text-sm">
            <p className="font-medium">
              {t(data ? "dashboard.staleData" : "dashboard.loadError")}
            </p>
            <p className="mt-1 text-text-muted">{dashboardError}</p>
          </div>
        </div>
      )}

      <div
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
        aria-busy={initialLoading}
      >
        <Metric
          label={t("dashboard.serviceStatus")}
          icon={<Server className="h-4 w-4" />}
          value={
            data ? (
              <span
                className={`flex items-center gap-2 text-2xl ${data.isRunning ? "text-accent-text" : "text-status-error"}`}
              >
                <span
                  className={`h-2 w-2 rounded-full ${data.isRunning ? "bg-status-active" : "bg-status-error"}`}
                />
                {t(data.isRunning ? "sidebar.running" : "sidebar.stopped")}
              </span>
            ) : (
              missing
            )
          }
          detail={t("dashboard.serviceCaption")}
        />
        <Metric
          label={t("dashboard.activeSessions")}
          icon={<Users className="h-4 w-4" />}
          value={data ? connections.length : missing}
          detail={t("dashboard.sessionsCaption")}
        />
        <Metric
          label={t("dashboard.openFiles")}
          icon={<FileStack className="h-4 w-4" />}
          value={data ? openFiles.length : missing}
          detail={t("dashboard.filesCaption")}
        />
        <Metric
          label={t("dashboard.rootDisk")}
          icon={<HardDrive className="h-4 w-4" />}
          value={disk ? `${percentage}%` : missing}
          detail={
            diskError
              ? t("dashboard.diskUnavailable")
              : disk
                ? `${t("dashboard.free")}: ${disk.available}`
                : t("dashboard.rootDiskCaption")
          }
        />
      </div>

      <section
        className={`${panel} flex flex-col gap-4 p-4 sm:flex-row sm:items-center sm:justify-between`}
        aria-label={t("dashboard.serviceActions")}
      >
        <div className="flex items-start gap-3">
          <div className="rounded-lg border border-border bg-surface-2 p-2">
            <Server className="h-5 w-5 text-text-muted" />
          </div>
          <div>
            <h2 className="text-sm font-semibold">
              {t("dashboard.serviceActions")}
            </h2>
            <p className="mt-1 text-xs text-text-muted">
              {t("dashboard.serviceImpact")}
            </p>
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          {data && !data.isRunning ? (
            <button
              className={`${button} !bg-accent !text-on-accent`}
              onClick={() => {
                void handleServiceControl("start");
              }}
              disabled={pendingAction !== null || Boolean(dashboardError)}
            >
              <Play className="h-4 w-4" />
              {t("dashboard.startService")}
            </button>
          ) : (
            <>
              <button
                className={button}
                onClick={() => {
                  void handleServiceControl("restart");
                }}
                disabled={
                  !data || pendingAction !== null || Boolean(dashboardError)
                }
              >
                <RefreshCw
                  className={`h-4 w-4 ${pendingAction === "restart" ? "animate-spin" : ""}`}
                />
                {t("dashboard.restart")}
              </button>
              <button
                className={`${button} !text-status-error`}
                onClick={() => {
                  void handleServiceControl("stop");
                }}
                disabled={
                  !data || pendingAction !== null || Boolean(dashboardError)
                }
              >
                <Square className="h-3.5 w-3.5" />
                {t("dashboard.stop")}
              </button>
            </>
          )}
        </div>
      </section>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <section className={`${panel} overflow-hidden`}>
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 className="flex items-center gap-2 text-sm font-semibold">
              <Users className="h-4 w-4 text-text-muted" />
              {t("dashboard.activeSessions")}
            </h2>
            <span className="rounded-md bg-surface-2 px-2 py-0.5 text-xs tabular-nums text-text-muted">
              {data ? connections.length : "—"}
            </span>
          </div>
          {connections.length ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="bg-surface-2 text-xs text-text-muted">
                  <tr>
                    <th className="px-5 py-3 font-medium">
                      {t("dashboard.sessionUser")}
                    </th>
                    <th className="px-5 py-3 font-medium">
                      {t("dashboard.clientAddress")}
                    </th>
                    <th className="px-5 py-3 font-medium">PID</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {connections.slice(0, 6).map((connection, index) => (
                    <tr key={`${connection.pid}-${index}`}>
                      <td className="px-5 py-3 font-medium">
                        {connection.user || "—"}
                      </td>
                      <td className="px-5 py-3 font-mono text-xs text-text-muted">
                        {connection.machine || "—"}
                      </td>
                      <td className="px-5 py-3 font-mono text-xs text-text-muted">
                        {connection.pid || "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="flex min-h-40 flex-col items-center justify-center gap-2 p-6 text-center">
              <Users className="h-6 w-6 text-text-muted" />
              <p className="text-sm text-text-muted">
                {t(
                  data
                    ? "dashboard.noSessions"
                    : initialLoading
                      ? "sidebar.wait"
                      : "dashboard.noData",
                )}
              </p>
            </div>
          )}
          {connections.length > 6 && (
            <p className="border-t border-border px-5 py-3 text-xs text-text-muted">
              {t("dashboard.showingSessions")} 6 / {connections.length}
            </p>
          )}
        </section>
        <section className={`${panel} overflow-hidden`}>
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            <h2 className="flex items-center gap-2 text-sm font-semibold">
              <FileStack className="h-4 w-4 text-text-muted" />
              {t("dashboard.openFiles")}
            </h2>
            <span className="rounded-md bg-surface-2 px-2 py-0.5 text-xs tabular-nums text-text-muted">
              {data ? openFiles.length : "—"}
            </span>
          </div>
          {openFiles.length ? (
            <ul className="divide-y divide-border">
              {openFiles.slice(0, 6).map((file, index) => (
                <li
                  key={`${file.pid}-${index}`}
                  className="flex items-center gap-3 px-5 py-3"
                >
                  <FileStack className="h-4 w-4 shrink-0 text-text-muted" />
                  <div className="min-w-0 flex-1">
                    <p
                      className="truncate text-sm font-medium"
                      title={file.file}
                    >
                      {file.file || t("dashboard.unnamedFile")}
                    </p>
                    <p className="mt-0.5 text-xs text-text-muted">
                      PID {file.pid || "—"}
                    </p>
                  </div>
                  <span className="rounded border border-border px-1.5 py-0.5 font-mono text-xs text-text-muted">
                    {file.rw || "—"}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <div className="flex min-h-40 flex-col items-center justify-center gap-2 p-6 text-center">
              <FileStack className="h-6 w-6 text-text-muted" />
              <p className="text-sm text-text-muted">
                {t(
                  data
                    ? "dashboard.noFilesInUse"
                    : initialLoading
                      ? "sidebar.wait"
                      : "dashboard.noData",
                )}
              </p>
            </div>
          )}
          {openFiles.length > 6 && (
            <p className="border-t border-border px-5 py-3 text-xs text-text-muted">
              {t("dashboard.showingFiles")} 6 / {openFiles.length}
            </p>
          )}
        </section>
      </div>

      <section className={`${panel} p-5`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 text-sm font-semibold">
            <HardDrive className="h-4 w-4 text-text-muted" />
            {t("dashboard.rootDisk")}
          </h2>
          <span className="font-mono text-xs text-text-muted">
            {disk?.mountPoint || "/"}
          </span>
        </div>
        {diskError && (
          <p role="status" className="mt-3 text-sm text-status-warning">
            {t(disk ? "dashboard.diskStale" : "dashboard.diskUnavailable")}
          </p>
        )}
        {disk ? (
          <>
            <div className="mt-5 flex items-center gap-4">
              <div
                role="progressbar"
                aria-label={t("dashboard.used")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={percentage}
                className="h-2.5 flex-1 overflow-hidden rounded-full bg-track"
              >
                <div
                  className={`h-full rounded-full transition-all duration-500 ${diskTone}`}
                  style={{ width: `${percentage}%` }}
                />
              </div>
              <span className="text-sm font-semibold tabular-nums">
                {percentage}%
              </span>
            </div>
            <div className="mt-4 grid grid-cols-3 gap-3">
              {[
                ["total", disk.total],
                ["used", disk.used],
                ["free", disk.available],
              ].map(([label, value]) => (
                <div key={label}>
                  <p className="text-xs text-text-muted">
                    {t(`dashboard.${label}`)}
                  </p>
                  <p className="mt-1 font-mono text-sm font-medium">{value}</p>
                </div>
              ))}
            </div>
            {percentage >= 90 && (
              <p className="mt-4 flex items-center gap-2 text-sm text-status-error">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                {t("dashboard.diskCritical")}
              </p>
            )}
          </>
        ) : (
          <p className="py-6 text-sm text-text-muted">
            {t(initialLoading ? "sidebar.wait" : "dashboard.noData")}
          </p>
        )}
      </section>

      <div className="flex flex-wrap gap-3">
        <Link to="/shares" className={button}>
          <FolderOpen className="h-4 w-4" />
          {t("sidebar.shares")}
          <ArrowUpRight className="h-3.5 w-3.5 text-text-muted" />
        </Link>
        <Link to="/users" className={button}>
          <Users className="h-4 w-4" />
          {t("sidebar.users")}
          <ArrowUpRight className="h-3.5 w-3.5 text-text-muted" />
        </Link>
      </div>

      <section className="border-t border-border pt-5">
        <button
          onClick={() => {
            setExplorerLoaded(true);
            setExplorerOpen(!explorerOpen);
          }}
          aria-expanded={explorerOpen}
          aria-controls="dashboard-explorer"
          className="flex w-full items-center justify-between gap-3 rounded-lg py-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent-text"
        >
          <span>
            <span className="flex items-center gap-2 text-base font-semibold">
              <FolderOpen className="h-4 w-4 text-text-muted" />
              {t("dashboard.storageExplorer")}
            </span>
            <span className="mt-1 block text-xs text-text-muted">
              {t("dashboard.explorerCaption")}
            </span>
          </span>
          <ChevronDown
            className={`h-5 w-5 shrink-0 text-text-muted transition-transform ${explorerOpen ? "rotate-180" : ""}`}
          />
        </button>
        <div id="dashboard-explorer" hidden={!explorerOpen}>
          {explorerLoaded && (
            <div className="pt-4">
              <FileManager />
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
