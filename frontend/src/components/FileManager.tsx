import { getErrorMessage } from "../lib/errors";
import React, { useState, useEffect, useCallback } from "react";
import { FilePane, BrowseResult } from "./ui/file-pane";
import { api } from "../lib/api";
import { useToast } from "./ui/toast";

export function FileManager() {
  const [leftPath, setLeftPath] = useState("/");
  const [rightPath, setRightPath] = useState("/home");
  
  const [leftData, setLeftData] = useState<BrowseResult | null>(null);
  const [rightData, setRightData] = useState<BrowseResult | null>(null);
  
  const [leftLoading, setLeftLoading] = useState(false);
  const [rightLoading, setRightLoading] = useState(false);

  const [leftSelected, setLeftSelected] = useState<Set<string>>(new Set());
  const [rightSelected, setRightSelected] = useState<Set<string>>(new Set());

  const { error: toastError, success } = useToast();

  const fetchPane = useCallback(async (path: string, side: 'left' | 'right') => {
    side === 'left' ? setLeftLoading(true) : setRightLoading(true);
    try {
      const res = await api.get<BrowseResult>(`/api/fs/browse?path=${encodeURIComponent(path)}`);
      if (side === 'left') {
        setLeftData(res);
        setLeftSelected(new Set());
      } else {
        setRightData(res);
        setRightSelected(new Set());
      }
    } catch (err) {
      toastError("FS Error", getErrorMessage(err));
    } finally {
      side === 'left' ? setLeftLoading(false) : setRightLoading(false);
    }
  }, [toastError]);

  useEffect(() => { fetchPane(leftPath, 'left'); }, [leftPath, fetchPane]);
  useEffect(() => { fetchPane(rightPath, 'right'); }, [rightPath, fetchPane]);

  const toggleSelect = (path: string, side: 'left' | 'right') => {
    const setFn = side === 'left' ? setLeftSelected : setRightSelected;
    setFn(prev => {
      const next = new Set(prev);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });
  };

  const selectAll = (side: 'left' | 'right') => {
    const data = side === 'left' ? leftData : rightData;
    const setFn = side === 'left' ? setLeftSelected : setRightSelected;
    const selected = side === 'left' ? leftSelected : rightSelected;
    
    if (selected.size === data?.directories.length) {
      setFn(new Set());
    } else {
      setFn(new Set(data?.directories.map(d => d.fullPath) || []));
    }
  };

  const createDir = async (name: string, path: string, side: 'left' | 'right') => {
    try {
      await api.post("/api/fs/mkdir", { parentPath: path, name, dirName: name });
      fetchPane(path, side);
    } catch (err) {
      toastError("Error", getErrorMessage(err));
    }
  };

  const downloadFile = async (filePath: string, filename?: string) => {
    try {
      const res = await fetch(`/api/fs/download?path=${encodeURIComponent(filePath)}`);
      if (!res.ok) {
        let msg = "Download failed";
        try {
          const errJson = await res.json();
          msg = errJson.message || msg;
        } catch {
          msg = (await res.text()) || msg;
        }
        throw new Error(msg);
      }
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const downloadName = filename || (filePath.includes('/') ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath);
      a.download = downloadName || "download";
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      toastError("Download Error", getErrorMessage(err));
    }
  };

  const handleAction = async (action: 'copy' | 'move' | 'delete' | 'download', side: 'left' | 'right') => {
    const selected = side === 'left' ? leftSelected : rightSelected;
    const destPath = side === 'left' ? rightPath : leftPath;
    const srcPath = side === 'left' ? leftPath : rightPath;
    const data = side === 'left' ? leftData : rightData;
    
    if (selected.size === 0) return;

    if (action === 'download') {
      let count = 0;
      for (const item of selected) {
        const itemData = data?.directories.find(d => d.fullPath === item);
        if (itemData && itemData.type === 'dir') continue;
        await downloadFile(item, itemData?.name);
        count++;
      }
      if (count === 0) {
        toastError("Download Error", "No files selected for download");
      }
      return;
    }
    
    if (action !== 'delete' && destPath === srcPath) {
      toastError("Error", "Source and destination are the same");
      return;
    }

    try {
      for (const item of selected) {
        if (action === 'delete') {
          await api.post("/api/fs/delete", { target: item });
        } else {
          await api.post(`/api/fs/${action}`, { source: item, destination: destPath });
        }
      }
      success("Success", `Items ${action}d successfully`);
      fetchPane(leftPath, 'left');
      fetchPane(rightPath, 'right');
    } catch (err) {
      toastError("Error", getErrorMessage(err));
    }
  };

  const uploadFile = async (file: File, path: string, side: 'left' | 'right') => {
    try {
      const formData = new FormData();
      formData.append("path", path);
      formData.append("file", file);
      
      const res = await fetch("/api/fs/upload", {
        method: "POST",
        body: formData
      });
      
      if (!res.ok) throw new Error(await res.text());
      success("Success", "File uploaded");
      fetchPane(path, side);
    } catch (err) {
      toastError("Upload Error", getErrorMessage(err));
    }
  };

  const handleDrop = (e: React.DragEvent, path: string, side: 'left' | 'right') => {
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      Array.from(e.dataTransfer.files).forEach(f => uploadFile(f, path, side));
    }
  };

  return (
    <div className="file-manager flex flex-col xl:flex-row gap-4">
      <FilePane 
        title="Left Pane"
        path={leftPath}
        onNavigate={setLeftPath}
        browseData={leftData}
        loading={leftLoading}
        selectedItems={leftSelected}
        onToggleSelect={(p) => toggleSelect(p, 'left')}
        onSelectAll={() => selectAll('left')}
        onCreateDir={(name) => createDir(name, leftPath, 'left')}
        onUploadFile={(f) => uploadFile(f, leftPath, 'left')}
        onDownloadFile={(p, name) => downloadFile(p, name)}
        onDrop={(e) => handleDrop(e, leftPath, 'left')}
        otherPanePath={rightPath}
        onAction={(a) => handleAction(a, 'left')}
      />
      <FilePane 
        title="Right Pane"
        path={rightPath}
        onNavigate={setRightPath}
        browseData={rightData}
        loading={rightLoading}
        selectedItems={rightSelected}
        onToggleSelect={(p) => toggleSelect(p, 'right')}
        onSelectAll={() => selectAll('right')}
        onCreateDir={(name) => createDir(name, rightPath, 'right')}
        onUploadFile={(f) => uploadFile(f, rightPath, 'right')}
        onDownloadFile={(p, name) => downloadFile(p, name)}
        onDrop={(e) => handleDrop(e, rightPath, 'right')}
        otherPanePath={leftPath}
        onAction={(a) => handleAction(a, 'right')}
      />
    </div>
  );
}
