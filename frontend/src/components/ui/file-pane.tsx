import React, { useState } from "react";
import { Folder, ArrowUp, FolderPlus, ChevronRight, File as FileIcon, Upload, Download, MoveRight, Copy, Trash2, CheckSquare, Square } from "lucide-react";
import { useTranslation } from "../../lib/i18n";

export interface DirectoryItem {
  name: string;
  fullPath: string;
  type?: string;
  size?: number;
}

export interface BrowseResult {
  currentPath: string;
  parentPath: string | null;
  directories: DirectoryItem[];
}

interface FilePaneProps {
  path: string;
  onNavigate: (path: string) => void;
  browseData: BrowseResult | null;
  loading: boolean;
  selectedItems: Set<string>;
  onToggleSelect: (path: string) => void;
  onSelectAll: () => void;
  onCreateDir: (name: string) => void;
  onUploadFile: (file: File) => void;
  onDownloadFile?: (path: string, filename?: string) => void;
  onDrop: (e: React.DragEvent) => void;
  otherPanePath: string;
  onAction: (action: 'copy' | 'move' | 'delete' | 'download') => void;
  title: string;
}

function formatSize(bytes: number) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

export function FilePane({ onNavigate, browseData, loading, selectedItems, onToggleSelect, onSelectAll, onCreateDir, onUploadFile, onDownloadFile, onDrop, onAction }: FilePaneProps) {
  const { t } = useTranslation();
  const [isCreatingDir, setIsCreatingDir] = useState(false);
  const [newDirName, setNewDirName] = useState("");
  const [isDragOver, setIsDragOver] = useState(false);

  const handleCreateDir = (e: React.FormEvent) => {
    e.preventDefault();
    if (newDirName) {
      onCreateDir(newDirName);
      setNewDirName("");
      setIsCreatingDir(false);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(true);
  };
  const handleDragLeave = () => setIsDragOver(false);
  const handleDropLocal = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    onDrop(e);
  };

  return (
    <div 
      className={`flex-1 flex flex-col min-w-0 bg-surface rounded-lg border overflow-hidden transition-colors ${isDragOver ? 'border-accent ring-2 ring-accent/20' : 'border-border'}`}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDropLocal}
    >
      <div className="px-3 py-2 border-b border-border bg-surface-2 flex flex-wrap items-center justify-between gap-2 shrink-0">
        <div className="flex items-center gap-1.5 text-[12px] font-mono overflow-hidden">
          <button onClick={() => onNavigate("/")} className="text-foreground hover:text-accent-text px-1 py-0.5 rounded shrink-0">{t("common.root")}</button>
          {browseData?.currentPath.split("/").filter(Boolean).map((part, index, array) => {
            const p = "/" + array.slice(0, index + 1).join("/");
            return (
              <div key={p} className="flex items-center gap-1 shrink-0 min-w-0">
                <ChevronRight className="w-3 h-3 text-text-muted shrink-0" />
                <button onClick={() => onNavigate(p)} className="text-foreground hover:text-accent-text px-1 py-0.5 rounded truncate max-w-[100px]">{part}</button>
              </div>
            );
          })}
        </div>

        <div className="flex items-center gap-2">
          {!isCreatingDir ? (
            <button 
              onClick={() => setIsCreatingDir(true)} 
              aria-label={t("dashboard.newFolder")}
              className="text-[11px] font-medium text-foreground bg-surface border border-border-strong px-2 py-1 rounded hover:bg-surface-2 transition-colors flex items-center gap-1"
            >
              <FolderPlus className="w-3.5 h-3.5" />
            </button>
          ) : (
            <form onSubmit={handleCreateDir} className="flex gap-1">
              <input 
                autoFocus required pattern="[a-zA-Z0-9_.-]+"
                className="text-[11px] font-mono border border-border-strong bg-surface px-2 py-1 rounded focus:outline-none focus:border-accent w-24 text-foreground"
                placeholder={t("dashboard.dirname")}
                value={newDirName}
                onChange={e => setNewDirName(e.target.value)}
              />
              <button type="submit" className="text-[11px] bg-accent text-on-accent px-2 py-1 rounded hover:opacity-90">{t("common.create")}</button>
              <button type="button" onClick={() => setIsCreatingDir(false)} className="text-[11px] bg-surface border border-border-strong px-2 py-1 rounded hover:bg-surface-2 text-foreground">{t("common.cancel")}</button>
            </form>
          )}
          <label title={t("dashboard.uploadFile")} className="text-[11px] font-medium text-foreground bg-surface border border-border-strong px-2 py-1 rounded hover:bg-surface-2 transition-colors flex items-center gap-1 cursor-pointer">
            <Upload className="w-3.5 h-3.5" />
            <input aria-label={t("dashboard.uploadFile")} type="file" className="sr-only" onChange={e => e.target.files?.[0] && onUploadFile(e.target.files[0])} />
          </label>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar bg-surface">
        {loading ? (
          <div className="flex justify-center items-center h-32">
            <div className="w-5 h-5 border-2 border-border border-t-foreground rounded-full animate-spin"></div>
          </div>
        ) : (
          <table className="w-full text-left text-[12px] font-mono">
            <thead className="sticky top-0 bg-surface-2 border-b border-border z-10">
              <tr>
                <th className="px-3 py-2 w-8">
                  <button onClick={onSelectAll} aria-label={t("dashboard.selectAll")}>
                    {browseData?.directories.length && selectedItems.size === browseData.directories.length ? <CheckSquare className="w-3.5 h-3.5 text-accent-text" /> : <Square className="w-3.5 h-3.5 text-text-muted" />}
                  </button>
                </th>
                <th className="px-2 py-2 font-semibold text-foreground">{t("files.name")}</th>
                <th className="px-2 py-2 font-semibold text-right w-20 text-foreground">{t("files.size")}</th>
              </tr>
            </thead>
            <tbody>
              {browseData?.parentPath && (
                <tr className="hover:bg-surface-2 transition-colors group cursor-pointer border-b border-border" onClick={() => onNavigate(browseData.parentPath!)}>
                  <td className="px-3 py-2"></td>
                  <td className="px-2 py-2 flex items-center gap-2">
                    <ArrowUp className="w-3.5 h-3.5 text-text-muted" />
                    <span className="font-medium text-foreground">..</span>
                  </td>
                  <td className="px-2 py-2"></td>
                </tr>
              )}
              {browseData?.directories.map(item => {
                const isSelected = selectedItems.has(item.fullPath);
                const isFolder = item.type === 'dir' || item.type === undefined;
                return (
                  <tr 
                    key={item.fullPath} 
                    className={`hover:bg-surface-2 transition-colors group border-b border-border ${isSelected ? 'bg-surface-tint text-text-on-tint' : ''}`}
                    onClick={(e) => {
                      if (e.target instanceof HTMLInputElement || e.target instanceof SVGElement || e.target instanceof HTMLButtonElement) return;
                      isFolder ? onNavigate(item.fullPath) : onToggleSelect(item.fullPath);
                    }}
                  >
                    <td className="px-3 py-2">
                      <button aria-label={`${t("common.select")}: ${item.name}`} aria-pressed={selectedItems.has(item.fullPath)} onClick={(e) => { e.stopPropagation(); onToggleSelect(item.fullPath); }}>
                        {isSelected ? <CheckSquare className="w-3.5 h-3.5 text-accent-text" /> : <Square className="w-3.5 h-3.5 text-text-muted opacity-0 group-hover:opacity-100" />}
                      </button>
                    </td>
                    <td className="px-2 py-2 flex items-center justify-between gap-2 max-w-[200px]" title={item.name}>
                      <div className="flex items-center gap-2 min-w-0 truncate">
                        {isFolder ? <Folder className="w-3.5 h-3.5 text-text-muted shrink-0" /> : <FileIcon className="w-3.5 h-3.5 text-text-muted shrink-0" />}
                        <span className="truncate">{item.name}</span>
                      </div>
                      {!isFolder && onDownloadFile && (
                        <button
                          type="button"
                          title={t("common.download")}
                          onClick={(e) => {
                            e.stopPropagation();
                            onDownloadFile(item.fullPath, item.name);
                          }}
                          className="p-1 rounded text-text-muted hover:text-foreground hover:bg-surface-2 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                        >
                          <Download className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </td>
                    <td className="px-2 py-2 text-right text-text-muted">
                      {!isFolder && item.size !== undefined ? formatSize(item.size) : ''}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {selectedItems.size > 0 && (
        <div className="px-3 py-2 border-t border-border bg-surface flex items-center justify-between shrink-0">
          <span className="text-[11px] font-medium text-text-muted">{t("files.selected")}: {selectedItems.size}</span>
          <div className="flex gap-1.5">
            <button onClick={() => onAction('download')} className="px-2 py-1 bg-surface border border-border-strong rounded text-[11px] font-medium flex items-center gap-1 hover:bg-surface-2 text-foreground">
              <Download className="w-3 h-3" /> {t("common.download")}
            </button>
            <button onClick={() => onAction('copy')} className="px-2 py-1 bg-surface border border-border-strong rounded text-[11px] font-medium flex items-center gap-1 hover:bg-surface-2 text-foreground">
              <Copy className="w-3 h-3" /> {t("files.copy")}
            </button>
            <button onClick={() => onAction('move')} className="px-2 py-1 bg-surface border border-border-strong rounded text-[11px] font-medium flex items-center gap-1 hover:bg-surface-2 text-foreground">
              <MoveRight className="w-3 h-3" /> {t("files.move")}
            </button>
            {/* TODO: token for status-error is not in theme palette, keeping functional status color */}
            <button onClick={() => onAction('delete')} className="px-2 py-1 bg-surface-2 text-status-error border border-status-error/30 rounded text-[11px] font-medium flex items-center gap-1 hover:bg-surface">
              <Trash2 className="w-3 h-3" /> {t("common.delete")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
