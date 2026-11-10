import { apiFetch, authFetch } from "./apiClient";

export type ChatMessage = {
  id?: number;
  sender: "USER" | "BOT";
  content: string;
  createdAt?: string;
};

export type StreamRequest =
  | { message: string; replaceFromId?: number }
  | { regenerate: true };

export type StreamResult = {
  reply: string;
  botMessageId?: number;
  userMessageId?: number;
};

/** The server refused a reply because the user hit their per-minute or daily message limit. */
export class RateLimitedError extends Error {
  readonly limit: "minute" | "day";

  constructor(limit: "minute" | "day") {
    super(`Chat rate limit reached (${limit})`);
    this.name = "RateLimitedError";
    this.limit = limit;
  }
}

export async function getChatHistory(
  userId: number,
  afterId?: number,
): Promise<ChatMessage[]> {
  const query = afterId !== undefined ? `?afterId=${afterId}` : "";
  return apiFetch<ChatMessage[]>(`/api/chat/history/${userId}${query}`);
}

/** RoboFriend's first message of the day, if it has something to bring up; otherwise null. */
export async function dailyCheckIn(): Promise<ChatMessage | null> {
  const message = await apiFetch<ChatMessage | undefined>("/api/chat/checkin", {
    method: "POST",
    body: JSON.stringify({ timeZone: localTimeZone() }),
  });
  return message ?? null;
}

/** Permanently deletes the whole conversation; optionally RoboFriend's memories of the user too. */
export async function deleteConversation(forgetMemories: boolean): Promise<void> {
  await apiFetch<void>(`/api/chat/messages?forgetMemories=${forgetMemories}`, {
    method: "DELETE",
  });
}

export async function deleteChatMessage(messageId: number): Promise<void> {
  await apiFetch<void>(`/api/chat/messages/${messageId}`, { method: "DELETE" });
}

/**
 * Sends a message (or a regenerate/edit request) and calls `onToken` with each piece of the
 * reply as RoboFriend writes it. Resolves with the final reply once it's complete.
 */
export async function streamChatMessage(
  request: StreamRequest,
  onToken: (text: string) => void,
): Promise<StreamResult> {
  const response = await authFetch("/api/chat/stream", {
    method: "POST",
    headers: { Accept: "text/event-stream" },
    body: JSON.stringify({ ...request, timeZone: localTimeZone() }),
  });

  if (response.status === 429) {
    const body = (await response.json().catch(() => ({}))) as { limit?: string };
    throw new RateLimitedError(body.limit === "day" ? "day" : "minute");
  }
  if (!response.ok || !response.body) {
    throw new Error(`Request failed: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let result: StreamResult | null = null;

  const handleEvent = (raw: string) => {
    let event = "message";
    const data: string[] = [];
    for (const line of raw.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (data.length === 0) return;
    const payload = JSON.parse(data.join("\n"));
    if (event === "token") onToken(payload.t as string);
    else if (event === "done") result = payload as StreamResult;
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary = buffer.search(/\r?\n\r?\n/);
    while (boundary !== -1) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, "");
      handleEvent(raw);
      boundary = buffer.search(/\r?\n\r?\n/);
    }
  }
  if (buffer.trim()) handleEvent(buffer);

  if (!result) throw new Error("The reply stream ended unexpectedly");
  return result;
}

// The timezone lets RoboFriend greet at the right time and schedule reminders in local time.
function localTimeZone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
