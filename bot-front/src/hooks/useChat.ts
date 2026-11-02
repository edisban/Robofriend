import { useCallback, useEffect, useRef, useState } from "react";
import {
  dailyCheckIn,
  deleteChatMessage,
  deleteConversation,
  getChatHistory,
  streamChatMessage,
  type ChatMessage,
  type StreamRequest,
} from "../api/chatApi";
import type { Message } from "../components/chat/ChatWindow";
import { timeOfDayGreeting } from "../utils/greeting";
import { createTypewriter } from "../utils/typewriter";

const POLL_INTERVAL_MS = 20000;
const FALLBACK_REPLY = "Hmm, I couldn't reach the server just now. Mind trying that again?";

type UseChatOptions = {
  userId: number;
  username: string;
  /** Called after anything that may have changed reminders or lists. */
  onActivity: () => void;
  /** Shows a short notice to the user. */
  notify: (text: string) => void;
};

function welcomeMessage(username: string): Message {
  return {
    role: "bot",
    text: `${timeOfDayGreeting()}, ${username}! 👋 I'm RoboFriend. How are you doing today?`,
    createdAt: new Date().toISOString(),
  };
}

function fromServer(item: ChatMessage): Message {
  return {
    id: item.id,
    role: item.sender === "USER" ? "user" : "bot",
    text: item.content,
    createdAt: item.createdAt,
  };
}

/** A system notification, but only when the tab is in the background. */
function notifyIfHidden(body: string) {
  if (!document.hidden || !("Notification" in window)) return;
  if (Notification.permission !== "granted") return;
  try {
    new Notification("RoboFriend", { body, icon: "/favicon.svg" });
  } catch {
    void 0;
  }
}

/**
 * The conversation: loads history, streams replies (send, regenerate, edit), deletes messages,
 * shows RoboFriend's daily check-in, and picks up messages it sends on its own (reminders).
 */
