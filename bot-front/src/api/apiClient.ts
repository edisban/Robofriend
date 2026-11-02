export const API_BASE_URL =
  import.meta.env.VITE_API_URL ||
  import.meta.env.VITE_API_BASE_URL ||
  "";

/** Fired when the session can't be renewed, so the app can sign out. */
export const AUTH_EXPIRED_EVENT = "robofriend:auth-expired";

// The session lives in HttpOnly cookies the page can't read. Spring Security puts a CSRF token in
// a readable cookie; state-changing requests echo it back in a header.
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function readCookie(name: string): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

async function ensureCsrfCookie() {
  if (!readCookie(CSRF_COOKIE)) {
    await fetch(`${API_BASE_URL}/api/auth/csrf`, { credentials: "same-origin" });
  }
}

const NO_REFRESH_ENDPOINTS = [
  "/api/auth/login",
  "/api/auth/register",
  "/api/auth/refresh",
  "/api/auth/logout",
];

let refreshInFlight: Promise<boolean> | null = null;

/**
 * Renews the short-lived access cookie using the refresh cookie. Concurrent callers share one
 * request, because the refresh token is single-use.
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      await ensureCsrfCookie();
      const res = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
        method: "POST",
        credentials: "same-origin",
        headers: { [CSRF_HEADER]: readCookie(CSRF_COOKIE) ?? "" },
      });
      return res.ok;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

type AuthFetchOptions = {
  /** Announce AUTH_EXPIRED when the session is gone (off for the start-up "who am I" check). */
  notifyExpired?: boolean;
};

/**
 * fetch() for the API: sends the session cookies and CSRF header, and on a 401 renews the
 * session once and retries.
 */
export async function authFetch(
  input: string,
  init: RequestInit = {},
  { notifyExpired = true }: AuthFetchOptions = {},
  retry = true,
): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const changesState = !["GET", "HEAD", "OPTIONS"].includes(method);
  if (changesState) await ensureCsrfCookie();

  const response = await fetch(`${API_BASE_URL}${input}`, {
    ...init,
    credentials: "same-origin",
    headers: {
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...(changesState ? { [CSRF_HEADER]: readCookie(CSRF_COOKIE) ?? "" } : {}),
      ...(init.headers ?? {}),
    },
  });

  // A 401 from these means "wrong password" or "session over", not "access token expired".
  const isSessionEndpoint = NO_REFRESH_ENDPOINTS.some((path) => input.startsWith(path));
  if (response.status === 401 && retry && !isSessionEndpoint) {
    if (await refreshSession()) {
      return authFetch(input, init, { notifyExpired }, false);
    }
    if (notifyExpired) window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
  }
  return response;
}

export async function apiFetch<T>(
  input: string,
  init?: RequestInit,
  options?: AuthFetchOptions,
): Promise<T> {
  const response = await authFetch(input, init, options);

  if (!response.ok) {
    let message = `Request failed: ${response.status}`;
    try {
      const body = (await response.json()) as {
        message?: string;
        error?: string;
      };
      if (body?.message) {
        message = body.message;
      } else if (body?.error) {
        message = body.error;
      }
    } catch {
      void 0;
    }
    throw new Error(message);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    return undefined as T;
  }
}
