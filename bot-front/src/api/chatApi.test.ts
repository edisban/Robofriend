import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RateLimitedError, streamChatMessage } from "./chatApi";

/** A fetch Response whose body arrives in the given byte chunks. */
function streamedResponse(chunks: Uint8Array[], status = 200) {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk));
      controller.close();
    },
  });
  return new Response(body, { status, headers: { "Content-Type": "text/event-stream" } });
}

/** Splits text into byte chunks of `size`, cutting through events, lines and multi-byte characters. */
function chunked(text: string, size: number) {
  const bytes = new TextEncoder().encode(text);
  const chunks: Uint8Array[] = [];
  for (let i = 0; i < bytes.length; i += size) chunks.push(bytes.slice(i, i + size));
  return chunks;
}

const SSE =
  'event: token\ndata: {"t":"Γεια σου! "}\n\n' +
  'event: token\ndata: {"t":"Πώς πήγε η μέρα;"}\n\n' +
  'event: done\ndata: {"reply":"Γεια σου! Πώς πήγε η μέρα;","botMessageId":12,"userMessageId":11}\n\n';

describe("streamChatMessage", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token";
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([1, 3, 7, 64, 10_000])("parses events however the stream is chunked (%i bytes)", async (size) => {
    fetchMock.mockResolvedValue(streamedResponse(chunked(SSE, size)));
    const tokens: string[] = [];

    const result = await streamChatMessage({ message: "γεια" }, (t) => tokens.push(t));

    expect(tokens).toEqual(["Γεια σου! ", "Πώς πήγε η μέρα;"]);
    expect(result).toEqual({ reply: "Γεια σου! Πώς πήγε η μέρα;", botMessageId: 12, userMessageId: 11 });
  });

  it("handles CRLF line endings and a final event without a trailing blank line", async () => {
    const crlf = 'event: token\r\ndata: {"t":"Hi"}\r\n\r\nevent: done\r\ndata: {"reply":"Hi"}';
    fetchMock.mockResolvedValue(streamedResponse(chunked(crlf, 5)));
    const tokens: string[] = [];

    const result = await streamChatMessage({ message: "hey" }, (t) => tokens.push(t));

    expect(tokens).toEqual(["Hi"]);
    expect(result.reply).toBe("Hi");
  });

  it("sends the message with the CSRF header and timezone", async () => {
    fetchMock.mockResolvedValue(streamedResponse(chunked(SSE, 100)));

    await streamChatMessage({ message: "hello" }, () => undefined);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/chat/stream");
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("test-token");
    expect(JSON.parse(init.body)).toMatchObject({ message: "hello", timeZone: expect.any(String) });
  });

  it("fails when the stream ends without a done event", async () => {
    fetchMock.mockResolvedValue(streamedResponse(chunked('event: token\ndata: {"t":"Hal"}\n\n', 4)));

    await expect(streamChatMessage({ message: "x" }, () => undefined)).rejects.toThrow(/ended unexpectedly/);
  });

  it("turns a 429 into a RateLimitedError that says which limit was hit", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ error: "limit", limit: "day" }), { status: 429 }),
    );

    const error = await streamChatMessage({ message: "x" }, () => undefined).catch((e) => e);

    expect(error).toBeInstanceOf(RateLimitedError);
    expect(error.limit).toBe("day");
  });
});
