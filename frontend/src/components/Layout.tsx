import { getErrorMessage } from "../lib/errors";
import { useEffect, useState } from "react";
import { Link, useLocation, Outlet } from "react-router-dom";
import {
  Server,
  FolderKanban,
  Users,
  Settings,
  LogOut,
  Play,
  Square,
  RefreshCw,
  Moon,
  Sun,
  Shield,
  ShieldCheck,
  PanelLeftClose,
  PanelLeftOpen,
  Github
} from "lucide-react";
import { api } from "../lib/api";
import { useToast } from "./ui/toast";
import { useTranslation } from "../lib/i18n";
import { GlobalSearch } from "./ui/global-search";
import "./workspace.css";
import SessionWarning from "./SessionWarning";

export default function Layout({ children }: { children?: React.ReactNode }) {
  const location = useLocation();
  const [userInfo, setUserInfo] = useState({ host: "", user: "" });
  const [serverStatus, setServerStatus] = useState<boolean | null>(null);
  const [isRestarting, setIsRestarting] = useState(false);
  const [isCollapsed, setIsCollapsed] = useState(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("sidebar_collapsed") === "true";
    }
    return false;
  });
  const [isDark, setIsDark] = useState(() => {
    const saved = typeof window !== "undefined" ? localStorage.getItem("theme") : null;
    if (saved === "dark") return true;
    if (saved === "light") return false;
    return typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches;
  });
  const { toast } = useToast();
  const { t, lang, setLanguage } = useTranslation();

  useEffect(() => {
    api.get<{ host: string; user: string }>("/api/auth/me")
      .then(res => setUserInfo(res))
      .catch(() => {});
  }, []);

  const fetchStatus = () => {
    api.get<{ isRunning: boolean }>("/api/monitoring/dashboard")
      .then(res => {
        if (typeof res?.isRunning === "boolean") {
          setServerStatus(res.isRunning);
        }
      })
      .catch(() => {});
  };

  useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  const handleLogout = async () => {
    try {
      await api.post("/api/auth/logout");
    } finally {
      window.location.href = "/ui/login";
    }
  };

  const handleServiceControl = async (action: string) => {
    try {
      if (action === "restart") setIsRestarting(true);
      await api.post(`/api/monitoring/control?action=${action}`);
      toast("info", "Command Sent", `Action: ${action}`);
      setTimeout(fetchStatus, 1000);
    } catch (err) {
      toast("error", "Error", getErrorMessage(err));
    } finally {
      if (action === "restart") {
        setTimeout(() => setIsRestarting(false), 1200);
      }
    }
  };

  const toggleCollapse = () => {
    setIsCollapsed(prev => {
      const next = !prev;
      localStorage.setItem("sidebar_collapsed", String(next));
      return next;
    });
  };

  const toggleDark = () => {
    const next = !isDark;
    setIsDark(next);
    document.documentElement.classList.toggle("dark", next);
    localStorage.setItem("theme", next ? "dark" : "light");
  };

  const toggleLang = () => {
    setLanguage(lang === "ru" ? "en" : "ru");
  };

  useEffect(() => {
    const saved = localStorage.getItem("theme");
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const shouldBeDark = saved === "dark" || (!saved && prefersDark);
    if (shouldBeDark) {
      document.documentElement.classList.add("dark");
      setIsDark(true);
    } else {
      document.documentElement.classList.remove("dark");
      setIsDark(false);
    }

    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
    const handleChange = (e: MediaQueryListEvent) => {
      if (!localStorage.getItem("theme")) {
        setIsDark(e.matches);
        document.documentElement.classList.toggle("dark", e.matches);
      }
    };
    mediaQuery.addEventListener("change", handleChange);
    return () => mediaQuery.removeEventListener("change", handleChange);
  }, []);

  const navItems = [
    { name: t("sidebar.dashboard"), path: "/dashboard", icon: Server },
    { name: t("sidebar.shares"), path: "/shares", icon: FolderKanban },
    { name: t("sidebar.users"), path: "/users", icon: Users },
    { name: t("sidebar.groups"), path: "/groups", icon: Users },
    { name: t("sidebar.ad"), path: "/ad", icon: Shield },
    { name: t("sidebar.fstec"), path: "/fstec", icon: ShieldCheck },
    { name: t("sidebar.settings"), path: "/config", icon: Settings },
  ];

  const userInitial = (userInfo.user || "U").slice(0, 2).toUpperCase();

  return (
    <div className="workspace min-h-screen flex text-foreground bg-bg font-sans selection:bg-accent selection:text-on-accent">
      <SessionWarning />
      <a href="#workspace-content" className="workspace-skip">{t("sidebar.skipContent")}</a>
      <aside className={`workspace-sidebar bg-surface flex-col hidden md:flex border border-border shrink-0 z-20 transition-[width] duration-200 ease-in-out ${
        isCollapsed ? "w-[68px]" : "w-[260px]"
      }`}>
        {/* Header */}
        <div className={`h-16 border-b border-border flex items-center shrink-0 transition-all ${
          isCollapsed ? "justify-center px-2" : "justify-between px-4"
        }`}>
          {!isCollapsed ? (
            <>
              <Link to="/shares" className="flex items-center gap-2.5 text-base tracking-tight group focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground rounded">
                <img
                  src={`${import.meta.env.BASE_URL}sambryx/logo-mark.svg`}
                  alt="Sambryx"
                  className="h-7 w-auto shrink-0 transition-transform group-hover:scale-105"
                />
                <span className="font-semibold text-foreground">Sambryx</span>
              </Link>
              <div className="flex items-center gap-0.5">
                <button
                  onClick={toggleLang}
                  className="p-1.5 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors text-xs font-bold uppercase tracking-wider focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
                  title={lang === "ru" ? "Switch to English" : "Переключить на Русский"}
                >
                  {lang}
                </button>
                <button
                  onClick={toggleDark}
                  className="p-1.5 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
                  title={isDark ? "Light mode" : "Dark mode"}
                >
                  {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                </button>
                <button
                  onClick={toggleCollapse}
                  className="p-1.5 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
                  title={t("sidebar.collapse")}
                  aria-label={t("sidebar.collapse")}
                >
                  <PanelLeftClose className="w-4 h-4" />
                </button>
              </div>
            </>
          ) : (
            <button
              onClick={toggleCollapse}
              className="p-2 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-lg transition-colors group relative focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
              title={t("sidebar.expand")}
              aria-label={t("sidebar.expand")}
            >
              <img
                src={`${import.meta.env.BASE_URL}sambryx/logo-mark.svg`}
                alt="Sambryx"
                className="h-7 w-auto shrink-0 group-hover:hidden transition-all"
              />
              <PanelLeftOpen className="w-6 h-6 hidden group-hover:block text-foreground" />
            </button>
          )}
        </div>

        {/* 1. Navigation */}
        <nav aria-label={t("sidebar.navigation")} className={`flex-1 py-3 flex flex-col gap-1 overflow-y-auto custom-scrollbar ${
          isCollapsed ? "px-2 items-center" : "px-3"
        }`}>
          {navItems.map((item) => {
            const isActive = location.pathname.includes(item.path);
            const Icon = item.icon;
            return (
              <Link
                key={item.path}
                to={item.path}
                aria-current={isActive ? "page" : undefined}
                title={isCollapsed ? item.name : undefined}
                className={`flex items-center rounded-lg transition-colors text-sm font-medium focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground ${
                  isCollapsed
                    ? "justify-center w-10 h-10 p-0"
                    : "gap-3 px-3 py-2 w-full"
                } ${
                  isActive
                    ? "bg-accent text-on-accent font-medium shadow-xs"
                    : "text-text-muted hover:text-foreground hover:bg-surface-2"
                }`}
              >
                <Icon className={`w-4 h-4 shrink-0 ${isActive ? 'opacity-100' : 'opacity-70'}`} />
                {!isCollapsed && <span className="truncate">{item.name}</span>}
              </Link>
            );
          })}
        </nav>

        {/* 2. Server & Connection Status */}
        {userInfo.host ? (
          !isCollapsed ? (
            <div className="border-t border-border px-3 py-2.5 shrink-0 space-y-1.5">
              <div className="flex items-center justify-between gap-1.5 min-w-0">
                <div
                  className="flex items-center gap-1.5 min-w-0 flex-1 text-xs"
                  title={userInfo.host}
                >
                  <Server className="w-3.5 h-3.5 text-text-muted shrink-0" />
                  <span className="font-mono text-foreground font-medium truncate">
                    {userInfo.host}
                  </span>
                </div>
                <div className="flex items-center gap-0.5 shrink-0">
                  {serverStatus === false && (
                    <button
                      onClick={() => handleServiceControl("start")}
                      className="p-1 text-text-muted hover:text-status-active hover:bg-surface-2 rounded transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
                      title={t("dashboard.startService")}
                      aria-label={t("dashboard.startService")}
                    >
                      <Play className="w-3.5 h-3.5 fill-current" />
                    </button>
                  )}
                  {serverStatus === true && (
                    <button
                      onClick={() => handleServiceControl("stop")}
                      className="p-1 text-text-muted hover:text-status-error hover:bg-surface-2 rounded transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
                      title={t("dashboard.stop")}
                      aria-label={t("dashboard.stop")}
                    >
                      <Square className="w-3.5 h-3.5 fill-current" />
                    </button>
                  )}
                  <button
                    onClick={() => handleServiceControl("restart")}
                    className={`p-1 text-text-muted hover:text-foreground hover:bg-surface-2 rounded transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground ${
                      isRestarting ? "animate-spin text-foreground" : ""
                    }`}
                    title={t("sidebar.restart")}
                    aria-label={t("sidebar.restart")}
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>

              <div className="flex items-center gap-2 text-xs">
                <span className="relative flex h-2 w-2 shrink-0">
                  {serverStatus === true && (
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-status-active opacity-40"></span>
                  )}
                  <span className={`relative inline-flex rounded-full h-2 w-2 ${
                    serverStatus === true ? 'bg-status-active' : serverStatus === false ? 'bg-status-error' : 'bg-status-disabled'
                  }`} />
                </span>
                <span className="text-[11px] font-medium text-text-muted truncate">
                  {serverStatus === true ? t("sidebar.running") : serverStatus === false ? t("sidebar.stopped") : t("sidebar.wait")}
                </span>
              </div>
            </div>
          ) : (
            <div className="border-t border-border py-2 px-2 shrink-0 flex flex-col items-center">
              <div
                className="p-2 text-text-muted hover:text-foreground rounded-lg transition-colors relative cursor-default"
                title={`${userInfo.host} • ${serverStatus === true ? t("sidebar.running") : serverStatus === false ? t("sidebar.stopped") : t("sidebar.wait")}`}
              >
                <Server className="w-4 h-4" />
                <span className={`absolute top-1.5 right-1.5 w-2 h-2 rounded-full ring-2 ring-surface ${
                  serverStatus === true ? 'bg-status-active' : serverStatus === false ? 'bg-status-error' : 'bg-status-disabled'
                }`} />
              </div>
            </div>
          )
        ) : null}

        {/* 3. User & Session Actions */}
        {!isCollapsed ? (
          <div className="border-t border-border px-3 py-2.5 shrink-0 flex items-center justify-between gap-2 bg-surface-2/30">
            <div className="flex items-center gap-2.5 min-w-0 flex-1">
              <div className="w-7 h-7 rounded-full bg-accent/20 border border-accent/40 text-accent-text flex items-center justify-center font-bold text-xs uppercase tracking-wider shrink-0 select-none">
                {userInitial}
              </div>
              <div className="min-w-0 flex-1">
                <div
                  className="text-xs font-semibold text-foreground truncate leading-tight"
                  title={userInfo.user}
                >
                  {userInfo.user || t("sidebar.user")}
                </div>
              </div>
            </div>

            <button
              onClick={handleLogout}
              className="p-1.5 text-text-muted hover:text-status-error hover:bg-status-error/10 rounded-lg transition-colors shrink-0 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-status-error"
              title={t("sidebar.logout")}
              aria-label={t("sidebar.logout")}
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        ) : (
          <div className="border-t border-border py-2 px-2 shrink-0 flex flex-col items-center gap-1.5">
            <div
              className="w-8 h-8 rounded-full bg-accent/20 border border-accent/40 text-accent-text flex items-center justify-center font-bold text-xs uppercase tracking-wider select-none"
              title={userInfo.user ? `${userInfo.user} @ ${userInfo.host}` : undefined}
            >
              {userInitial}
            </div>
            <button
              onClick={handleLogout}
              className="p-2 text-text-muted hover:text-status-error hover:bg-status-error/10 rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-status-error"
              title={`${t("sidebar.logout")} (${userInfo.user})`}
              aria-label={t("sidebar.logout")}
            >
              <LogOut className="w-4 h-4" />
            </button>
            <button
              onClick={toggleDark}
              className="p-2 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
              title={isDark ? "Light mode" : "Dark mode"}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </button>
            <button
              onClick={toggleLang}
              className="p-2 text-text-muted hover:text-foreground hover:bg-surface-2 rounded-lg transition-colors text-[11px] font-bold uppercase tracking-wider focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
              title={lang === "ru" ? "Switch to English" : "Переключить на Русский"}
            >
              {lang}
            </button>
          </div>
        )}
      </aside>

      {/* Main content */}
      <div className="workspace-body flex-1 flex flex-col min-w-0">
        <header className="workspace-header border-b border-border bg-surface flex items-center justify-between gap-4 shrink-0">
          <Link to="/dashboard" className="workspace-mobile-brand md:hidden" aria-label="Sambryx">
            <img src={`${import.meta.env.BASE_URL}sambryx/logo-mark.svg`} alt="" />
            <span>Sambryx</span>
          </Link>
          <div className="workspace-search flex-1 max-w-xl">
            <GlobalSearch />
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <div className="workspace-mobile-tools flex md:hidden">
              <button onClick={toggleLang} title={lang === "ru" ? "Switch to English" : "Переключить на Русский"}>{lang.toUpperCase()}</button>
              <button onClick={toggleDark} aria-label={isDark ? t("sidebar.lightMode") : t("sidebar.darkMode")}>
                {isDark ? <Sun /> : <Moon />}
              </button>
              <button onClick={handleLogout} aria-label={t("sidebar.logout")}><LogOut /></button>
            </div>
            {/* GitHub Project & Issues Link */}
            <a
              href="https://github.com/AndreyJVM/sambryx"
              target="_blank"
              rel="noopener noreferrer"
              className="hidden sm:flex items-center gap-2 px-3 py-1.5 text-xs font-medium text-text-muted hover:text-foreground hover:bg-surface-2 rounded-lg border border-border transition-colors shadow-2xs group focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground"
              title="GitHub repository & issues"
            >
              <Github className="w-4 h-4 text-foreground/80 group-hover:text-foreground transition-colors shrink-0" />
              <span>GitHub</span>
            </a>
          </div>
        </header>

        <nav className="workspace-mobile-nav md:hidden" aria-label={t("sidebar.navigation")}>
          {navItems.map(({ name, path, icon: Icon }) => (
            <Link key={path} to={path} aria-current={location.pathname.includes(path) ? "page" : undefined}>
              <Icon aria-hidden="true" /><span>{name}</span>
            </Link>
          ))}
        </nav>
        <main id="workspace-content" tabIndex={-1} className="workspace-content flex-1 overflow-y-auto">
          <div className="max-w-7xl mx-auto">
            {children || <Outlet />}
          </div>
        </main>
      </div>
    </div>
  );
}
