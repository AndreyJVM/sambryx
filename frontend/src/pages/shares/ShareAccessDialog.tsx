import { useEffect, useId, useRef, useState } from "react";
import { Modal } from "../../components/ui/modal";
import { api } from "../../lib/api";
import { useTranslation } from "../../lib/i18n";

interface Report {
  share: string;
  username: string;
  findings: { code: string; status: string; evidence: string; nextAction: string }[];
}

export function ShareAccessDialog({ share, usernames, onClose }: {
  share: string; usernames: string[]; onClose: () => void;
}) {
  const { t } = useTranslation();
  const inputId = useId();
  const [username, setUsername] = useState("");
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState(false);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const listRef = useRef<HTMLUListElement>(null);
  const options = usernames.filter(name => /^[a-z_][a-z0-9_.-]{0,31}$/.test(name)
    && name.toLowerCase().includes(username.toLowerCase()));
  const listId = `${inputId}-users`;

  useEffect(() => {
    if (suggestionsOpen && activeIndex >= 0) {
      listRef.current?.children[activeIndex]?.scrollIntoView({ block: "nearest" });
    }
  }, [suggestionsOpen, activeIndex]);

  const closeSuggestions = () => {
    setSuggestionsOpen(false);
    setActiveIndex(-1);
  };
  const changeUsername = (value: string) => {
    setUsername(value);
    setReport(null);
    setError(false);
    setActiveIndex(-1);
  };
  const selectUsername = (value: string) => {
    changeUsername(value);
    closeSuggestions();
  };
  const valid = /^[a-z_][a-z0-9_.-]{0,31}$/.test(username);

  const check = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || loading) return;
    closeSuggestions();
    setLoading(true);
    setError(false);
    setReport(null);
    try {
      setReport(await api.get<Report>(`/api/shares/${encodeURIComponent(share)}/access-check?username=${encodeURIComponent(username)}`));
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  return <Modal isOpen onClose={onClose} title={`${t("diagnostics.title")}: ${share}`} description={t("diagnostics.description")} maxWidth="lg">
    <form onSubmit={check} className="space-y-4">
      <div className="space-y-2">
        <label htmlFor={inputId} className="block text-sm text-foreground">{t("diagnostics.username")}</label>
        <input id={inputId} role="combobox" aria-autocomplete="list"
          aria-expanded={suggestionsOpen} aria-controls={suggestionsOpen ? listId : undefined}
          aria-activedescendant={suggestionsOpen && options[activeIndex] ? `${listId}-${activeIndex}` : undefined}
          value={username} disabled={loading} maxLength={32}
          onChange={event => { changeUsername(event.target.value); setSuggestionsOpen(true); }}
          onFocus={() => setSuggestionsOpen(true)} onClick={() => setSuggestionsOpen(true)}
          onBlur={closeSuggestions}
          onKeyDown={event => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              setSuggestionsOpen(true);
              setActiveIndex(index => event.key === "ArrowDown"
                ? Math.min(index + 1, options.length - 1)
                : index < 0 ? options.length - 1 : Math.max(0, index - 1));
            } else if (event.key === "Enter" && suggestionsOpen) {
              event.preventDefault();
              if (options[activeIndex]) selectUsername(options[activeIndex]);
              else closeSuggestions();
            } else if (event.key === "Escape" && suggestionsOpen) {
              event.preventDefault();
              event.stopPropagation();
              closeSuggestions();
            }
          }}
          autoComplete="off" aria-describedby={`${inputId}-hint`}
          className="w-full min-w-0 bg-surface text-foreground border border-border-strong rounded-md px-3 py-2" />
        {suggestionsOpen && <>
          <ul ref={listRef} id={listId} role="listbox" aria-label={t("diagnostics.localAccounts")}
            onMouseDown={event => event.preventDefault()}
            className="max-h-[min(15rem,20dvh)] overflow-y-auto overscroll-contain border border-border rounded-md bg-surface">
            {options.map((name, index) => <li key={name} id={`${listId}-${index}`} role="option"
              aria-selected={activeIndex === index}
              onClick={() => selectUsername(name)}
              className={`px-3 py-2 min-h-11 break-all cursor-pointer ${activeIndex === index ? "bg-accent text-on-accent" : "text-foreground hover:bg-surface-2"}`}>{name}</li>)}
          </ul>
          {options.length === 0 && <p role="status" className="text-sm text-text-muted">{t("diagnostics.noAccounts")}</p>}
        </>}
        <p id={`${inputId}-hint`} className="text-sm text-text-muted">{t("diagnostics.identityHint")}</p>
      </div>
      <button type="submit" disabled={!valid || loading}
        onMouseDown={event => {
          // Keep the inline list from moving the submit button before its click.
          if (suggestionsOpen) event.preventDefault();
        }}
        className="px-4 py-2 rounded-md bg-accent text-on-accent disabled:opacity-50">{t("diagnostics.run")}</button>
      {loading && <p role="status" className="text-sm text-text-muted">{t("diagnostics.loading")}</p>}
      {error && <p role="alert" className="text-sm text-status-error">{t("diagnostics.error")}</p>}
    </form>
    {report && <div className="mt-5 space-y-3 break-words" aria-live="polite">
      <p className="font-medium text-foreground">{report.share} · {report.username}</p>
      <p className="text-sm text-text-muted">{t("diagnostics.partial")}</p>
      <ul className="space-y-3">{report.findings.map(finding => <li key={finding.code} className="border border-border rounded-md p-3 space-y-1">
        <h3 className="text-sm font-semibold text-foreground">{t(`diagnostics.checks.${finding.code}`)}</h3>
        <p className={`text-sm font-semibold ${finding.status === "BLOCKING" ? "text-status-error" : "text-text-muted"}`}>{t(`diagnostics.${finding.status}`)}</p>
        <p className="text-sm text-foreground">{t(`diagnostics.${finding.evidence}`)}</p>
        <p className="text-sm text-text-muted">{t(`diagnostics.${finding.nextAction}`)}</p>
      </li>)}</ul>
    </div>}
  </Modal>;
}
