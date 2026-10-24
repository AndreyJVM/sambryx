import { getErrorMessage } from "../lib/errors";
import { useEffect, useState } from "react";
import { Lock, Key, ArrowRight, AlertTriangle, SlidersHorizontal, Folders, Eye, EyeOff, Moon, Sun } from "lucide-react";
import { Input } from "../components/ui/input";
import { api } from "../lib/api";
import "./login.css";

export default function LoginPage() {
  const [host, setHost] = useState("");
  const [port, setPort] = useState("22");
  const [showPort, setShowPort] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isDark, setIsDark] = useState(() => {
    const saved = localStorage.getItem("theme");
    return saved === "dark" || (!saved && window.matchMedia("(prefers-color-scheme: dark)").matches);
  });

  useEffect(() => {
    document.documentElement.classList.toggle("dark", isDark);
  }, [isDark]);

  const toggleTheme = () => {
    const next = !isDark;
    localStorage.setItem("theme", next ? "dark" : "light");
    setIsDark(next);
  };
  const [privateKey, setPrivateKey] = useState("");
  const [useKeyAuth, setUseKeyAuth] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");

    try {
      let finalHost = host.trim();
      let finalPort = parseInt(port, 10);
      if (isNaN(finalPort)) finalPort = 22;

      // Support host:port if user entered it directly into host input
      if (/^[^:]+:\d+$/.test(finalHost)) {
        const [h, p] = finalHost.split(":");
        if (p && !isNaN(parseInt(p, 10))) {
          finalHost = h;
          finalPort = parseInt(p, 10);
        }
      }

      await api.post("/api/auth/login", {
        host: finalHost,
        port: finalPort,
        username,
        authType: useKeyAuth ? "key" : "password",
        password: useKeyAuth ? undefined : password,
        privateKey: useKeyAuth ? privateKey : undefined,
      });
      // Simple redirect since auth state isn't managed globally here
      window.location.href = "/ui/";
    } catch (err) {
      setError(getErrorMessage(err) || "Ошибка авторизации");
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-story" aria-label="О Sambryx">
        <div className="login-brand"><Folders aria-hidden="true" /><span>Sambryx</span></div>
        <div className="login-story-copy">
          <p className="login-story-title">Порядок<br />в общих папках.</p>
          <p>Пользователи, доступ и файлы — в одной панели.</p>
        </div>
        <svg className="login-art" viewBox="0 0 700 560" fill="none" aria-hidden="true">
          <rect x="-65" y="15" width="390" height="440" rx="100" fill="var(--login-sage)" />
          <circle cx="548" cy="135" r="94" fill="var(--login-paper)" />
          <path d="M200 65H403C485 65 505 98 505 176V257C505 294 553 294 601 294V470" stroke="var(--login-ink)" strokeWidth="1.4" />
          <path d="M136 169C136 151 150 137 168 137H246L278 169H410C428 169 442 183 442 201V424H136V169Z" fill="var(--login-middle)" />
          <rect x="337" y="192" width="155" height="217" rx="24" fill="var(--login-paper)" />
          <path d="M370 235H457M370 257H435M370 279H450" stroke="var(--login-sage)" strokeWidth="6" />
          <rect x="77" y="227" width="210" height="263" rx="50" fill="var(--login-paper)" />
          <path d="M212 334C212 314 228 298 248 298H313L344 329H495C515 329 531 345 531 365V525H212V334Z" fill="var(--login-ink)" />
          <path d="M-15 417H301C333 417 351 435 351 467V560" stroke="var(--login-paper)" strokeWidth="1.4" />
          <path d="M99 525H382C418 525 435 508 435 472V433" stroke="var(--login-ink)" strokeWidth="1.4" />
          <circle cx="505" cy="257" r="11" fill="var(--login-ink)" />
          <circle cx="435" cy="433" r="8" fill="var(--login-middle)" />
          <circle cx="114" cy="397" r="22" fill="var(--login-sage)" />
          <g fill="var(--login-middle)">
            {[0, 1, 2, 3].flatMap(row => [0, 1, 2, 3].map(column => (
              <circle key={`${row}-${column}`} cx={568 + column * 20} cy={380 + row * 20} r="1.6" />
            )))}
          </g>
        </svg>
      </section>
      <section className="login-panel" aria-labelledby="login-title">
        <button type="button" className="login-theme-toggle" onClick={toggleTheme}
          aria-label={isDark ? "Включить светлую тему" : "Включить тёмную тему"}>
          {isDark ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
        </button>
        <div className="login-mobile-brand login-brand"><Folders aria-hidden="true" /><span>Sambryx</span></div>
        <div className="login-form-wrap">
          <header className="login-heading">
            <h1 id="login-title">Подключение к серверу</h1>
            <p>Введите данные для подключения по SSH</p>
          </header>
        <div>
          <form onSubmit={handleLogin} className="space-y-4" aria-busy={loading}>
            {error && (
              <div
                role="alert"
                className="bg-surface-2 text-status-error p-3 rounded-lg text-xs font-medium border border-status-error/30 flex items-start gap-2.5"
              >
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                <span className="leading-relaxed">{error}</span>
              </div>
            )}

            <div className="login-methods">
              <span className="block text-[11px] font-medium text-text-muted text-center">
                Способ аутентификации
              </span>
              <div
                className="flex bg-surface-2 p-1 rounded-lg border border-border"
                role="group"
                aria-label="Способ аутентификации"
              >
                <button
                  type="button"
                  onClick={() => setUseKeyAuth(false)}
                  aria-pressed={!useKeyAuth}
                  className={`flex-1 py-1.5 text-xs font-medium rounded-md transition-all ${
                    !useKeyAuth
                      ? 'bg-surface text-foreground shadow-xs font-semibold'
                      : 'text-text-muted hover:text-foreground'
                  }`}
                >
                  Пароль
                </button>
                <button
                  type="button"
                  onClick={() => setUseKeyAuth(true)}
                  aria-pressed={useKeyAuth}
                  className={`flex-1 py-1.5 text-xs font-medium rounded-md transition-all flex items-center justify-center gap-1.5 ${
                    useKeyAuth
                      ? 'bg-surface text-foreground shadow-xs font-semibold'
                      : 'text-text-muted hover:text-foreground'
                  }`}
                >
                  <Key className="w-3.5 h-3.5" />
                  <span>SSH-ключ</span>
                </button>
              </div>
            </div>

            {/* Main scenario: 1. Server Host with prominent port toggle pill */}
            <div className="space-y-2">
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label
                    htmlFor="login-host"
                    className="block text-xs font-semibold text-text-muted uppercase tracking-wider"
                  >
                    Хост / IP
                  </label>
                  <button
                    type="button"
                    onClick={() => setShowPort(!showPort)}
                    className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium border transition-colors shadow-2xs focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-foreground ${
                      showPort
                        ? "bg-accent/25 border-accent/60 text-accent-text font-semibold"
                        : "bg-surface-2 border-border-strong text-foreground hover:bg-surface hover:border-foreground/30"
                    }`}
                    title={showPort ? "Скрыть настройку порта" : "Изменить порт подключения (по умолчанию 22)"}
                    aria-expanded={showPort}
                    aria-controls="login-port-options"
                  >
                    <SlidersHorizontal className="w-3 h-3 shrink-0 opacity-70" />
                    <span>{showPort ? "Скрыть порт" : `Порт: ${port || "22"}`}</span>
                  </button>
                </div>
                <Input
                  id="login-host"
                  name="host"
                  autoComplete="host"
                  required
                  value={host}
                  onChange={e => setHost(e.target.value)}
                  placeholder="192.168.1.100"
                  className="bg-surface-2 border-border-strong text-foreground focus-visible:border-border-strong focus-visible:ring-2 focus-visible:ring-accent-text/30 text-sm h-10 rounded-lg transition-colors"
                />
              </div>

              {/* Separate Port setting (revealed on click) */}
              {showPort && (
                <div id="login-port-options" className="space-y-1.5 animate-in fade-in slide-in-from-top-1 duration-150">
                  <div className="flex items-center justify-between">
                    <label
                      htmlFor="login-port"
                      className="block text-xs font-semibold text-text-muted uppercase tracking-wider"
                    >
                      Порт SSH
                    </label>
                    {port !== "22" && (
                      <button
                        type="button"
                        onClick={() => setPort("22")}
                        className="text-[11px] font-medium text-text-muted hover:text-foreground transition-colors focus-visible:outline-none focus-visible:underline"
                      >
                        Сбросить на 22
                      </button>
                    )}
                  </div>
                  <Input
                    id="login-port"
                    name="port"
                    required
                    value={port}
                    onChange={e => setPort(e.target.value)}
                    type="number"
                    placeholder="22"
                    className="bg-surface-2 border-border-strong text-foreground focus-visible:border-border-strong focus-visible:ring-2 focus-visible:ring-accent-text/30 text-sm h-10 rounded-lg transition-colors"
                  />
                </div>
              )}
            </div>

            {/* Main scenario: 2. Login (Username) */}
            <div className="space-y-1.5">
              <label
                htmlFor="login-username"
                className="block text-xs font-semibold text-text-muted uppercase tracking-wider"
              >
                Пользователь
              </label>
              <Input
                id="login-username"
                name="username"
                autoComplete="username"
                required
                value={username}
                onChange={e => setUsername(e.target.value)}
                placeholder="root"
                className="bg-surface-2 border-border-strong text-foreground focus-visible:border-border-strong focus-visible:ring-2 focus-visible:ring-accent-text/30 text-sm h-10 rounded-lg transition-colors"
              />
            </div>

            {/* Main scenario: 3. Password OR Key input */}
            {!useKeyAuth ? (
              <div className="space-y-1.5">
                <label
                  htmlFor="login-password"
                  className="block text-xs font-semibold text-text-muted uppercase tracking-wider"
                >
                  Пароль
                </label>
                <div className="relative">
                  <Input
                    id="login-password"
                    name="password"
                    autoComplete="current-password"
                    required
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    type={showPassword ? "text" : "password"}
                    placeholder="••••••••"
                    className="bg-surface-2 border-border-strong pl-10 pr-12 text-foreground focus-visible:border-border-strong focus-visible:ring-2 focus-visible:ring-accent-text/30 text-sm h-10 rounded-lg transition-colors"
                  />
                  <Lock className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                  <button type="button" className="login-password-toggle" aria-controls="login-password"
                    aria-label={showPassword ? "Скрыть пароль" : "Показать пароль"}
                    aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>
                    {showPassword ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                  </button>
                </div>
              </div>
            ) : (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label
                    htmlFor="login-privatekey"
                    className="block text-xs font-semibold text-text-muted uppercase tracking-wider"
                  >
                    Приватный ключ
                  </label>
                  <span className="text-[11px] font-mono text-text-muted">RSA / Ed25519</span>
                </div>
                <textarea
                  id="login-privatekey"
                  name="privateKey"
                  required
                  value={privateKey}
                  onChange={e => setPrivateKey(e.target.value)}
                  placeholder={"-----BEGIN OPENSSH PRIVATE KEY-----\n..."}
                  className="w-full h-28 text-xs font-mono leading-relaxed p-3 bg-surface-2 border border-border-strong rounded-lg text-foreground placeholder:text-text-muted focus:outline-none focus:border-border-strong focus:ring-2 focus:ring-accent-text/30 transition-colors resize-none"
                />
              </div>
            )}

            {/* Main scenario: 4. Submit button with fixed dimensions to prevent jump */}
            <button
              disabled={loading}
              type="submit"
              className="login-submit w-full font-medium flex items-center justify-center gap-2 h-11 rounded-lg text-sm mt-5 transition-all hover:opacity-90 active:scale-[0.99] disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-foreground group shadow-xs shrink-0"
            >
              {loading ? (
                <>
                  <span className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin shrink-0" />
                  <span>Подключение...</span>
                </>
              ) : (
                <>
                  <span>Подключиться</span>
                  <ArrowRight className="w-4 h-4 opacity-60 group-hover:opacity-100 group-hover:translate-x-0.5 transition-all shrink-0" />
                </>
              )}
            </button>

          </form>
        </div>

          <p className="login-connection-note"><Lock aria-hidden="true" />Подключение по SSH</p>
        </div>
        <footer className="login-footer">Sambryx &copy; {new Date().getFullYear()}</footer>
      </section>
    </main>
  );
}