export function useChat({ userId, username, onActivity, notify }: UseChatOptions) {
  const [messages, setMessages] = useState<Message[]>(() => [welcomeMessage(username)]);
  const [isSending, setIsSending] = useState(false);
  const [replyStarted, setReplyStarted] = useState(false);
  // Refs let timers and callbacks read the latest values without re-subscribing.
  const messagesRef = useRef(messages);
  const sendingRef = useRef(false);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const setBusy = (busy: boolean) => {
    sendingRef.current = busy;
    setIsSending(busy);
    setReplyStarted(false);
  };

  /** Types a new bot message into the chat word by word, appending it on the first word. */
  const typeNewBotMessage = useCallback((base: Partial<Message> = {}) => {
    let started = false;
    return createTypewriter((shown) => {
      if (!started) {
        started = true;
        setReplyStarted(true);
        setMessages((prev) => [
          ...prev,
          { role: "bot", createdAt: new Date().toISOString(), ...base, text: shown, streaming: true },
        ]);
        return;
      }
      setMessages((prev) => [...prev.slice(0, -1), { ...prev[prev.length - 1], text: shown }]);
    });
  }, []);

  /** Replaces the message being typed with its final version (or appends it if none started). */
  const finishBotMessage = useCallback((final: Message) => {
    setMessages((prev) =>
      prev.at(-1)?.streaming ? [...prev.slice(0, -1), final] : [...prev, final],
    );
  }, []);

  // Load the conversation once, then let RoboFriend check in if it has something for today.
  useEffect(() => {
    let cancelled = false;
    getChatHistory(userId)
      .then(async (data) => {
        if (cancelled) return;
        const history = (data ?? []).map(fromServer);
        setMessages(history.length > 0 ? history : [welcomeMessage(username)]);
        if (history.length === 0) return;

        const checkIn = await dailyCheckIn();
        if (!checkIn || cancelled || sendingRef.current) return;
        setBusy(true);
        await new Promise((resolve) => setTimeout(resolve, 1200)); // a moment of "typing…"
        const message = fromServer(checkIn);
        const typer = typeNewBotMessage(message);
        typer.push(message.text);
        await typer.finish();
        finishBotMessage(message);
        notifyIfHidden(message.text);
        setBusy(false);
      })
      .catch(() => {
        if (!cancelled) setMessages([welcomeMessage(username)]);
      });
    return () => {
      cancelled = true;
    };
  }, [userId, username, typeNewBotMessage, finishBotMessage]);

  // Pick up messages RoboFriend sent on its own, e.g. a reminder that just came due.
  useEffect(() => {
    const poll = () => {
      if (sendingRef.current) return;
      const lastId = Math.max(0, ...messagesRef.current.map((m) => m.id ?? 0));
      getChatHistory(userId, lastId)
        .then((data) => {
          const fresh = (data ?? []).map(fromServer);
          if (sendingRef.current || fresh.length === 0) return;
          setMessages((prev) => [...prev, ...fresh]);
          const lastBot = fresh.findLast((m) => m.role === "bot");
          if (lastBot) {
            notify(lastBot.text);
            notifyIfHidden(lastBot.text);
          }
          onActivity();
        })
        .catch(() => undefined);
    };
    const interval = setInterval(poll, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [userId, notify, onActivity]);

  /**
   * Streams a reply into the conversation. `prepare` shapes the list first (append the new
   * message, drop the reply being regenerated, cut at an edited message).
   */
  const runStream = async (
    request: StreamRequest,
    prepare: (prev: Message[]) => Message[],
  ): Promise<string> => {
    setBusy(true);
    setMessages(prepare);
    const typer = typeNewBotMessage();
    try {
      const result = await streamChatMessage(request, (text) => typer.push(text));
      await typer.finish();
      finishBotMessage({
        role: "bot",
        text: result.reply,
        id: result.botMessageId,
        createdAt: new Date().toISOString(),
      });
      // The user message gets its server id, so it can be edited or deleted later.
      setMessages((prev) => {
        const i = prev.findLastIndex((m) => m.role === "user");
        if (i < 0 || prev[i].id !== undefined) return prev;
        return prev.map((m, j) => (j === i ? { ...m, id: result.userMessageId } : m));
      });
      onActivity();
      // Reminders only help if they can reach you; ask once, after the first reply.
      if ("Notification" in window && Notification.permission === "default") {
        Notification.requestPermission().catch(() => undefined);
      }
      return result.reply;
    } catch {
      typer.cancel();
      const last = messagesRef.current.at(-1);
      if (last?.streaming) {
        finishBotMessage({ ...last, streaming: false });
      } else {
        finishBotMessage({ role: "bot", text: FALLBACK_REPLY, createdAt: new Date().toISOString() });
      }
      return FALLBACK_REPLY;
    } finally {
      setBusy(false);
    }
  };

  const send = (text: string) =>
    runStream({ message: text }, (prev) => [
      ...prev,
      { role: "user", text, createdAt: new Date().toISOString() },
    ]);

  const regenerate = () =>
    runStream({ regenerate: true }, (prev) => {
      let end = prev.length;
      while (end > 0 && prev[end - 1].role === "bot") end--;
      return prev.slice(0, end);
    });

  const edit = (index: number, text: string) => {
    const original = messages[index];
    if (!original?.id) return;
    return runStream({ message: text, replaceFromId: original.id }, (prev) => [
      ...prev.slice(0, index),
      { role: "user", text, createdAt: new Date().toISOString() },
    ]);
  };

  const remove = (index: number) => {
    const target = messages[index];
    if (!target) return;
    setMessages((prev) => prev.filter((_, i) => i !== index));
    if (target.id === undefined) return;
    deleteChatMessage(target.id).catch(() => {
      notify("Couldn't delete that message. Try again?");
      setMessages((prev) => [...prev.slice(0, index), target, ...prev.slice(index)]);
    });
  };

  /** Deletes the whole conversation (and optionally everything RoboFriend learned). */
  const clear = async (forgetMemories: boolean) => {
    await deleteConversation(forgetMemories);
    setMessages([welcomeMessage(username)]);
  };

  return {
    messages,
    isSending,
    /** Waiting for the first word of a reply. */
    isTyping: isSending && !replyStarted,
    send,
    regenerate,
    edit,
    remove,
    clear,
  };
}
