import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as chatApi from "../api/chatApi";
import { useChat } from "./useChat";

// The network calls are faked; RateLimitedError and the page size stay real.
vi.mock("../api/chatApi", async (importActual) => ({
  ...(await importActual<typeof import("../api/chatApi")>()),
  getChatHistory: vi.fn(),
  dailyCheckIn: vi.fn(),
  streamChatMessage: vi.fn(),
  deleteChatMessage: vi.fn(),
  deleteConversation: vi.fn(),
}));

const api = vi.mocked(chatApi);

function serverMessage(id: number, sender: "USER" | "BOT", content: string): chatApi.ChatMessage {
  return { id, sender, content, createdAt: "2026-11-10T09:00:00Z" };
}

function setup() {
  const onActivity = vi.fn();
  const notify = vi.fn();
  const hook = renderHook(() => useChat({ userId: 7, username: "eleni", onActivity, notify }));
  return { ...hook, onActivity, notify };
}

const shape = (messages: { role: string; text: string; id?: number }[]) =>
  messages.map((m) => [m.role, m.text, m.id]);

describe("useChat", () => {
  beforeEach(() => {
    api.getChatHistory.mockResolvedValue([serverMessage(1, "BOT", "Hi Eleni!")]);
    api.dailyCheckIn.mockResolvedValue(null);
  });

  it("sends a message and shows the streamed reply with the ids the server gave", async () => {
    api.streamChatMessage.mockImplementation(async (_request, onToken) => {
      onToken("Hey ");
      onToken("there!");
      return { reply: "Hey there!", userMessageId: 2, botMessageId: 3 };
    });
    const { result, onActivity } = setup();
    await waitFor(() => expect(result.current.messages[0].id).toBe(1));

    await act(() => result.current.send("Hello"));

    expect(api.streamChatMessage).toHaveBeenCalledWith({ message: "Hello" }, expect.any(Function));
    expect(shape(result.current.messages)).toEqual([
      ["bot", "Hi Eleni!", 1],
      ["user", "Hello", 2],
      ["bot", "Hey there!", 3],
    ]);
    expect(result.current.isSending).toBe(false);
    expect(onActivity).toHaveBeenCalled();
  });

  it("shows a friendly bubble when the rate limit is hit", async () => {
    api.streamChatMessage.mockRejectedValue(new chatApi.RateLimitedError("minute"));
    const { result } = setup();
    await waitFor(() => expect(result.current.messages[0].id).toBe(1));

    await act(() => result.current.send("one more"));

    expect(result.current.messages.at(-1)?.text).toMatch(/Slow down a little/);
    expect(result.current.isSending).toBe(false);
  });

  it("removes a message right away and puts it back if the server refuses", async () => {
    api.getChatHistory.mockResolvedValue([
      serverMessage(1, "USER", "first"),
      serverMessage(2, "BOT", "second"),
      serverMessage(3, "USER", "third"),
    ]);
    let refuse: (reason: Error) => void = () => undefined;
    api.deleteChatMessage.mockReturnValue(new Promise((_, reject) => (refuse = reject)));
    const { result, notify } = setup();
    await waitFor(() => expect(result.current.messages).toHaveLength(3));

    act(() => result.current.remove(1));
    expect(shape(result.current.messages)).toEqual([["user", "first", 1], ["user", "third", 3]]);
    expect(api.deleteChatMessage).toHaveBeenCalledWith(2);

    await act(async () => refuse(new Error("500")));

    expect(shape(result.current.messages)).toEqual([
      ["user", "first", 1],
      ["bot", "second", 2],
      ["user", "third", 3],
    ]);
    expect(notify).toHaveBeenCalledWith(expect.stringMatching(/Couldn't delete/));
  });

  it("loads the page before the oldest message and knows when it reached the start", async () => {
    const page = (from: number, to: number) =>
      Array.from({ length: to - from + 1 }, (_, i) => serverMessage(from + i, "BOT", `m${from + i}`));
    api.getChatHistory.mockImplementation(async (_userId, cursor) =>
      cursor && "beforeId" in cursor ? page(1, 10) : page(11, 60),
    );
    const { result } = setup();
    await waitFor(() => expect(result.current.messages).toHaveLength(50));
    expect(result.current.hasOlder).toBe(true);

    const beforePrepend = vi.fn();
    await act(() => result.current.loadOlder(beforePrepend));

    expect(api.getChatHistory).toHaveBeenLastCalledWith(7, { beforeId: 11 });
    expect(beforePrepend).toHaveBeenCalledOnce();
    expect(result.current.messages).toHaveLength(60);
    expect(result.current.messages[0].text).toBe("m1");
    expect(result.current.hasOlder).toBe(false);
  });

  it("keeps the loaded history when the daily check-in fails", async () => {
    api.dailyCheckIn.mockRejectedValue(new Error("429"));
    const { result } = setup();

    await waitFor(() => expect(api.dailyCheckIn).toHaveBeenCalled());
    // Let the refused check-in settle (and React re-render) before looking: the bug this guards
    // against replaced the history only after the rejection was handled.
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));

    expect(shape(result.current.messages)).toEqual([["bot", "Hi Eleni!", 1]]);
  });
});
