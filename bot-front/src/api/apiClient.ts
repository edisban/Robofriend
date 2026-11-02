export const API_BASE_URL =
  import.meta.env.VITE_API_URL ||
  import.meta.env.VITE_API_BASE_URL ||
  "";

const TOKEN_KEY = "robofriend-user";

/** Fired when the backend rejects the stored token, so the app can sign out. */
export const AUTH_EXPIRED_EVENT = "robofriend:auth-expired";

export function getToken(): string | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed?.token ?? null;
  } catch {
    return null;
  }
}

export async function apiFetch<T>(
  input: string,
  init?: RequestInit,
): Promise<T> {
  const { headers, ...rest } = init ?? {};

  const token = getToken();

  const response = await fetch(`${API_BASE_URL}${input}`, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(headers ?? {}),
    },
  });

  if (!response.ok) {
    if (token && (response.status === 401 || response.status === 403)) {
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
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
