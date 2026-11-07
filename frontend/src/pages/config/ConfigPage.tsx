import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback, useRef } from "react";
import { Save, RotateCcw, Clock, ArrowLeft, RefreshCw, AlertTriangle, Settings2 } from "lucide-react";
import { api } from "../../lib/api";
import { useToast } from "../../components/ui/toast";
import { useConfirm } from "../../components/ui/confirm";
import { Toggle } from "../../components/ui/toggle";
import { useTranslation } from "../../lib/i18n";

interface SmbGlobalConfig {
  workgroup: string;
  netbiosName: string;
  serverString: string;
  security: string;
  mapToGuest: string;
  interfaces: string;
  bindInterfacesOnly: boolean;
  minProtocol: string;
  maxProtocol: string;
  disableNetbios: boolean;
  loadPrinters: boolean;
  passdbBackend: string;
  guestAccount: string;

  realm: string;
  winbindUseDefaultDomain: boolean;
  idmapDefaultBackend: string;
  idmapDefaultRange: string;
  idmapDomainBackend: string;
  idmapDomainRange: string;
  templateShell: string;
}

interface Backup {
  filename: string;
  createdAt: string;
  size: string;
}

interface BackupComparison {
  filename: string;
  equal: boolean;
  changes: { type: "ADDED" | "REMOVED"; lineNumber: number; text: string }[];
}

