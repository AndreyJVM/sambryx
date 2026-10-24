export interface ApiResponse<T = unknown> {
  success: boolean;
  message: string;
  data: T;
}

export class ApiError extends Error {
  constructor(public message: string, public status?: number, public details?: {code?: string; fingerprint?: string}) {
    super(message);
    this.name = "ApiError";
  }
}

const XSRF_TOKEN_COOKIE_PATTERN = /(^| )XSRF-TOKEN=([^;]+)/;

function getCsrfToken(): string | null {
  const match = document.cookie.match(XSRF_TOKEN_COOKIE_PATTERN);
  return match ? match[2] : null;
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? "GET").toUpperCase();
  const unsafe = !["GET", "HEAD", "OPTIONS", "TRACE"].includes(method);
  let csrf = getCsrfToken();
  if (unsafe && !csrf) {
    await request("/api/auth/environment");
    csrf = getCsrfToken();
    if (!csrf) throw new ApiError("Не удалось получить CSRF-токен. Обновите страницу.", 403);
  }
  const headers = new Headers(options.headers);
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");

  if (csrf && unsafe) {
    headers.set("X-XSRF-TOKEN", csrf);
  }

  try {
    const res = await fetch(url, { ...options, headers });
    
    // Some endpoints might return 401
    if (res.status === 401) {
      if (window.location.pathname !== "/ui/login") {
        window.location.href = "/ui/login";
      }
      throw new ApiError("Сессия истекла", 401);
    }

    // Try to parse JSON response
    let json;
    try {
      json = await res.json();
    } catch (e) {
      if (!res.ok) throw new ApiError("Ошибка ответа", res.status);
      throw new ApiError("Неожиданный формат ответа");
    }

    if (!res.ok || (Object.prototype.hasOwnProperty.call(json, "success") && !json.success)) {
      throw new ApiError(json.message || "Произошла ошибка при выполнении запроса", res.status, json.data);
    }

    return (Object.prototype.hasOwnProperty.call(json, "success") && Object.prototype.hasOwnProperty.call(json, "data")) ? json.data : json;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError("Ошибка сетевого соединения");
  }
}

export const api = {
  get: <T>(url: string) => request<T>(url),
  post: <T>(url: string, body?: unknown) => request<T>(url, { method: "POST", body: JSON.stringify(body) }),
  put: <T>(url: string, body?: unknown) => request<T>(url, { method: "PUT", body: JSON.stringify(body) }),
  delete: <T>(url: string) => request<T>(url, { method: "DELETE" }),
};
