import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback, useRef } from "react";
import { Folder, ArrowUp, FolderPlus, ChevronRight, FileText } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslation } from "../../lib/i18n";
import { useToast } from "./toast";
import { Modal } from "./modal";

interface DirectoryItem { name: string; fullPath: string; type?: string; size?: number; }
interface DirectoryPreparation { owner: string; group: string; mode: string; confirmed: boolean; }
interface DirectoryCreation { parentPath: string; dirName: string; preparation?: DirectoryPreparation; }
interface BrowseResult { currentPath: string; parentPath: string | null; directories: DirectoryItem[]; }

interface FilePickerProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (path: string) => void;
  initialPath?: string;
  pickerType?: 'folder' | 'file' | 'any';
}

export function FilePickerModal({ isOpen, onClose, onSelect, initialPath = "/", pickerType = 'folder' }: FilePickerProps) {
  const [currentPath, setCurrentPath] = useState(initialPath || "/");
  const [browseData, setBrowseData] = useState<BrowseResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [isCreatingDir, setIsCreatingDir] = useState(false);
  const [newDirName, setNewDirName] = useState("");
  const [preparePrivate, setPreparePrivate] = useState(false);
  const [owner, setOwner] = useState("");
  const [group, setGroup] = useState("");
  const [creating, setCreating] = useState(false);
  const [directoryResult, setDirectoryResult] = useState<{ error: boolean; message: string } | null>(null);
  const creatingRef = useRef(false);
  const [review, setReview] = useState<Required<DirectoryCreation> | null>(null);
  
  const { error: toastError } = useToast();
  const { t } = useTranslation();
  const translate = useRef(t);
  useEffect(() => { translate.current = t; }, [t]);

  const fetchFiles = useCallback(async (path: string) => {
    setLoading(true);
    try {
      const res = await api.get<BrowseResult>(`/api/fs/browse?path=${encodeURIComponent(path)}`);
      setBrowseData(res);
      setCurrentPath(res.currentPath);
    } catch (err) {
      toastError(translate.current("files.error"), getErrorMessage(err));
      if (path !== "/") {
        fetchFiles("/");
      }
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    if (isOpen) {
      const target = (initialPath && initialPath.trim()) ? initialPath.trim() : "/";
      setIsCreatingDir(false);
      setPreparePrivate(false);
      setOwner("");
      setGroup("");
      setReview(null);
      setDirectoryResult(null);
      setCurrentPath(target);
      fetchFiles(target);
    }
  }, [isOpen, initialPath, fetchFiles]);

  const navigateTo = (path: string) => {
    setCurrentPath(path);
    fetchFiles(path);
  };

  const submitDirectory = async (request: DirectoryCreation) => {
    if (creatingRef.current) return;
    creatingRef.current = true;
    setCreating(true);
    setDirectoryResult(null);
    setReview(null);
    try {
      await api.post("/api/fs/mkdir", request);
      setDirectoryResult({ error: false, message: `${t("files.folderCreated")}: ${request.dirName}` });
      setIsCreatingDir(false);
      setNewDirName("");
      setPreparePrivate(false);
      setOwner("");
      setGroup("");
      fetchFiles(request.parentPath);
    } catch (err) {
      setDirectoryResult({ error: true, message: getErrorMessage(err) });
    } finally {
      creatingRef.current = false;
      setCreating(false);
    }
  };

  const handleCreateDirectory = (e: React.FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!newDirName || creatingRef.current || review) return;
    if (preparePrivate) {
      if (!owner.trim() || !group.trim()) return;
      setReview({ parentPath: currentPath, dirName: newDirName, preparation: { owner: owner.trim(), group: group.trim(), mode: "0700", confirmed: true } });
    } else {
      void submitDirectory({ parentPath: currentPath, dirName: newDirName });
    }
  };

  return (
    <><Modal isOpen={isOpen} onClose={() => { if (!creatingRef.current) onClose(); }} title={t("dashboard.storageExplorer")} maxWidth="3xl" zIndex={60}>
      <fieldset disabled={creating} className="min-w-0 bg-surface rounded-lg border border-border flex flex-col overflow-y-auto max-h-[70vh]">
        
        {/* Header / Toolbar */}
        <div className="px-4 py-3 border-b border-border flex flex-col gap-3 bg-surface-2">
          <div className="flex flex-wrap items-center gap-1.5 text-[12px] font-mono shrink-0">
            <button 
              type="button" 
              onClick={() => navigateTo("/")} 
              className="text-foreground hover:text-accent-text px-1.5 py-0.5 rounded transition-colors hover:bg-surface shrink-0"
            >
              {t("common.root")}
            </button>
            {browseData?.currentPath.split("/").filter(Boolean).map((part, index, array) => {
              const p = "/" + array.slice(0, index + 1).join("/");
              return (
                <div key={p} className="flex items-center gap-1 shrink-0 overflow-hidden">
                  <ChevronRight className="w-3.5 h-3.5 text-text-muted shrink-0" />
                  <button 
                    type="button" 
                    onClick={() => navigateTo(p)} 
                    className="text-foreground hover:text-accent-text px-1.5 py-0.5 rounded transition-colors hover:bg-surface truncate"
                  >
                    {part}
                  </button>
                </div>
              );
            })}
          </div>

          <div className="flex justify-between items-center shrink-0">
             <div className="flex gap-2">
              {!isCreatingDir ? (
                <button 
                  type="button"
                  onClick={() => setIsCreatingDir(true)} 
                  className="text-[12px] font-medium text-foreground bg-surface border border-border-strong px-3 py-1.5 rounded-md hover:bg-surface-2 transition-colors flex items-center gap-1.5"
                >
                  <FolderPlus className="w-3.5 h-3.5" /> {t("dashboard.mkdir")}
                </button>
              ) : (
                <fieldset disabled={creating} className="flex flex-wrap gap-2 isolate min-w-0">
                  <input 
                    autoFocus required pattern="[a-zA-Z0-9_.-]+"
                    className="text-xs font-mono border border-border-strong bg-surface px-3 py-1.5 rounded-md focus:outline-none focus:border-accent w-40 text-foreground"
                    placeholder={t("dashboard.dirname")}
                    aria-label={t("dashboard.dirname")}
                    value={newDirName}
                    onChange={e => setNewDirName(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        e.stopPropagation();
                        handleCreateDirectory(e);
                      } else if (e.key === 'Escape') {
                        e.preventDefault();
                        e.stopPropagation();
                        setIsCreatingDir(false);
                      }
                    }}
                  />
                  <button 
                    type="button" 
                    onClick={handleCreateDirectory} 
                    className="text-xs font-medium text-on-accent bg-accent px-3 py-1.5 rounded-md hover:opacity-90 transition-colors"
                  >
                    {t("common.save")}
                  </button>
                  <button 
                    type="button" 
                    onClick={() => setIsCreatingDir(false)} 
                    className="text-xs font-medium text-foreground bg-surface border border-border-strong px-3 py-1.5 rounded-md hover:bg-surface-2 transition-colors"
                  >
                    {t("common.cancel")}
                  </button>
                  <div className="w-full space-y-2 text-xs text-foreground">
                    <label className="flex items-center gap-2"><input type="checkbox" checked={preparePrivate} onChange={e => setPreparePrivate(e.target.checked)} />{t("files.privateChoice")}</label>
                    {preparePrivate && <>
                      <p className="text-text-muted max-w-lg">{t("files.privateHint")}</p>
                      <label className="block">{t("files.localOwner")}<input className="block w-full bg-surface border border-border rounded p-2" value={owner} onChange={e => setOwner(e.target.value)} /></label>
                      <label className="block">{t("files.localGroup")}<input className="block w-full bg-surface border border-border rounded p-2" value={group} onChange={e => setGroup(e.target.value)} /></label>
                      <p>0700 — {t("files.privateMode")}</p>
                    </>}
                  </div>
                </fieldset>
              )}
             </div>
          </div>
          {directoryResult && <p role={directoryResult.error ? "alert" : "status"} className="text-sm break-words text-foreground border border-border rounded p-2">{directoryResult.message}</p>}
        </div>

        {/* Content List */}
        <div className="flex-1 overflow-y-auto bg-surface relative custom-scrollbar min-h-[300px]">
           {loading ? (
             <div className="absolute inset-0 flex justify-center items-center backdrop-blur-[1px] bg-bg/20 z-10">
               <div className="w-5 h-5 border-2 border-border border-t-foreground rounded-full animate-spin"></div>
             </div>
           ) : null}
           
           <div className="flex flex-col divide-y divide-border">
             {browseData?.parentPath && browseData.parentPath !== browseData.currentPath && (
               <button 
                 type="button"
                 onClick={() => navigateTo(browseData.parentPath!)}
                 className="flex items-center gap-3 px-4 py-2.5 hover:bg-surface-2 text-left transition-colors group"
               >
                 <ArrowUp className="w-4 h-4 text-text-muted group-hover:text-foreground transition-colors shrink-0" />
                 <span className="text-[13px] font-mono font-medium text-foreground">..</span>
               </button>
             )}
             
             {browseData?.directories.map(item => {
               const isFolder = item.type === 'dir' || item.type === undefined;
               const canSelect = pickerType === 'any' || (pickerType === 'folder' && isFolder) || (pickerType === 'file' && !isFolder);
               
               return (
                 <div key={item.fullPath} className="flex items-center justify-between hover:bg-surface-2 transition-colors group px-3 py-1">
                   <button 
                     type="button"
                     onClick={() => isFolder ? navigateTo(item.fullPath) : (canSelect ? onSelect(item.fullPath) : null)}
                     className={`flex-1 flex items-center gap-3 py-2 text-left ${isFolder ? 'cursor-pointer' : (canSelect ? 'cursor-pointer' : 'cursor-default')}`}
                   >
                     {isFolder ? <Folder className="w-4 h-4 text-accent-text shrink-0" /> : <FileText className="w-4 h-4 text-text-muted shrink-0" />}
                     <span className="text-[13px] font-mono font-medium text-foreground truncate">{item.name}</span>
                     {!isFolder && item.size !== undefined && (
                        <span className="text-[11px] text-text-muted font-mono ml-auto mr-4">{item.size} B</span>
                     )}
                   </button>
                   {canSelect && (
                     <button
                       type="button"
                       onClick={() => onSelect(item.fullPath)}
                       className="text-xs px-2.5 py-1 rounded bg-surface border border-border text-text-muted hover:text-foreground hover:bg-surface-2 transition-colors ml-2 shrink-0 font-medium"
                       title={t("common.select")}
                     >
                       {t("common.select")}
                     </button>
                   )}
                 </div>
               );
             })}
             
             {(!browseData?.directories || browseData.directories.length === 0) && !loading && (
               <div className="text-center text-[13px] text-text-muted font-mono py-16">
                 {t("dashboard.dirEmpty")}
               </div>
             )}
           </div>
        </div>
        
        {/* Footer Actions */}
        <div className="px-4 py-3 border-t border-border flex justify-between items-center bg-surface w-full">
           <div className="text-[12px] font-mono text-text-muted truncate max-w-[50%] select-none px-2 py-1 rounded bg-surface-2 border border-border">
              {currentPath}
           </div>
           <div className="flex gap-2">
              <button 
                type="button"
                onClick={onClose} 
                className="text-[12px] font-medium text-foreground bg-surface hover:bg-surface-2 px-4 py-2 border border-border-strong rounded-md transition-colors"
              >
                {t("common.cancel")}
              </button>
              {pickerType !== 'file' && (
                <button 
                  type="button"
                  onClick={() => onSelect(currentPath)} 
                  className="text-[12px] font-medium text-on-accent bg-accent hover:opacity-90 px-4 py-2 rounded-md transition-colors"
                >
                  {t("common.select")}
                </button>
              )}
           </div>
        </div>
      </fieldset>
    </Modal>
    <Modal isOpen={review !== null} onClose={() => setReview(null)} title={t("files.privateReview")} zIndex={70}>
      {review && <div className="space-y-4 break-words">
        <p className="font-mono">{review.parentPath.replace(/\/$/, "")}/{review.dirName}</p>
        <p className="font-mono">{review.preparation.owner}:{review.preparation.group} — 0700</p>
        <p>{t("files.privateConsequences")}</p>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" onClick={() => setReview(null)} className="px-3 py-2 border border-border rounded">{t("common.cancel")}</button>
          <button type="button" onClick={() => void submitDirectory(review)} className="px-3 py-2 bg-accent text-on-accent rounded">{t("files.privateCreate")}</button>
        </div>
      </div>}
    </Modal></>
  );
}
