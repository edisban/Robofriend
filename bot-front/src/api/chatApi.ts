import {
  API_BASE_URL,
  AUTH_EXPIRED_EVENT,
  apiFetch,
  getToken,
} from "./apiClient";

export type ChatMessage = {
  id?: number;
  sender: "USER" | "BOT";
  content: string;
  createdAt?: string;
};

export type ChatRequest = {
  userId: number;
  message: string;
};

export type StreamRequest =
  | { message: string; replaceFromId?: number }
  | { regenerate: true };

export type StreamResult = {
  reply: string;
  botMessageId?: number;
  userMessageId?: number;
};

export async function getChatHistory(
  userId: number,
  afterId?: number,
): Promise<ChatMessage[]> {
  const query = afterId !== undefined ? `?afterId=${afterId}` : "";
  return apiFetch<ChatMessage[]>(`/api/chat/history/${userId}${query}`);
}

export async function sendChatMessage(
  payload: ChatRequest,
): Promise<{ reply: string }> {
  return apiFetch<{ reply: string }>("/api/chat/send", {
    method: "POST",
    body: JSON.stringify({ ...payload, timeZone: localTimeZone() }),
  });
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
  const token = getToken();
  const response = await fetch(`${API_BASE_URL}/api/chat/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ ...request, timeZone: localTimeZone() }),
  });

  if (!response.ok || !response.body) {
    if (token && (response.status === 401 || response.status === 403)) {
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
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
