import { RefreshCw, Search } from "lucide-react";
import { useTranslation } from "../lib/i18n";

export function ResourceToolbar({ query, onQueryChange, searchLabel, count, total, loading, onRefresh }: {
  query: string;
  onQueryChange: (value: string) => void;
  searchLabel: string;
  count: number;
  total: number;
  loading: boolean;
  onRefresh: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="shares-toolbar resource-toolbar">
      <label className="shares-search">
        <Search aria-hidden="true" className="h-4 w-4 shrink-0 text-text-muted" />
        <input type="search" value={query} onChange={event => onQueryChange(event.target.value)} placeholder={searchLabel} aria-label={searchLabel} />
      </label>
      <span role="status" className="text-xs text-text-muted">{loading ? t("sidebar.wait") : `${t("lists.shown")}: ${count} / ${total}`}</span>
      <button onClick={onRefresh} disabled={loading} className="shares-refresh"><RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />{t("dashboard.refresh")}</button>
    </div>
  );
}