export default function ConfigPage() {
  const [config, setConfig] = useState<SmbGlobalConfig | null>(null);
  const comparisonLock = useRef(false);
  const [comparing, setComparing] = useState(false);
  const [comparison, setComparison] = useState<{ filename: string; data?: BackupComparison; error?: boolean } | null>(null);
  const [backups, setBackups] = useState<Backup[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const backupCreationLock = useRef(false);
  const [creatingBackup, setCreatingBackup] = useState(false);
  const [backupCreationResult, setBackupCreationResult] = useState<boolean | null>(null);
  const restoreLock = useRef(false);
  const restoreResultRef = useRef<HTMLDivElement>(null);
  const restoreProgressRef = useRef<HTMLParagraphElement>(null);
  const [restoring, setRestoring] = useState<string | null>(null);
  const [restoreResult, setRestoreResult] = useState<{ filename: string; success: boolean } | null>(null);
  const [loadError, setLoadError] = useState("");
  const [backupError, setBackupError] = useState(false);
  const [loadingBackups, setLoadingBackups] = useState(false);
  const backupListLock = useRef(false);
  const { error: toastError, success } = useToast();
  const { confirm } = useConfirm();
  const { t } = useTranslation();

  const refreshBackups = useCallback(async () => {
    if (backupListLock.current) return;
    backupListLock.current = true;
    setLoadingBackups(true);
    setBackups([]);
    try {
      const result = await api.get<Backup[]>("/api/config/backups");
      if (!Array.isArray(result) || result.some(entry =>
        !entry || typeof entry.filename !== "string" || typeof entry.createdAt !== "string" || typeof entry.size !== "string"
      )) throw new Error("Invalid backup list response.");
      setBackups(result);
      setBackupError(false);
    } catch {
      setBackupError(true);
    } finally {
      setLoadingBackups(false);
      backupListLock.current = false;
    }
  }, []);

  const fetchData = useCallback(async (safeErrorMessage?: string) => {
    try {
      setLoading(true);
      setLoadError("");
      const [resConfig] = await Promise.all([
        api.get<SmbGlobalConfig>("/api/config/global"),
        refreshBackups()
      ]);
      setConfig(resConfig);
    } catch (err) {
      setLoadError(safeErrorMessage || getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [refreshBackups]);

  useEffect(() => { fetchData(); }, [fetchData]);

  useEffect(() => {
    if (!loading && restoreResult) restoreResultRef.current?.scrollIntoView({ block: "center" });
  }, [loading, restoreResult]);

  useEffect(() => {
    if (restoring) restoreProgressRef.current?.scrollIntoView({ block: "center" });
  }, [restoring]);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!config || comparisonLock.current || restoreLock.current || backupCreationLock.current) return;

    try {
      setComparison(null);
      setSaving(true);
      await api.put("/api/config/global", config);
      success("Saved", "Configuration applied. Restart service to apply fully.");
      // Refetch to pull latest backups
      fetchData();
    } catch (err) {
      toastError("Save Error", getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (comparisonLock.current || restoreLock.current || backupCreationLock.current) return;
    const ok = await confirm({
      title: t("config.defaults"),
      message: t("config.resetWarning"),
      destructive: true,
      confirmText: t("config.restore")
    });
    if (!ok) return;

    setConfig({
      workgroup: "WORKGROUP",
      netbiosName: "",
      serverString: "Samba Server",
      security: "user",
      mapToGuest: "never",
      interfaces: "",
      bindInterfacesOnly: false,
      minProtocol: "SMB2",
      maxProtocol: "SMB3",
      disableNetbios: true,
      loadPrinters: false,
      passdbBackend: "tdbsam",
      guestAccount: "nobody",

      realm: "",
      winbindUseDefaultDomain: true,
      idmapDefaultBackend: "tdb",
      idmapDefaultRange: "3000-7999",
      idmapDomainBackend: "rid",
      idmapDomainRange: "10000-999999",
      templateShell: "/bin/bash",
    });
    success("Restored", "Default values set. Save to apply.");
  };

  const handleCompareBackup = async (filename: string) => {
    if (comparisonLock.current || restoreLock.current || backupCreationLock.current || saving) return;
    comparisonLock.current = true;
    setComparing(true);
    setComparison({ filename });
    try {
      const data = await api.get<BackupComparison>(`/api/config/backups/${encodeURIComponent(filename)}/compare`);
      if (!data || data.filename !== filename || typeof data.equal !== "boolean" || !Array.isArray(data.changes)
        || data.changes.length > 2000 || data.equal !== (data.changes.length === 0)
        || !data.changes.every(change => change && ["ADDED", "REMOVED"].includes(change.type)
          && Number.isInteger(change.lineNumber) && change.lineNumber > 0 && typeof change.text === "string")) {
        throw new Error("Invalid backup comparison response.");
      }
      setComparison({ filename, data });
    } catch {
      setComparison({ filename, error: true });
    } finally {
      comparisonLock.current = false;
      setComparing(false);
    }
  };

  const handleCreateBackup = async () => {
    if (comparisonLock.current || backupCreationLock.current || restoreLock.current || saving) return;
    backupCreationLock.current = true;
    setComparison(null);
    setCreatingBackup(true);
    setBackupCreationResult(null);
    try {
      try {
        await api.post("/api/config/backups");
      } catch {
        setBackupCreationResult(false);
        return;
      }
      setBackupCreationResult(true);
      await refreshBackups();
    } finally {
      setCreatingBackup(false);
      backupCreationLock.current = false;
    }
  };

  const handleRestoreBackup = async (filename: string) => {
    if (comparisonLock.current || restoreLock.current || backupCreationLock.current || saving) return;
    restoreLock.current = true;
    try {
      const ok = await confirm({
        title: t("config.restore"),
        message: t("config.restoreWarning").replace("{filename}", filename),
        destructive: true,
        confirmText: t("config.restore")
      });
      if (!ok) return;
      setRestoreResult(null);
      setComparison(null);
      setRestoring(filename);
      try {
        await api.post(`/api/config/backups/${encodeURIComponent(filename)}/restore`);
      } catch {
        setRestoreResult({ filename, success: false });
        return;
      }
      setRestoreResult({ filename, success: true });
      await fetchData(t("config.restoreRefreshError"));
    } finally {
      setRestoring(null);
      restoreLock.current = false;
    }
  };

  if (loading) {
    return (
      <div role="status" aria-busy="true" aria-label={t("sidebar.wait")} className="space-y-6 animate-in fade-in duration-500 max-w-4xl">
         <div className="h-10 w-48 bg-surface-2 animate-pulse rounded-md"></div>
         <div className="h-64 bg-surface border border-border rounded-lg animate-pulse"></div>
      </div>
    );
  }

  if (!config) return (
    <div className="config-page space-y-6">
      <h1 className="text-2xl font-semibold">{t("config.title")}</h1>
      <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p>{t("config.loadError")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>
      <button onClick={() => void fetchData()} className="shares-refresh"><RefreshCw className="h-4 w-4" />{t("dashboard.refresh")}</button>
    </div>
  );

  return (
    <div className="config-page space-y-6 animate-in fade-in duration-500 max-w-4xl pb-10">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4">
        <div><div className="mb-2 flex items-center gap-2 text-xs uppercase tracking-widest text-text-muted"><Settings2 className="h-3.5 w-3.5" />smb.conf</div><h1 className="text-2xl font-semibold text-foreground tracking-tight">{t("config.title")}</h1><p className="mt-2 text-sm text-text-muted">{t("config.subtitle")}</p></div>
        <div className="flex gap-2">
          <button onClick={handleReset} disabled={comparing || !!restoring || saving || creatingBackup} type="button" className="disabled:opacity-50 text-sm font-medium text-foreground bg-surface border border-border-strong px-4 py-2.5 rounded-md hover:bg-surface-2 transition-colors flex items-center gap-2">
            <RotateCcw className="w-4 h-4" /> {t("config.defaults")}
          </button>
        </div>
      </div>
      {loadError && <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p>{t("config.staleData")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}

      <form onSubmit={handleSave} className="config-form space-y-8">
        
        <div className="bg-surface rounded-lg border border-border overflow-hidden">
           <div className="bg-surface-2 px-5 py-3 border-b border-border">
             <h2 className="text-[13px] font-semibold tracking-wide uppercase text-text-muted">{t("config.general")}</h2>
           </div>
           <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <label htmlFor="config-workgroup" className="text-[13px] font-medium text-foreground">{t("config.workgroup")}</label>
                <input 
                  id="config-workgroup" value={config.workgroup} onChange={(e) => setConfig({...config, workgroup: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm font-medium rounded-lg uppercase"
                />
              </div>
              <div className="space-y-2">
                <label htmlFor="config-netbiosName" className="flex flex-col gap-0.5">
                  <span className="text-[13px] font-medium text-foreground">{t("config.netbiosName")}</span>
                </label>
                <input 
                  id="config-netbiosName" value={config.netbiosName} onChange={(e) => setConfig({...config, netbiosName: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm font-medium rounded-lg"
                  placeholder={t("config.autoGenerated")}
                />
              </div>
              <div className="space-y-2 md:col-span-2">
                <label htmlFor="config-serverString" className="text-[13px] font-medium text-foreground">{t("config.serverString")}</label>
                <input 
                  id="config-serverString" value={config.serverString} onChange={(e) => setConfig({...config, serverString: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg"
                />
              </div>
              <div className="md:col-span-2 flex items-center bg-surface-2 p-4 rounded-lg border border-border">
                <Toggle checked={config.loadPrinters} onChange={c => setConfig({ ...config, loadPrinters: c })} label={t("config.loadPrinters")} />
              </div>
           </div>
        </div>

        <div className="bg-surface rounded-lg border border-border overflow-hidden">
           <div className="bg-surface-2 px-5 py-3 border-b border-border">
             <h2 className="text-[13px] font-semibold tracking-wide uppercase text-text-muted">{t("config.network")}</h2>
           </div>
           <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <label htmlFor="config-interfaces" className="text-[13px] font-medium text-foreground">{t("config.interfaces")}</label>
                <input 
                  id="config-interfaces" value={config.interfaces} onChange={(e) => setConfig({...config, interfaces: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm font-mono rounded-lg"
                  placeholder="lo eth0 192.168.1.10"
                />
              </div>
              <div className="flex flex-col gap-4 pt-1">
                <div className="bg-surface-2 p-4 border border-border rounded-lg">
                  <Toggle checked={config.bindInterfacesOnly} onChange={c => setConfig({ ...config, bindInterfacesOnly: c })} label={t("config.bindInterfacesOnly")} />
                </div>
                <div className="bg-surface-2 p-4 border border-border rounded-lg">
                  <Toggle checked={config.disableNetbios} onChange={c => setConfig({ ...config, disableNetbios: c })} label={t("config.disableNetbios")} />
                </div>
              </div>
           </div>
        </div>

        <div className="bg-surface rounded-lg border border-border overflow-hidden">
           <div className="bg-surface-2 px-5 py-3 border-b border-border">
             <h2 className="text-[13px] font-semibold tracking-wide uppercase text-text-muted">{t("config.security")}</h2>
           </div>
           <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-2">
                <label htmlFor="config-security" className="text-[13px] font-medium text-foreground">{t("config.securityModel")}</label>
                <select 
                  id="config-security" value={config.security} onChange={(e) => setConfig({...config, security: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg appearance-none cursor-pointer"
                >
                  <option value="user">User</option>
                  <option value="ads">{t("config.activeDirectory")}</option>
                  <option value="domain">Domain</option>
                </select>
              </div>
              <div className="space-y-2">
                <label htmlFor="config-mapToGuest" className="text-[13px] font-medium text-foreground">{t("config.mapToGuest")}</label>
                <select 
                  id="config-mapToGuest" value={config.mapToGuest} onChange={(e) => setConfig({...config, mapToGuest: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg appearance-none cursor-pointer"
                >
                  <option value="never">{t("config.never")}</option>
                  <option value="bad user">{t("config.badUser")}</option>
                  <option value="bad password">{t("config.badPassword")}</option>
                </select>
              </div>
              <div className="space-y-2">
                <label htmlFor="config-minProtocol" className="text-[13px] font-medium text-foreground">{t("config.minProtocol")}</label>
                <select 
                  id="config-minProtocol" value={config.minProtocol} onChange={(e) => setConfig({...config, minProtocol: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg appearance-none cursor-pointer"
                >
                  <option value="CORE">CORE (Legacy)</option>
                  <option value="NT1">NT1 (SMB1)</option>
                  <option value="SMB2">SMB2</option>
                  <option value="SMB3">SMB3</option>
                </select>
              </div>
              <div className="space-y-2">
                <label htmlFor="config-maxProtocol" className="text-[13px] font-medium text-foreground">{t("config.maxProtocol")}</label>
                <select 
                  id="config-maxProtocol" value={config.maxProtocol} onChange={(e) => setConfig({...config, maxProtocol: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg appearance-none cursor-pointer"
                >
                  <option value="NT1">NT1 (SMB1)</option>
                  <option value="SMB2">SMB2</option>
                  <option value="SMB3">SMB3</option>
                </select>
              </div>
           </div>
        </div>

        
        {config.security === 'ads' && (
        <div className="bg-surface rounded-lg border border-border overflow-hidden animate-fade-in fade-in">
           <div className="bg-surface-2 px-5 py-3 border-b border-border">
             <h2 className="text-[13px] font-semibold tracking-wide uppercase text-text-muted flex items-center gap-2">Idmap / Winbind Configuration</h2>
           </div>
           <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-6">
              
              <div className="space-y-2">
                <label htmlFor="config-realm" className="text-[13px] font-medium text-foreground">Domain Realm (e.g. ad.company.com)</label>
                <input 
                  id="config-realm" value={config.realm} onChange={(e) => setConfig({...config, realm: e.target.value.toUpperCase()})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg uppercase"
                />
              </div>

              <div className="flex items-center bg-surface-2 p-4 rounded-lg border border-border">
                <Toggle checked={config.winbindUseDefaultDomain} onChange={c => setConfig({ ...config, winbindUseDefaultDomain: c })} label="Winbind Use Default Domain" />
              </div>

              <div className="space-y-2">
                <label htmlFor="config-templateShell" className="text-[13px] font-medium text-foreground">Template Shell</label>
                <input 
                  id="config-templateShell" value={config.templateShell} onChange={(e) => setConfig({...config, templateShell: e.target.value})}
                  className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/20 text-foreground transition-all py-2.5 px-3.5 text-sm font-mono rounded-lg"
                />
              </div>
              <div></div>

              <div className="p-4 border border-border rounded-lg bg-surface-2 space-y-4">
                 <h3 className="text-sm font-semibold">Default IDMAP (TDB)</h3>
                 <div className="space-y-2">
                   <label htmlFor="config-idmapDefaultRange" className="text-[12px] font-medium text-text-muted">Range (e.g. 3000-7999)</label>
                   <input 
                     id="config-idmapDefaultRange" value={config.idmapDefaultRange} onChange={(e) => setConfig({...config, idmapDefaultRange: e.target.value})}
                     className="w-full bg-surface border border-border-strong focus:border-accent px-3 py-2 text-sm rounded-md"
                   />
                 </div>
              </div>

              <div className="p-4 border border-border rounded-lg bg-surface-tint text-text-on-tint space-y-4">
                 <h3 className="text-sm font-semibold">Domain IDMAP (RID)</h3>
                 <div className="space-y-2">
                   <label htmlFor="config-idmapDomainRange" className="text-[12px] font-medium text-text-on-tint">Range (e.g. 10000-999999)</label>
                   <input 
                     id="config-idmapDomainRange" value={config.idmapDomainRange} onChange={(e) => setConfig({...config, idmapDomainRange: e.target.value})}
                     className="w-full bg-surface border border-border-strong focus:border-accent text-foreground px-3 py-2 text-sm rounded-md"
                   />
                 </div>
              </div>
           </div>
        </div>
        )}

        {restoring && (
          <p ref={restoreProgressRef} role="status" aria-live="polite" aria-busy="true" className="config-note break-words">
            {t("config.restoring").replace("{filename}", restoring)}
          </p>
        )}
        {restoreResult && (
          <div ref={restoreResultRef} role={restoreResult.success ? "status" : "alert"} className="bg-surface-2 border border-border rounded-lg p-4 break-words">
            <p className="font-semibold">{t(restoreResult.success ? "config.restoreSuccess" : "config.restoreError")}</p>
            <p className="font-mono text-sm mt-2">{restoreResult.filename}</p>
            <p className="text-sm text-text-muted mt-2">{t(restoreResult.success ? "config.restoreSuccessDetail" : "config.restoreErrorDetail")}</p>
          </div>
        )}
        <section aria-labelledby="config-backups-title" className="bg-surface rounded-lg border border-border overflow-hidden">
             <div className="bg-surface-2 px-5 py-3 border-b border-border flex flex-wrap items-center justify-between gap-3">
               <h2 id="config-backups-title" className="text-[13px] font-semibold tracking-wide uppercase text-text-muted">{t("config.backups")}</h2>
               <button type="button" onClick={() => void handleCreateBackup()} disabled={comparing || creatingBackup || !!restoring || saving}
                 className="text-sm font-medium bg-surface border border-border-strong px-4 py-2 rounded-md disabled:opacity-50">
                 {t(creatingBackup ? "config.creatingBackup" : "config.createBackup")}
               </button>
             </div>
             <div className="p-5">
                <p className="text-sm text-text-muted mb-4">{t("config.backupsDesc")}</p>
                <p className="text-sm text-text-muted mb-4">{t("config.backupScope")}</p>
                {backupCreationResult !== null && (
                  <p role={backupCreationResult ? "status" : "alert"} className="config-note mb-4" aria-live="polite">
                    {t(backupCreationResult ? "config.backupCreated" : "config.backupCreationFailed")}
                  </p>
                )}
                {backupError && <div className="mb-4">
                  <p role="status" className="config-note mb-3">{t("config.backupError")}</p>
                  <button type="button" onClick={() => void refreshBackups()}
                    disabled={loadingBackups || creatingBackup || !!restoring || saving}
                    className="disabled:opacity-50 text-sm border border-border-strong rounded-md px-3 py-2">
                    {t(loadingBackups ? "config.loadingBackups" : "config.retryBackups")}
                  </button>
                </div>}
                {!backupError && !loadingBackups && backups.length === 0 && <p className="text-sm text-text-muted">{t("config.noBackups")}</p>}
                <div className="grid grid-cols-1 gap-3 select-none">
                  {!backupError && !loadingBackups && backups.map((bkp) => (
                    <div key={bkp.filename} className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-4 border border-border rounded-md bg-surface-2 hover:border-border-strong transition-colors group">
                      <div className="flex min-w-0 items-center gap-3">
                         <div className="shrink-0 bg-surface border border-border p-2 rounded-md">
                           <Clock className="w-5 h-5 text-text-muted group-hover:text-foreground transition-colors" />
                         </div>
                         <div className="min-w-0">
                            <div className="break-words text-sm font-semibold text-foreground font-mono">{bkp.filename}</div>
                            <div className="text-xs text-text-muted font-medium mt-0.5">{bkp.createdAt} · {bkp.size}</div>
                         </div>
                      </div>
                      <div className="flex flex-wrap gap-2 shrink-0">
                      <button type="button" onClick={() => void handleCompareBackup(bkp.filename)}
                        disabled={comparing || !!restoring || saving || creatingBackup}
                        aria-label={`${t("config.compare")}: ${bkp.filename}`}
                        className="disabled:opacity-50 text-xs border border-border-strong rounded-md px-3 py-2">
                        {t("config.compare")}
                      </button>
                      <button 
                        type="button"
                        disabled={comparing || !!restoring || saving || creatingBackup}
                        onClick={() => handleRestoreBackup(bkp.filename)}
                        aria-label={`${t("config.restore")}: ${bkp.filename}`}
                        className="disabled:opacity-50 text-xs font-medium bg-surface text-foreground hover:bg-surface-2 py-1.5 px-3 rounded-md transition-colors border border-border-strong flex gap-1.5 items-center shrink-0"
                      >
                        <ArrowLeft className="w-3.5 h-3.5" />
                        {t("config.restore")}
                      </button>
                      </div>
                    </div>
                  ))}
                </div>
                {comparison && <div data-comparison className="mt-4 min-w-0 border border-border rounded-md p-4" aria-live="polite" aria-busy={comparing}>
                  <h3 className="font-semibold break-all">{t("config.compare")}: {comparison.filename}</h3>
                  <p className="mt-2 text-sm">{t("config.compareDirection")}</p>
                  <p className="mt-2 text-sm text-text-muted">{t("config.compareSnapshot")}</p>
                  {comparing && <p role="status">{t("config.comparing")}</p>}
                  {comparison.error && <p role="alert" className="config-note mt-3">{t("config.compareError")}</p>}
                  {comparison.data?.equal && <p role="status" className="config-note mt-3">{t("config.compareEqual")}</p>}
                  {comparison.data && !comparison.data.equal && <>
                    <p className="mt-3 text-sm">{t("config.compareLegend")}</p>
                    <div className="mt-2 max-h-96 overflow-y-auto text-sm font-mono select-text">
                      {comparison.data.changes.map((change, index) => <div key={index} className="border-b border-border py-1">
                        <pre className="whitespace-pre-wrap [overflow-wrap:anywhere]">{change.type === "ADDED" ? "+" : "−"} {change.lineNumber}: {change.text}</pre>
                        {!change.text.endsWith("\n") && <p className="text-text-muted">{t("config.compareNoNewline")}</p>}
                      </div>)}
                    </div>
                  </>}
                </div>}
             </div>
        </section>

        <div className="config-save-bar flex flex-wrap items-center justify-between gap-4 pt-4 border-t border-border">
          <p className="text-xs text-text-muted">{t("config.saveHint")}</p>
          <button 
            type="submit" 
            disabled={comparing || saving || !!restoring || creatingBackup}
            className="text-sm font-medium text-on-accent bg-accent px-6 py-2.5 rounded-md hover:opacity-90 transition-opacity flex items-center gap-2 disabled:opacity-50"
          >
            <Save className="w-4 h-4" /> {saving ? t("config.saving") : t("config.applyConfig")}
          </button>
        </div>
      </form>
    </div>
  );
}
