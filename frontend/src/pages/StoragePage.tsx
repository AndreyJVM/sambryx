import { FileManager } from "../components/FileManager";
import { useTranslation } from "../lib/i18n";

export default function StoragePage() {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      <div className="dashboard-heading">
        <h1 className="font-semibold text-foreground">{t("sidebar.storage")}</h1>
        <p className="mt-1 text-sm text-text-muted">{t("dashboard.explorerCaption")}</p>
      </div>
      <FileManager />
    </div>
  );
}
