import { getErrorMessage } from "../../lib/errors";
import { useEffect, useState, useCallback } from "react";
import { Users, Plus, Trash2, UserPlus, UserMinus, AlertTriangle } from "lucide-react";
import { ResourceToolbar } from "../../components/ResourceToolbar";
import { api } from "../../lib/api";
import { useToast } from "../../components/ui/toast";
import { useConfirm } from "../../components/ui/confirm";
import { Modal } from "../../components/ui/modal";
import { useTranslation } from "../../lib/i18n";
import { MultiSelect } from "../../components/ui/multi-select";

interface Group { name: string; members: string[]; }

export default function GroupsPage() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [users, setUsers] = useState<{username: string}[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [query, setQuery] = useState("");

  const [isCreating, setIsCreating] = useState(false);
  const [newGroupName, setNewGroupName] = useState("");

  const [isAddingUser, setIsAddingUser] = useState(false);
  const [targetGroup, setTargetGroup] = useState("");
  const [usernameToAdd, setUsernameToAdd] = useState("");

  const { error: toastError, success } = useToast();
  const { confirm } = useConfirm();
  const { t } = useTranslation();

  const fetchGroups = useCallback(async () => {
    try {
      setLoading(true);
      setLoadError("");
      const [res, usersRes] = await Promise.all([
        api.get<Group[]>("/api/groups"),
        api.get<{username: string}[]>("/api/users").catch(() => [])
      ]);
      setGroups(res);
      setUsers(usersRes || []);
    } catch (err) {
      setLoadError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchGroups(); }, [fetchGroups]);

  const handleDeleteGroup = async (groupName: string) => {
    const ok = await confirm({
      title: t("common.delete"),
      message: `${t("common.confirm")} ${groupName}?`,
      destructive: true,
      confirmText: t("common.delete")
    });
    if (!ok) return;

    try {
      await api.delete(`/api/groups/${groupName}`);
      success(t("common.delete"), `Group ${groupName} removed`);
      fetchGroups();
    } catch (err) {
      toastError("Delete Error", getErrorMessage(err));
    }
  };

  const handleCreateGroup = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post("/api/groups", { groupName: newGroupName });
      success(t("common.create"), `Group ${newGroupName} created`);
      setIsCreating(false);
      setNewGroupName("");
      fetchGroups();
    } catch (err) {
      toastError("Creation Error", getErrorMessage(err));
    }
  };

  const handleAddUser = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const users = usernameToAdd.split(',').map(s => s.trim()).filter(Boolean);
      await Promise.all(users.map(u => api.post(`/api/groups/${targetGroup}/users`, { username: u })));
      success(t("common.add"), `Users ${users.join(', ')} added to ${targetGroup}`);
      setIsAddingUser(false);
      setUsernameToAdd("");
      fetchGroups();
    } catch (err) {
      toastError("Add Error", getErrorMessage(err));
    }
  };

  const handleRemoveUser = async (groupName: string, username: string) => {
    const ok = await confirm({
      title: t("common.remove"),
      message: `${t("common.remove")} ${username}?`,
      destructive: true,
      confirmText: t("common.remove")
    });
    if (!ok) return;

    try {
      await api.delete(`/api/groups/${groupName}/users/${username}`);
      success(t("common.remove"), `User ${username} removed from ${groupName}`);
      fetchGroups();
    } catch (err) {
      toastError("Remove Error", getErrorMessage(err));
    }
  };

  const openAddUser = (groupName: string) => {
    setTargetGroup(groupName);
    setUsernameToAdd("");
    setIsAddingUser(true);
  };

  const search = query.trim().toLocaleLowerCase();
  const visibleGroups = groups.filter(group => [group.name, ...(group.members || [])].some(value => value.toLocaleLowerCase().includes(search)));

  return (
    <div className="accounts-page space-y-6 animate-in fade-in duration-500 pb-10">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4">
        <div><h1 className="text-2xl font-semibold text-foreground tracking-tight">{t("groups.title")}</h1><p className="mt-2 text-sm text-text-muted">{t("groups.subtitle")}</p></div>
        <button onClick={() => setIsCreating(true)} className="shares-create text-sm font-medium text-on-accent bg-accent px-4 py-2 rounded-md hover:opacity-90 transition-colors flex items-center gap-2">
          <Plus className="w-4 h-4" /> {t("groups.newGroup")}
        </button>
      </div>
      <ResourceToolbar query={query} onQueryChange={setQuery} searchLabel={t("groups.search")} count={visibleGroups.length} total={groups.length} loading={loading} onRefresh={() => void fetchGroups()} />
      {loadError && <div role="alert" className="shares-error"><AlertTriangle className="h-5 w-5 shrink-0" /><div><p>{t(groups.length ? "lists.staleData" : "lists.loadError")}</p><p className="mt-1 text-sm text-text-muted">{loadError}</p></div></div>}
      
      {loading ? (
        <div className="flex flex-col gap-3" role="status" aria-label={t("sidebar.wait")} aria-busy="true">
          {[1,2,3,4].map(i => (
             <div key={i} className="h-16 bg-surface border border-border rounded-lg animate-pulse" />
          ))}
        </div>
      ) : groups.length === 0 && !loadError ? (
        <div className="text-center py-24 bg-surface rounded-lg border border-border border-dashed">
          <Users className="w-12 h-12 text-text-muted mx-auto mb-4 opacity-50" />
          <h3 className="text-base font-semibold text-foreground">{t("groups.noGroups")}</h3>
        </div>
      ) : groups.length > 0 && visibleGroups.length === 0 ? (
        <div className="shares-empty"><Users className="h-8 w-8 text-text-muted" /><h2>{t("lists.noMatches")}</h2><p>{t("lists.noMatchesHint")}</p><button className="shares-refresh" onClick={() => setQuery("")}>{t("lists.clearSearch")}</button></div>
      ) : visibleGroups.length > 0 ? (
        <div className="accounts-list bg-surface rounded-lg border border-border overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead className="bg-surface-2 text-[13px] font-semibold text-text-muted uppercase tracking-wide">
              <tr>
                <th className="py-4 px-6 w-[35%]">{t("groups.groupName")}</th>
                <th className="py-4 px-6 w-[45%]">{t("common.members")}</th>
                <th className="py-4 px-6 text-right w-[20%]">{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border text-sm">
              {visibleGroups.map((group) => (
                <tr key={group.name} className="hover:bg-surface-2 transition-colors group/row">
                  <td data-label={t("groups.groupName")} className="py-4 px-6 align-top">
                    <div className="flex flex-col gap-1">
                      <div className="flex items-center gap-2.5">
                         <div className="bg-surface-2 border border-border p-2 rounded-md">
                           <Users className="w-4 h-4 text-foreground opacity-80" />
                         </div>
                         <span className="min-w-0 break-words text-foreground font-semibold font-mono tracking-wide text-[15px]">@{group.name}</span>
                      </div>
                      <span className="text-[11px] font-semibold tracking-wider text-text-muted uppercase mt-1 ml-12">{t("groups.posixGroup")}</span>
                    </div>
                  </td>
                  <td data-label={t("common.members")} className="py-4 px-6 align-top">
                    <div className="flex flex-col gap-2">
                      <div className="flex flex-wrap gap-2 items-center justify-between max-w-[400px]">
                        <span className="text-xs font-semibold uppercase tracking-wider text-text-muted">{group.members?.length || 0} {t("common.members")} </span>
                        <button 
                          onClick={() => openAddUser(group.name)}
                          aria-label={`${t("common.add")}: ${group.name}`}
                          className="text-accent-text hover:opacity-80 text-xs font-bold uppercase flex items-center gap-1 bg-surface-2 hover:bg-surface px-2 py-0.5 rounded border border-border/60 transition-colors"
                        >
                          <Plus className="w-3 h-3" /> {t("common.add")}
                        </button>
                      </div>
                      
                      {!group.members || group.members.length === 0 ? (
                        <div className="text-[13px] text-text-muted my-1">{t("groups.emptyGroup")}</div>
                      ) : (
                        <div className="flex flex-wrap gap-2 max-w-[400px]">
                          {group.members.map(member => (
                            <div key={member} className="group-member flex items-center gap-1.5 bg-surface-2 px-2.5 py-1 rounded-md text-[13px] font-mono border border-border text-foreground group/mem">
                              <span className="min-w-0 break-words">{member}</span>
                              {/* TODO: status-error token fallback */}
                              <button 
                                title={t("common.remove")} 
                                aria-label={`${t("common.remove")}: ${member} (${group.name})`}
                                onClick={() => handleRemoveUser(group.name, member)}
                                className="text-text-muted hover:text-status-error transition-colors ml-0.5 p-0.5 rounded hover:bg-surface"
                              >
                                <UserMinus className="w-3.5 h-3.5" />
                              </button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </td>
                  <td data-label={t("common.actions")} className="account-actions py-4 px-6 text-right align-top">
                    <div className="flex items-center justify-end gap-1.5">
                      <button onClick={() => handleDeleteGroup(group.name)} aria-label={`${t("common.delete")}: ${group.name}`} className="p-2 text-text-muted hover:text-status-error hover:bg-status-error/10 rounded-md transition-colors border border-border/60 hover:border-border" title={t("common.delete")}>
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
        title={t("groups.newOsGroup")}
        maxWidth="sm"
      >
        <form onSubmit={handleCreateGroup} className="space-y-5">
          <div className="space-y-1.5">
            <label htmlFor="group-name" className="text-[13px] font-medium text-foreground">{t("groups.groupName")}</label>
            <input 
              id="group-name"
              required autoFocus pattern="^[a-z_][a-z0-9_-]*[$]?$"
              className="w-full bg-surface border border-border-strong focus:border-accent focus:outline-none text-foreground transition-all py-2.5 px-3.5 text-sm font-mono rounded-lg"
              placeholder="smb_users"
              value={newGroupName} 
              onChange={(e) => setNewGroupName(e.target.value)} 
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
        isOpen={isAddingUser} 
        onClose={() => setIsAddingUser(false)}
        title={`${t("groups.addToGroup")}@${targetGroup}`}
        maxWidth="sm"
      >
        <form onSubmit={handleAddUser} className="space-y-5">
          <div className="space-y-1.5">
            <label htmlFor="group-users" className="text-[13px] font-medium text-foreground">{t("users.username")}</label>
            <MultiSelect 
                inputId="group-users"
                className="w-full text-sm font-mono"
                value={usernameToAdd} 
                onChange={(val: string) => setUsernameToAdd(val)} 
                placeholder="john_doe, user2"
                options={users.map(u => u.username)}
              />
          </div>
          <div className="pt-4 flex justify-end gap-3 border-t border-border mt-2">
             <button type="button" onClick={() => setIsAddingUser(false)} className="text-sm font-medium text-foreground bg-surface border border-border-strong px-5 py-2.5 rounded-md hover:bg-surface-2 transition-colors">
               {t("common.cancel")}
             </button>
            <button type="submit" className="text-sm font-medium text-on-accent bg-accent px-6 py-2.5 rounded-md hover:opacity-90 transition-colors flex items-center gap-1.5">
              <UserPlus className="w-4 h-4" /> {t("common.add")}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
