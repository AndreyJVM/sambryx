import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback } from "react";
import { FolderKanban, HardDrive, Plus, Trash2, Settings2, ChevronDown, ChevronUp, Search, RefreshCw, AlertTriangle } from "lucide-react";
import { api } from "../../lib/api";
import { useToast } from "../../components/ui/toast";
import { useConfirm } from "../../components/ui/confirm";
import { Modal } from "../../components/ui/modal";
import { FilePickerModal } from "../../components/ui/file-picker";
import { Toggle } from "../../components/ui/toggle";
import { AutocompleteInput } from "../../components/ui/autocomplete";
import { MultiSelect } from "../../components/ui/multi-select";
import { useTranslation } from "../../lib/i18n";

interface Share {
  name: string;
  path: string;
  comment?: string;
  readOnly: boolean;
  guestOk: boolean;
  browseable: boolean;
  validUsers?: string;
  writeList?: string;
  createMask?: string;
  directoryMask?: string;
  forceUser?: string;
  forceGroup?: string;
  maxConnections?: string;
  hostsAllow?: string;
  hostsDeny?: string;
  isNew?: boolean;
}

type AdvancedShareField = {
  l: string;
  k: "writeList" | "maxConnections" | "createMask" | "directoryMask" | "forceUser" | "forceGroup" | "hostsAllow" | "hostsDeny";
  p: string;
  mult?: boolean;
  sug?: string[];
};

interface User { username: string; }
interface Group { name: string; }

