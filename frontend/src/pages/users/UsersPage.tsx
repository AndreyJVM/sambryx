import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback } from "react";
import { Plus, Trash2, Key, UserIcon, ShieldCheck, AlertTriangle } from "lucide-react";
import { ResourceToolbar } from "../../components/ResourceToolbar";
import { api } from "../../lib/api";
import { useToast } from "../../components/ui/toast";
import { useConfirm } from "../../components/ui/confirm";
import { Modal } from "../../components/ui/modal";
import { useTranslation } from "../../lib/i18n";

interface User {
  username: string;
  fullName: string;
}

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [query, setQuery] = useState("");

  const [isCreating, setIsCreating] = useState(false);
  const [newUser, setNewUser] = useState({ username: "", fullName: "", password: "" });

  const [passwordModalOpen, setPasswordModalOpen] = useState(false);
  const [passwordTarget, setPasswordTarget] = useState("");
  const [newPassword, setNewPassword] = useState("");

  const { error: toastError, success } = useToast();
  const { confirm } = useConfirm();
  const { t } = useTranslation();

  const fetchUsers = useCallback(async () => {
    try {
      setLoading(true);
      setLoadError("");
      const res = await api.get<User[]>("/api/users");
      setUsers(res);
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleDelete = async (username: string) => {
    const ok = await confirm({
      title: t("common.delete"),
      message: `${t("users.deleteWarning")} @${username}`,
      destructive: true,
      confirmText: t("common.delete")
    });
    if (!ok) return;

    try {
      await api.delete(`/api/users/${username}`);
      success(t("common.delete"), `User ${username} removed`);
      fetchUsers();
    } catch (err) {
      toastError("Delete Error", getErrorMessage(err));
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post("/api/users", newUser);
      success("Created", `User ${newUser.username} added successfully`);
      setIsCreating(false);
      setNewUser({ username: "", fullName: "", password: "" });
      fetchUsers();
    } catch (err) {
      toastError("Creation Error", getErrorMessage(err));
    }
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.put(`/api/users/${passwordTarget}/password`, { newPassword });
      success("Updated", `Password for ${passwordTarget} changed`);
      setPasswordModalOpen(false);
      setNewPassword("");
    } catch (err) {
      toastError("Password Error", getErrorMessage(err));
    }
  };

  const openPasswordModal = (username: string) => {
    setPasswordTarget(username);
    setNewPassword("");
    setPasswordModalOpen(true);
  };

  const search = query.trim().toLocaleLowerCase();
  const visibleUsers = users.filter(user => [user.username, user.fullName || ""].some(value => value.toLocaleLowerCase().includes(search)));

  return (
    <div className="accounts-page space-y-6 animate-in fade-in duration-500 pb-10">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4">
        <div><h1 className="text-2xl font-semibold text-foreground tracking-tight">{t("users.title")}</h1><p className="mt-2 text-sm text-text-muted">{t("users.subtitle")}</p></div>
        <button onClick={() => setIsCreating(true)} className="shares-create text-sm font-medium text-on-accent bg-accent px-4 py-2 rounded-md hover:opacity-90 transition-colors flex items-center gap-2">
          <Plus className="w-4 h-4" /> {t("users.addUser")}
        </button>
      </div>
      <ResourceToolbar query={query} onQueryChange={setQuery} searchLabel={t("users.search")} count={visibleUsers.length} total={users.length} loading={loading} onRefresh={() => void fetchUsers()} />
      {loadError && <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p>{t(users.length ? "lists.staleData" : "lists.loadError")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}
      
      {loading ? (
        <div className="flex flex-col gap-3" role="status" aria-label={t("sidebar.wait")} aria-busy="true">
          {[1,2,3,4].map(i => (
             <div key={i} className="h-16 bg-surface border border-border rounded-lg animate-pulse" />
          ))}
        </div>
      ) : users.length === 0 && !loadError ? (
        <div className="text-center py-24 bg-surface rounded-lg border border-border border-dashed">
          <UserIcon className="w-12 h-12 text-text-muted mx-auto mb-4 opacity-50" />
          <h3 className="text-base font-semibold text-foreground">{t("users.noUsers")}</h3>
          <p className="text-sm text-text-muted mt-1 mb-6">{t("users.noUsersDesc")}</p>
        </div>
      ) : users.length > 0 && visibleUsers.length === 0 ? (
        <div className="shares-empty"><UserIcon className="h-8 w-8 text-text-muted" /><h2>{t("lists.noMatches")}</h2><p>{t("lists.noMatchesHint")}</p><button className="shares-refresh" onClick={() => setQuery("")}>{t("lists.clearSearch")}</button></div>
      ) : visibleUsers.length > 0 ? (
        <div className="accounts-list bg-surface rounded-lg border border-border overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead className="bg-surface-2 text-[13px] font-semibold text-text-muted uppercase tracking-wide">
              <tr>
                <th className="py-4 px-6 w-[40%]">{t("users.username")}</th>
                <th className="py-4 px-6 w-[40%]">{t("users.fullName")}</th>
                <th className="py-4 px-6 text-right w-[20%]">{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border text-sm">
              {visibleUsers.map((user) => (
                <tr key={user.username} className="hover:bg-surface-2 transition-colors group">
                  <td data-label={t("users.username")} className="py-4 px-6">
                    <div className="flex items-center gap-3">
                       <div className="bg-surface-2 border border-border p-2 rounded-md">
                         <ShieldCheck className="w-4 h-4 text-foreground opacity-80" />
                       </div>
                       <span className="min-w-0 break-words text-foreground font-semibold font-mono tracking-wide">@{user.username}</span>
                    </div>
                  </td>
                  <td data-label={t("users.fullName")} className="py-4 px-6 text-text-muted">
                    {user.fullName || "—"}
                  </td>
                  <td data-label={t("common.actions")} className="account-actions py-4 px-6 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      <button onClick={() => openPasswordModal(user.username)} aria-label={`${t("users.changePwd")}: ${user.username}`} className="p-2 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors border border-border/60 hover:border-border" title={t("users.changePwd")}>
                        <Key className="w-4 h-4" />
                      </button>
                      <button onClick={() => handleDelete(user.username)} aria-label={`${t("common.delete")}: ${user.username}`} className="p-2 text-text-muted hover:text-status-error hover:bg-status-error/10 rounded-md transition-colors border border-border/60 hover:border-border" title={t("common.delete")}>
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

      <Modal 
        isOpen={isCreating} 
        onClose={() => setIsCreating(false)}
        title={t("users.addUser")}
        description={t("users.subtitle")}
        maxWidth="md"
      >
        <form onSubmit={handleCreate} className="space-y-5">
          <div className="space-y-1.5">
            <label htmlFor="user-username" className="text-[13px] font-medium text-foreground">{t("users.username")}</label>
            <input 
              id="user-username"
              required 
              pattern="^[a-z_][a-z0-9_-]*[$]?$"
              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-sm font-mono rounded-lg"
              value={newUser.username} 
              onChange={(e) => setNewUser({...newUser, username: e.target.value})} 
              placeholder="john_doe"
            />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="user-full-name" className="text-[13px] font-medium text-foreground">{t("users.fullName")}</label>
            <input 
              id="user-full-name"
              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg"
              value={newUser.fullName} 
              onChange={(e) => setNewUser({...newUser, fullName: e.target.value})} 
              placeholder="John Doe"
            />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="user-password" className="text-[13px] font-medium text-foreground">{t("users.password")}</label>
            <input 
              id="user-password"
              autoComplete="new-password"
              required 
              type="password"
              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg"
              value={newUser.password} 
              onChange={(e) => setNewUser({...newUser, password: e.target.value})} 
              placeholder="••••••••"
            />
          </div>
          <div className="pt-4 flex justify-end gap-3 border-t border-border mt-2">
             <button type="button" onClick={() => setIsCreating(false)} className="text-sm font-medium text-foreground bg-surface border border-border-strong px-5 py-2.5 rounded-md hover:bg-surface-2 transition-colors">
               {t("common.cancel")}
             </button>
            <button type="submit" className="text-sm font-medium text-on-accent bg-accent px-6 py-2.5 rounded-md hover:opacity-90 transition-colors">
               {t("common.create")}
            </button>
          </div>
        </form>
      </Modal>

      <Modal 
        isOpen={passwordModalOpen} 
        onClose={() => setPasswordModalOpen(false)}
        title={t("users.changePwd")}
        description={`${t("users.pwdDesc")}@${passwordTarget}`}
        maxWidth="sm"
      >
        <form onSubmit={handleChangePassword} className="space-y-5">
          <div className="space-y-1.5">
            <label htmlFor="user-new-password" className="text-[13px] font-medium text-foreground">{t("users.newPassword")}</label>
            <input 
              id="user-new-password"
              autoComplete="new-password"
              required 
              type="password"
              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-sm rounded-lg"
              value={newPassword} 
              onChange={(e) => setNewPassword(e.target.value)} 
              placeholder="••••••••"
            />
          </div>
          <div className="pt-4 flex justify-end gap-3 border-t border-border mt-2">
             <button type="button" onClick={() => setPasswordModalOpen(false)} className="text-sm font-medium text-foreground bg-surface border border-border-strong px-5 py-2.5 rounded-md hover:bg-surface-2 transition-colors">
               {t("common.cancel")}
             </button>
            <button type="submit" className="text-sm font-medium text-on-accent bg-accent px-6 py-2.5 rounded-md hover:opacity-90 transition-colors">
               {t("common.save")}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
