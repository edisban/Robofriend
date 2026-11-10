import type { Page, Route } from "@playwright/test";

/** A chat message as the API returns it. */
export type ApiMessage = { id: number; sender: "USER" | "BOT"; content: string; createdAt: string };

type FakeApiOptions = {
  /** Signed in already (otherwise the app starts on the sign-in screen). */
  signedInAs?: string;
  /** The whole stored conversation, oldest first; history pages are cut from it like the server does. */
  history?: ApiMessage[];
  /** Answers POST /api/chat/stream; defaults to a short streamed reply. */
  onStream?: (route: Route, body: { message?: string }) => Promise<void>;
};

const USER_ID = 7;
/** The password the fake account has, for confirming account deletion. */
export const PASSWORD = "correct-horse-1";

/** Builds a server-sent-events body: token events for each piece, then the done event. */
export function sseReply(pieces: string[], ids = { userMessageId: 1001, botMessageId: 1002 }) {
  const events = pieces.map((t) => `event: token\ndata: ${JSON.stringify({ t })}\n\n`);
  events.push(`event: done\ndata: ${JSON.stringify({ reply: pieces.join(""), ...ids })}\n\n`);
  return events.join("");
}

/**
 * Fakes the RoboFriend API inside the browser. Returns the requests the app made, so tests can
 * check what it sent (e.g. which history page it asked for).
 */
export async function fakeApi(page: Page, options: FakeApiOptions = {}) {
  let user = options.signedInAs ? { userId: USER_ID, username: options.signedInAs } : null;
  const history = options.history ?? [];
  const requests: { method: string; path: string; body?: unknown }[] = [];
  let displayName: string | null = null;
  let birthday: string | null = null;
  const profile = () => ({
    username: user?.username,
    displayName,
    birthday,
    memberSince: "2026-10-01T10:00:00Z",
  });

  // Only real API paths: a glob like "**/api/**" would also catch the app's own source files
  // that the dev server serves from /src/api/.
  await page.route((url) => url.pathname.startsWith("/api/"), async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()!) : undefined;
    requests.push({ method, path: path + url.search, body });
    const json = (status: number, data?: unknown) =>
      route.fulfill({ status, contentType: "application/json", body: data === undefined ? "" : JSON.stringify(data) });

    if (path === "/api/auth/csrf") return json(200, {});
    if (path === "/api/auth/me") return user ? json(200, user) : json(401, { error: "Not signed in" });
    if (path === "/api/auth/refresh") return json(401, { error: "Not signed in" });
    if (path === "/api/auth/register" || path === "/api/auth/login") {
      user = { userId: USER_ID, username: body.username };
      return json(200, user);
    }
    if (path === "/api/auth/logout") return json(204);

    if (path === `/api/chat/history/${USER_ID}`) {
      const limit = Number(url.searchParams.get("limit") ?? 50);
      const afterId = url.searchParams.get("afterId");
      const beforeId = url.searchParams.get("beforeId");
      if (afterId !== null) return json(200, history.filter((m) => m.id > Number(afterId)).slice(0, limit));
      const before = beforeId === null ? history : history.filter((m) => m.id < Number(beforeId));
      return json(200, before.slice(-limit));
    }
    if (path === "/api/chat/checkin") return json(204);
    if (path === "/api/chat/stream") {
      if (options.onStream) return options.onStream(route, body);
      return route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: sseReply(["Hey! ", "Nice to ", "meet you 😊"]),
      });
    }
    if (path === "/api/profile") {
      if (!user) return json(401, { error: "Not signed in" });
      if (method === "GET") return json(200, profile());
      if (method === "PUT") {
        displayName = body.displayName || null;
        birthday = body.birthday;
        return json(200, profile());
      }
      if (method === "DELETE") {
        if (body.password !== PASSWORD) return json(403, { error: "Wrong password." });
        user = null;
        return json(204);
      }
    }
    if (path === "/api/lists") return json(200, []);
    if (path.startsWith("/api/reminders/")) return json(200, []);

    return json(404, { error: "Not faked: " + path });
  });

  return requests;
}

/** `count` alternating user/bot messages with ids 1..count, a minute apart. */
export function conversation(count: number): ApiMessage[] {
  const start = Date.parse("2026-11-09T09:00:00Z");
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    sender: i % 2 === 0 ? "USER" : "BOT",
    content: `message ${i + 1}`,
    createdAt: new Date(start + i * 60_000).toISOString(),
  }));
}