export default function SharesPage() {
  const [shares, setShares] = useState<Share[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [systemUsers, setSystemUsers] = useState<string[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [suggestionsUnavailable, setSuggestionsUnavailable] = useState(false);
  
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [query, setQuery] = useState("");

  const [sizes, setSizes] = useState<Record<string, string>>({});
  const [loadingSizes, setLoadingSizes] = useState<Record<string, boolean>>({});

  const fetchSize = async (sharename: string) => {
    setLoadingSizes(prev => ({ ...prev, [sharename]: true }));
    try {
      const res = await api.get<string>(`/api/shares/${sharename}/size`);
      setSizes(prev => ({ ...prev, [sharename]: res as string }));
    } catch (err) {
      toastError("Size Error", getErrorMessage(err));
    } finally {
      setLoadingSizes(prev => ({ ...prev, [sharename]: false }));
    }
  };

  const [isEditing, setIsEditing] = useState(false);
  const [isPickerOpen, setIsPickerOpen] = useState(false);
  const [currentShare, setCurrentShare] = useState<Partial<Share> | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  
  const { error: toastError, success } = useToast();
  const { confirm } = useConfirm();
  const { t } = useTranslation();

  const usernames = [...new Set([...users.map(user => user.username), ...systemUsers])].sort();
  const accessOptions = [...usernames, ...groups.map(group => '@' + group.name)];
  const sambaUsernames = new Set(users.map(user => user.username));
  const optionDescriptions = Object.fromEntries(accessOptions.map(name => [name,
    name.startsWith('@') ? t("shares.systemGroup") : sambaUsernames.has(name) ? t("shares.sambaAccount") : t("shares.osAccount")
  ]));

  const advancedFields: AdvancedShareField[] = [
    { l: t("shares.writeList"), k: "writeList", p: "@admins, user1", mult: true, sug: accessOptions },
    { l: t("shares.maxConn"), k: "maxConnections", p: "0" },
    { l: t("shares.forceUser"), k: "forceUser", p: "user1", mult: false, sug: usernames },
    { l: t("shares.forceGroup"), k: "forceGroup", p: "users", mult: false, sug: groups.map(g => g.name) },
    { l: t("shares.hostsAllow"), k: "hostsAllow", p: "192.168.1." },
    { l: t("shares.hostsDeny"), k: "hostsDeny", p: "ALL" },
  ];

  const fetchAll = useCallback(async () => {
    try {
      setLoading(true);
      setLoadError("");
      setSuggestionsUnavailable(false);
      const unavailable = () => { setSuggestionsUnavailable(true); return []; };
      const [shRes, usRes, grRes, sysRes] = await Promise.all([
        api.get<Share[]>("/api/shares"),
        api.get<User[]>("/api/users").catch(unavailable),
        api.get<Group[]>("/api/groups").catch(unavailable),
        api.get<string[]>("/api/users/system").catch(unavailable)
      ]);
      setShares(shRes || []);
      setUsers(usRes || []);
      setSystemUsers(sysRes || []);
      setGroups(grRes || []);
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const handleDelete = async (name: string) => {
    const ok = await confirm({
      title: t("common.delete"),
      message: `${t("common.confirm")} [${name}]?`,
      destructive: true,
      confirmText: t("common.delete")
    });
    if (!ok) return;

    try {
      await api.delete(`/api/shares/${name}`);
      success(t("common.delete"), `Share ${name} removed`);
      fetchAll();
    } catch (err) {
      toastError("Delete Error", getErrorMessage(err));
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!currentShare?.name || !currentShare?.path) return;

    const isNew = currentShare.isNew === true;
    const url = isNew ? "/api/shares" : `/api/shares/${currentShare.name}`;

    try {
      const dto = {
        name: currentShare.name,
        path: currentShare.path,
        comment: currentShare.comment || "",
        readOnly: currentShare.readOnly,
        browseable: currentShare.browseable,
        guestOk: currentShare.guestOk,
        validUsers: currentShare.validUsers || "",
        writeList: currentShare.writeList || "",
        createMask: currentShare.createMask || "",
        directoryMask: currentShare.directoryMask || "",
        forceUser: currentShare.forceUser || "",
        forceGroup: currentShare.forceGroup || "",
        maxConnections: currentShare.maxConnections || "",
        hostsAllow: currentShare.hostsAllow || "",
        hostsDeny: currentShare.hostsDeny || ""
      };

      if (isNew) {
        await api.post(url, dto);
      } else {
        await api.put(url, dto);
      }
      
      success(isNew ? t("common.create") : t("common.save"), "Configuration saved");
      setIsEditing(false);
      fetchAll();
    } catch (err) {
      toastError("Save Error", getErrorMessage(err));
    }
  };

  const openEditor = (share?: Share) => {
    if (share) {
      setCurrentShare({ ...share, isNew: false });
    } else {
      setCurrentShare({
        name: "",
        path: "",
        browseable: true,
        readOnly: true,
        guestOk: false,
        isNew: true
      });
    }
    setShowAdvanced(false);
    setIsEditing(true);
  };

  const resources = shares.filter(share => share.name !== "global");
  const search = query.trim().toLocaleLowerCase();
  const visibleShares = resources.filter(share =>
    [share.name, share.path, share.comment || ""].some(value => value.toLocaleLowerCase().includes(search))
  );

  return (
    <div className="shares-page space-y-6 animate-in fade-in duration-500 pb-10">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4">
        <div>
          <div className="mb-2 flex items-center gap-2 text-xs font-medium uppercase tracking-widest text-text-muted"><FolderKanban className="h-3.5 w-3.5" />Samba · SMB</div>
          <h1 className="text-2xl font-semibold text-foreground tracking-tight">{t("shares.title")}</h1>
          <p className="mt-2 text-sm text-text-muted">{t("shares.subtitle")}</p>
        </div>
        <button onClick={() => openEditor()} className="shares-create text-[13px] font-medium text-on-accent bg-accent px-4 py-2 rounded-md hover:opacity-90 transition-colors flex items-center gap-2">
          <Plus className="w-4 h-4" /> {t("shares.newShare")}
        </button>
      </div>

      <div className="shares-toolbar">
        <label className="shares-search">
          <Search aria-hidden="true" className="h-4 w-4 shrink-0 text-text-muted" />
          <input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder={t("shares.search")} aria-label={t("shares.search")} />
        </label>
        <span className="text-xs text-text-muted" role="status">{loading ? t("sidebar.wait") : `${t("shares.shown")}: ${visibleShares.length} / ${resources.length}`}</span>
        <button data-session-activity onClick={() => void fetchAll()} disabled={loading} className="shares-refresh"><RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />{t("dashboard.refresh")}</button>
      </div>

      {loadError && <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p className="font-medium">{t(resources.length ? "shares.staleData" : "shares.loadError")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}

      {loading ? (
        <div className="flex flex-col gap-3" role="status" aria-label={t("sidebar.wait")} aria-busy="true">
          {[1,2,3].map(i => (
             <div key={i} className="h-20 bg-surface border border-border rounded-lg animate-pulse" />
          ))}
        </div>
      ) : resources.length === 0 && !loadError ? (
        <div className="text-center py-24 bg-surface rounded-lg border border-border border-dashed">
          <FolderKanban className="w-12 h-12 text-text-muted mx-auto mb-4 opacity-50" />
          <h3 className="text-base font-semibold text-foreground">{t("shares.noShares")}</h3>
          <p className="text-[13px] text-text-muted mt-1 mb-6">{t("shares.noSharesDesc")}</p>
          <button onClick={() => openEditor()} className="text-[13px] font-medium text-foreground bg-surface border border-border-strong px-4 py-2 rounded-md hover:bg-surface-2 transition-colors">
            {t("shares.createShare")}
          </button>
        </div>
      ) : visibleShares.length === 0 && resources.length > 0 ? (
        <div className="shares-empty"><Search className="h-8 w-8 text-text-muted" /><h2>{t("shares.noMatches")}</h2><p>{t("shares.noMatchesHint")}</p><button onClick={() => setQuery("")} className="shares-refresh">{t("shares.clearSearch")}</button></div>
      ) : visibleShares.length > 0 ? (
        <div className="shares-list bg-surface rounded-lg border border-border overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead className="bg-surface-2 text-[11px] font-medium text-text-muted uppercase">
              <tr>
                <th className="py-3 px-5 w-[25%] font-medium">{t("shares.shareName")}</th>
                <th className="py-3 px-5 w-[35%] font-medium">{t("shares.diskPath")}</th>
                <th className="py-3 px-5 w-[25%] font-medium">{t("shares.access")}</th>
                <th className="py-3 px-5 text-right w-[15%] font-medium">{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {visibleShares.map((share) => (
              <tr key={share.name} className="hover:bg-surface-2 transition-colors group">
                  <td data-label={t("shares.shareName")} className="py-4 px-5">
                    <div className="flex items-center gap-2.5">
                       <div className="bg-surface-2 border border-border p-1.5 rounded-md">
                         <FolderKanban className="w-4 h-4 text-foreground" />
                       </div>
                       <span className="shares-name text-foreground font-semibold text-sm">{share.name}</span>
                    </div>
                    {share.comment && <div className="text-[12px] text-text-muted mt-1.5 truncate max-w-[220px]" title={share.comment}>{share.comment}</div>}
                  </td>
                  
                  <td data-label={t("shares.diskPath")} className="py-4 px-5">
                    <div className="flex flex-col gap-2 relative group-size">
                      <span className="shares-path text-[13px] font-mono text-text-muted bg-surface-2 px-2 py-1 rounded-md border border-border inline-block w-fit" title={share.path}>
                        {share.path || '/'}
                      </span>
                      <div className="flex items-center gap-2 mt-1 min-h-[20px]">
                        {!sizes[share.name] ? (
                            <button onClick={() => fetchSize(share.name)} disabled={loadingSizes[share.name]} className="text-[11px] text-text-muted hover:text-accent-text flex items-center gap-1.5 transition-colors">
                              <HardDrive className={`w-3.5 h-3.5 ${loadingSizes[share.name] ? 'animate-pulse' : ''}`} />
                              {loadingSizes[share.name] ? t('shares.calculating') : t('shares.calcSize')}
                            </button>
                        ) : (
                            <span className="flex items-center gap-1.5 text-[11px] font-semibold text-text-on-tint bg-surface-tint px-2 py-0.5 rounded border border-border">
                              <HardDrive className="w-3.5 h-3.5" />
                              {sizes[share.name]}
                            </span>
                        )}
                      </div>
                    </div>
                  </td>

                  <td data-label={t("shares.access")} className="py-4 px-5">
                    <div className="flex flex-wrap gap-2">
                      <span className="shares-badge">{t(share.readOnly ? "shares.readOnly" : "shares.writeAccess")}</span>
                      <span className={`shares-badge ${share.guestOk ? "shares-badge-guest" : ""}`}>{t(share.guestOk ? "shares.guestOk" : "shares.private")}</span>
                    </div>
                  </td>
                  <td data-label={t("common.actions")} className="shares-actions py-4 px-5 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      <button onClick={() => openEditor(share)} className="p-2 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors border border-border/60 hover:border-border" title={t("shares.configure")} aria-label={`${t("shares.configure")}: ${share.name}`}>
                        <Settings2 className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleDelete(share.name)} className="p-2 text-text-muted hover:text-status-error hover:bg-status-error/10 rounded-md transition-colors border border-border/60 hover:border-border" title={t("common.delete")} aria-label={`${t("common.delete")}: ${share.name}`}>
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {currentShare && (
        <Modal 
          isOpen={isEditing} 
          onClose={() => setIsEditing(false)}
          title={currentShare.isNew ? t("shares.newShare") : `${t("shares.configure")}: ${currentShare.name}`}
          maxWidth="2xl"
        >
          <form onSubmit={handleSave} className="share-editor space-y-6">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
              <div className="space-y-2">
                <label htmlFor="share-name" className="text-[12px] font-medium text-foreground">{t("shares.shareName")}</label>
                <input 
                  id="share-name"
                  required
                  value={currentShare.name} 
                  disabled={!currentShare.isNew}
                  onChange={(e) => setCurrentShare({ ...currentShare, name: e.target.value })} 
                  className={`w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-[13px] rounded-lg ${!currentShare.isNew ? 'opacity-60 cursor-not-allowed' : ''}`}
                />
              </div>
              <div className="space-y-2">
                <label htmlFor="share-path" className="text-[12px] font-medium text-foreground">{t("shares.diskPath")}</label>
                <div className="share-path-field flex gap-2">
                  <input 
                    id="share-path"
                    required
                    value={currentShare.path || ""} 
                    onChange={(e) => setCurrentShare({ ...currentShare, path: e.target.value })} 
                    className="flex-1 bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-[13px] rounded-lg font-mono"
                    placeholder="/mnt/disk1/data"
                  />
                  <button type="button" onClick={() => setIsPickerOpen(true)} className="px-4 py-2 bg-surface-2 hover:bg-surface border border-border-strong rounded-lg text-[13px] font-medium text-foreground transition-colors">
                     {t("common.browse") || "Browse..."}
                  </button>
                </div>
              </div>
            </div>

            <div className="space-y-2">
              <label htmlFor="share-comment" className="text-[12px] font-medium text-foreground">{t("shares.comment")}</label>
              <input 
                id="share-comment"
                value={currentShare.comment || ""} 
                onChange={(e) => setCurrentShare({ ...currentShare, comment: e.target.value })} 
                className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-[13px] rounded-lg"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-2">
               <div className="bg-surface-2 p-4 rounded-lg border border-border">
                 <Toggle checked={currentShare.browseable || false} onChange={c => setCurrentShare({ ...currentShare, browseable: c })} label={t("shares.browseable")} />
               </div>
               <div className="bg-surface-2 p-4 rounded-lg border border-border">
                 <Toggle checked={currentShare.readOnly || false} onChange={c => setCurrentShare({ ...currentShare, readOnly: c })} label={t("shares.readOnly")} />
               </div>
               <div className="bg-surface-2 p-4 rounded-lg border border-border">
                 <Toggle checked={currentShare.guestOk || false} onChange={c => setCurrentShare({ ...currentShare, guestOk: c })} label={t("shares.guestOk")} />
               </div>
            </div>

            <div className="space-y-3 pt-4 border-t border-border">
              <div className="flex flex-col gap-1">
                <label htmlFor="share-valid-users" className="text-[12px] font-medium text-foreground">{t("shares.validUsers")}</label>
                <p className="text-[12px] text-text-muted">{t("shares.validUsersHint")}</p>
              </div>
              
              <MultiSelect 
                inputId="share-valid-users"
                options={accessOptions}
                optionDescriptions={optionDescriptions}
                value={currentShare.validUsers || ""} 
                onChange={(val: string) => setCurrentShare({ ...currentShare, validUsers: val })} 
                placeholder="user1, user2, @admins"
              />
              {suggestionsUnavailable && <p role="status" className="text-[12px] text-status-warning">{t("shares.suggestionsUnavailable")}</p>}
            </div>

            <fieldset className="space-y-4 pt-4 border-t border-border">
              <legend className="text-[13px] font-medium text-foreground">{t("shares.permissionPresets")}</legend>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {([
                  { key: "createMask", label: "shares.createMask", hint: "shares.fileMaskHint", presets: ["0600", "0644", "0660", "0666"] },
                  { key: "directoryMask", label: "shares.dirMask", hint: "shares.directoryMaskHint", presets: ["0700", "0755", "0770", "0777"] }
                ] as const).map(field => (
                  <div key={field.key} className="space-y-2">
                    <label htmlFor={`share-${field.key}`} className="text-[12px] font-medium text-foreground">{t(field.label)}</label>
                    <input
                      id={`share-${field.key}`}
                      value={currentShare[field.key] || ""}
                      onChange={event => setCurrentShare({ ...currentShare, [field.key]: event.target.value })}
                      pattern="[0-7]{3,4}"
                      maxLength={4}
                      placeholder={t("shares.defaultMask")}
                      aria-describedby={`share-${field.key}-hint`}
                      className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none py-2 px-3 text-[13px] font-mono rounded-lg text-foreground"
                    />
                    <div className="flex flex-wrap gap-2" role="group" aria-label={t(field.label)}>
                      {field.presets.map(mask => (
                        <button key={mask} type="button" aria-pressed={currentShare[field.key] === mask}
                          onClick={() => setCurrentShare({ ...currentShare, [field.key]: mask })}
                          className={`px-2 py-1 rounded-md border text-[12px] font-mono ${currentShare[field.key] === mask ? "border-accent text-accent-text bg-surface-tint" : "border-border text-text-muted hover:text-foreground"}`}>
                          {mask}
                        </button>
                      ))}
                    </div>
                    <p id={`share-${field.key}-hint`} className="text-[12px] text-text-muted">{t(field.hint)}</p>
                  </div>
                ))}
              </div>
              {(currentShare.directoryMask === "0777" || currentShare.directoryMask === "777") &&
                <p role="status" className="text-[12px] text-status-warning">{t("shares.worldWritableWarning")}</p>}
            </fieldset>

            <div className="pt-2">
              <button 
                type="button"
                onClick={() => setShowAdvanced(!showAdvanced)}
                aria-expanded={showAdvanced}
                aria-controls="share-advanced-options"
                className="w-full flex items-center justify-between text-[13px] font-medium text-foreground py-3 border-b border-border hover:bg-surface-2 transition-colors rounded-t-lg px-2"
              >
                <div className="flex items-center gap-2">
                  <Settings2 className="w-4 h-4 text-text-muted" /> {t("shares.advOptions")}
                </div>
                {showAdvanced ? <ChevronUp className="w-4 h-4 text-text-muted" /> : <ChevronDown className="w-4 h-4 text-text-muted" />}
              </button>
              
              {showAdvanced && (
                 <div id="share-advanced-options" className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-4 animate-in slide-in-from-top-2 duration-300">
                    {advancedFields.map(field => (
                        <div key={field.k} className="flex flex-col gap-1.5 overflow-visible">
                          <label htmlFor={`share-${field.k}`} className="text-[11px] font-semibold text-text-muted uppercase" title={field.l}>{field.l}</label>
                          {field.sug ? (
                            field.mult ? (
                              <MultiSelect 
                                inputId={`share-${field.k}`}
                                optionDescriptions={optionDescriptions}
                                options={field.sug}
                                value={currentShare[field.k] || ""}
                                onChange={(val: string) => setCurrentShare({ ...currentShare, [field.k]: val })} 
                                placeholder={field.p}
                              />
                            ) : (
                              <div className="w-full bg-surface border border-border-strong rounded-md flex items-center">
                                <AutocompleteInput 
                                  id={`share-${field.k}`}
                                  suggestions={field.sug}
                                  value={currentShare[field.k] || ""}
                                  onValueChange={(value) => setCurrentShare({ ...currentShare, [field.k]: value })}
                                  className="w-full text-[12px] font-mono rounded-md !py-1.5 !px-2.5 text-foreground"
                                  placeholder={field.p}
                                />
                              </div>
                            )
                          ) : (
                            <input 
                              id={`share-${field.k}`}
                              value={currentShare[field.k] || ""}
                              onChange={(e) => setCurrentShare({ ...currentShare, [field.k]: e.target.value })} 
                              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none transition-all py-1.5 px-2.5 text-[12px] font-mono rounded-md text-foreground"
                              placeholder={field.p}
                            />
                          )}
                        </div>
                      ))}
                 </div>
              )}
            </div>

            <div className="pt-6 flex justify-end gap-3 border-t border-border">
               <button type="button" onClick={() => setIsEditing(false)} className="text-[13px] font-medium text-foreground bg-surface border border-border-strong px-5 py-2.5 rounded-md hover:bg-surface-2 transition-colors">
                 {t("common.cancel")}
               </button>
               <button type="submit" className="text-[13px] font-medium text-on-accent bg-accent px-6 py-2.5 rounded-md hover:opacity-90 transition-colors">
                 {t("common.save")}
               </button>
            </div>
          </form>
        </Modal>
      )}

      <FilePickerModal 
        isOpen={isPickerOpen} 
        onClose={() => setIsPickerOpen(false)} 
        onSelect={(path) => { 
          if (currentShare) {
            setCurrentShare({ ...currentShare, path }); 
          }
          setIsPickerOpen(false); 
        }} 
        initialPath={currentShare?.path || "/"} 
        pickerType="folder" 
      />
    </div>
  );
}
