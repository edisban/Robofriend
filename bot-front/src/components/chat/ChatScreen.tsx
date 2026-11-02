import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Drawer,
  FormControlLabel,
  Tab,
  Tabs,
  IconButton,
  Paper,
  Snackbar,
  Stack,
  Tooltip,
  Typography,
  Alert,
} from "@mui/material";
import { DeleteSweepOutlined, EventNote } from "@mui/icons-material";
import VoiceOverlay from "../VoiceOverlay";
import ChatWindow from "./ChatWindow";
import MessageInput from "./MessageInput";
import QuickReplies from "./QuickReplies";
import ReminderVault from "../reminders/ReminderVault";
import ListsPanel from "../lists/ListsPanel";
import {
  clearDoneItems,
  deleteListItem,
  getLists,
  setListItemDone,
  type ListItem,
} from "../../api/listApi";
import FriendAvatar from "../common/FriendAvatar";
import {
  dailyCheckIn,
  deleteChatMessage,
  deleteConversation,
  getChatHistory,
  streamChatMessage,
  type ChatMessage,
  type StreamRequest,
} from "../../api/chatApi";
import {
  deleteReminder,
  getReminders,
  updateReminderStatus,
  type ReminderItem,
} from "../../api/reminderApi";
import { timeOfDayGreeting } from "../../utils/greeting";
import { createTypewriter } from "../../utils/typewriter";
import type { Message as ScreenMessage } from "./ChatWindow";

interface SessionUser {
  userId: number;
  username: string;
}

type ChatScreenProps = {
  currentUser: SessionUser;
};

const POLL_INTERVAL_MS = 20000;
const FALLBACK_REPLY = "Hmm, I couldn't reach the server just now. Mind trying that again?";

function welcomeMessage(username: string): ScreenMessage {
  return {
    role: "bot",
    text: `${timeOfDayGreeting()}, ${username}! 👋 I'm RoboFriend. How are you doing today?`,
    createdAt: new Date().toISOString(),
  };
}

function fromServer(item: ChatMessage): ScreenMessage {
  return {
    id: item.id,
    role: item.sender === "USER" ? "user" : "bot",
    text: item.content,
    createdAt: item.createdAt,
  };
}

function notifyIfHidden(body: string) {
  if (!document.hidden || !("Notification" in window)) return;
  if (Notification.permission !== "granted") return;
  try {
    new Notification("RoboFriend", { body, icon: "/favicon.svg" });
  } catch {
    void 0;
  }
}

export default function ChatScreen({ currentUser }: ChatScreenProps) {
  const [messages, setMessages] = useState<ScreenMessage[]>(() => [
    welcomeMessage(currentUser.username),
  ]);
  const [reminders, setReminders] = useState<ReminderItem[]>([]);
  const [listItems, setListItems] = useState<ListItem[]>([]);
  const [sideTab, setSideTab] = useState<"reminders" | "lists">("reminders");

  const [input, setInput] = useState("");
  const [voiceOverlayOpen, setVoiceOverlayOpen] = useState(false);
  const [remindersOpen, setRemindersOpen] = useState(false);
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [replyStarted, setReplyStarted] = useState(false);
  const [clearDialogOpen, setClearDialogOpen] = useState(false);
  const [forgetMemories, setForgetMemories] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const contentRef = useRef<HTMLDivElement | null>(null);
  const messagesRef = useRef(messages);
  const sendingRef = useRef(false);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const showSnackbar = (message: string) => {
    setSnackbarMessage(message);
    setSnackbarOpen(true);
  };

  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, []);

  useEffect(() => {
    const el = scrollRef.current;
    const content = contentRef.current;
    if (!el || !content || typeof ResizeObserver === "undefined") return;

    const observer = new ResizeObserver(() => {
      scrollToBottom();
    });
    observer.observe(content);

    scrollToBottom();

    return () => observer.disconnect();
  }, [scrollToBottom]);

  useEffect(() => {
    scrollToBottom();
    const timer = setTimeout(() => scrollToBottom(), 120);
    return () => clearTimeout(timer);
  }, [messages, isSending, scrollToBottom]);

  const userId = currentUser?.userId;

  const refreshReminders = useCallback(() => {
    if (!userId) return;
    getReminders(userId)
      .then((data) => setReminders(Array.isArray(data) ? data : []))
      .catch(() => setReminders([]));
    getLists()
      .then((data) => setListItems(Array.isArray(data) ? data : []))
      .catch(() => undefined);
  }, [userId]);

  // Picks up messages RoboFriend sent on its own, e.g. a reminder that just came due.
  const pollNewMessages = useCallback(() => {
    if (!userId || sendingRef.current) return;
    const lastId = messagesRef.current.reduce(
      (max, m) => (m.id !== undefined && m.id > max ? m.id : max),
      0,
    );
    getChatHistory(userId, lastId)
      .then((data) => {
        if (sendingRef.current || !Array.isArray(data) || data.length === 0) return;
        const known = new Set(messagesRef.current.map((m) => m.id));
        const fresh = data
          .filter((m) => m.id !== undefined && !known.has(m.id))
          .map(fromServer);
        if (fresh.length === 0) return;
        setMessages((prev) => [...prev, ...fresh]);
        const lastBot = [...fresh].reverse().find((m) => m.role === "bot");
        if (lastBot) {
          showSnackbar(lastBot.text);
          notifyIfHidden(lastBot.text);
        }
        refreshReminders();
      })
      .catch(() => undefined);
  }, [userId, refreshReminders]);

  /**
   * Once a day RoboFriend may text first (a birthday, an exam, a follow-up). Show it typing and
   * write the message out like any other reply.
   */
  const checkIn = (isCancelled: () => boolean) => {
    dailyCheckIn()
      .then(async (message) => {
        if (!message || isCancelled() || sendingRef.current) return;
        setReplyStarted(false);
        setIsSending(true);
        await new Promise((resolve) => setTimeout(resolve, 900 + Math.random() * 600));
        if (isCancelled()) return;
        const base = fromServer(message);
        let started = false;
        const typer = createTypewriter((shown) => {
          if (!started) {
            started = true;
            setReplyStarted(true);
            setMessages((prev) => [...prev, { ...base, text: shown, streaming: true }]);
            return;
          }
          setMessages((prev) => {
            const next = prev.slice();
            next[next.length - 1] = { ...next[next.length - 1], text: shown };
            return next;
          });
        });
        typer.push(base.text);
        await typer.finish();
        setMessages((prev) => {
          const next = prev.slice();
          next[next.length - 1] = { ...base, streaming: false };
          return next;
        });
        notifyIfHidden(base.text);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!sendingRef.current) {
          setIsSending(false);
          setReplyStarted(false);
        }
      });
  };

  useEffect(() => {
    if (!userId) return;

    let cancelled = false;

    getChatHistory(userId)
      .then((data) => {
        if (cancelled) return;
        const mapped = (Array.isArray(data) ? data : []).map(fromServer);
        setMessages(
          mapped.length > 0 ? mapped : [welcomeMessage(currentUser.username)],
        );
        if (mapped.length > 0) checkIn(() => cancelled);
      })
      .catch(() => {
        if (cancelled) return;
        setMessages([welcomeMessage(currentUser.username)]);
      });

    refreshReminders();

    return () => {
      cancelled = true;
    };
  }, [userId, currentUser.username, refreshReminders]);

  useEffect(() => {
    if (!userId) return;
    const interval = setInterval(() => {
      refreshReminders();
      pollNewMessages();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [userId, refreshReminders, pollNewMessages]);

  /**
   * Streams a reply into the conversation. `prepare` shapes the message list before the reply
   * starts (append the new message, drop the reply being regenerated, cut at an edit...).
   */
  const runStream = async (
    request: StreamRequest,
    prepare: (prev: ScreenMessage[]) => ScreenMessage[],
  ): Promise<string> => {
    sendingRef.current = true;
    setIsSending(true);
    setReplyStarted(false);
    setMessages(prepare);

    let started = false;
    const typer = createTypewriter((shown) => {
      if (!started) {
        started = true;
        setReplyStarted(true);
        setMessages((prev) => [
          ...prev,
          { role: "bot", text: shown, createdAt: new Date().toISOString(), streaming: true },
        ]);
        return;
      }
      setMessages((prev) => {
        const next = prev.slice();
        next[next.length - 1] = { ...next[next.length - 1], text: shown };
        return next;
      });
    });
    try {
      const result = await streamChatMessage(request, (text) => typer.push(text));
      await typer.finish();

      setMessages((prev) => {
        const next = prev.slice();
        const lastIdx = next.length - 1;
        if (started && next[lastIdx]?.streaming) {
          next[lastIdx] = {
            ...next[lastIdx],
            text: result.reply,
            id: result.botMessageId,
            streaming: false,
          };
        } else {
          next.push({
            role: "bot",
            text: result.reply,
            id: result.botMessageId,
            createdAt: new Date().toISOString(),
          });
        }
        for (let i = next.length - 1; i >= 0; i--) {
          if (next[i].role === "user") {
            if (next[i].id === undefined) next[i] = { ...next[i], id: result.userMessageId };
            break;
          }
        }
        return next;
      });

      refreshReminders();
      // Reminders only help if they can reach you; ask once, after the first reply.
      if ("Notification" in window && Notification.permission === "default") {
        Notification.requestPermission().catch(() => undefined);
      }
      return result.reply;
    } catch {
      typer.cancel();
      setMessages((prev) => {
        const next = prev.slice();
        const lastIdx = next.length - 1;
        if (started && next[lastIdx]?.streaming) {
          next[lastIdx] = { ...next[lastIdx], streaming: false };
          return next;
        }
        return [
          ...next,
          { role: "bot", text: FALLBACK_REPLY, createdAt: new Date().toISOString() },
        ];
      });
      return FALLBACK_REPLY;
    } finally {
      sendingRef.current = false;
      setIsSending(false);
      setReplyStarted(false);
    }
  };

  const sendMessage = (message: string) =>
    runStream({ message }, (prev) => [
      ...prev,
      { role: "user", text: message, createdAt: new Date().toISOString() },
    ]);

  const handleSend = async (value: string) => {
    const trimmed = value.trim();
    if (!trimmed || isSending) return;
    setInput("");
    await sendMessage(trimmed);
  };

  const handleRegenerate = () => {
    if (isSending) return;
    runStream({ regenerate: true }, (prev) => {
      let end = prev.length;
      while (end > 0 && prev[end - 1].role === "bot") end--;
      return prev.slice(0, end);
    });
  };

  const handleEdit = (index: number, text: string) => {
    const original = messages[index];
    const trimmed = text.trim();
    if (isSending || !original?.id || !trimmed) return;
    runStream({ message: trimmed, replaceFromId: original.id }, (prev) => [
      ...prev.slice(0, index),
      { role: "user", text: trimmed, createdAt: new Date().toISOString() },
    ]);
  };

  const handleDeleteMessage = (index: number) => {
    const target = messages[index];
    if (!target) return;
    setMessages((prev) => prev.filter((_, i) => i !== index));
    if (target.id === undefined) return;
    deleteChatMessage(target.id).catch(() => {
      showSnackbar("Couldn't delete that message. Try again?");
      setMessages((prev) => {
        const next = prev.slice();
        next.splice(Math.min(index, next.length), 0, target);
        return next;
      });
    });
  };

  const handleVoiceTranscript = (transcript: string) => sendMessage(transcript);

  const handleClearConversation = async () => {
    setIsClearing(true);
    try {
      await deleteConversation(forgetMemories);
      setMessages([welcomeMessage(currentUser.username)]);
      showSnackbar(
        forgetMemories
          ? "Conversation deleted, and I've forgotten what I knew. Fresh start! 🌱"
          : "Conversation deleted.",
      );
      setClearDialogOpen(false);
      setForgetMemories(false);
    } catch {
      showSnackbar("Couldn't delete the conversation. Try again?");
    } finally {
      setIsClearing(false);
    }
  };

  const handleReminderToggle = (id: number) => {
    const current = reminders.find((item) => item.id === id);
    const nextStatus = current?.status === "DONE" ? "PENDING" : "DONE";
    setReminders((prev) =>
      prev.map((item) =>
        item.id === id ? { ...item, status: nextStatus } : item,
      ),
    );
    updateReminderStatus(id, nextStatus).catch((error) => {
      console.warn("Failed to persist reminder status", error);
      refreshReminders();
    });
  };

  const handleReminderDelete = (id: number) => {
    setReminders((prev) => prev.filter((item) => item.id !== id));
    deleteReminder(id).catch((error) => {
      console.warn("Failed to delete reminder", error);
      refreshReminders();
    });
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    showSnackbar("Copied to clipboard");
  };

  const handleListToggle = (item: ListItem) => {
    setListItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, done: !i.done } : i)));
    setListItemDone(item.id, !item.done).catch(() => refreshReminders());
  };

  const handleListDelete = (item: ListItem) => {
    setListItems((prev) => prev.filter((i) => i.id !== item.id));
    deleteListItem(item.id).catch(() => refreshReminders());
  };

  const handleListClearDone = (listName: string) => {
    setListItems((prev) => prev.filter((i) => !(i.listName === listName && i.done)));
    clearDoneItems(listName).catch(() => refreshReminders());
  };

  const upcomingCount = reminders.filter((r) => r.status !== "DONE").length;
  const openListCount = listItems.filter((i) => !i.done).length;
  const showQuickReplies =
    !isSending && messages.every((message) => message.role === "bot");

  const reminderPanel = (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      <Tabs
        value={sideTab}
        onChange={(_, value) => setSideTab(value)}
        variant="fullWidth"
        sx={{
          px: 1.5,
          pt: 1,
          minHeight: 44,
          "& .MuiTab-root": { minHeight: 44, textTransform: "none", fontWeight: 600 },
        }}
      >
        <Tab
          value="reminders"
          label={upcomingCount ? `Reminders · ${upcomingCount}` : "Reminders"}
        />
        <Tab value="lists" label={openListCount ? `Lists · ${openListCount}` : "Lists"} />
      </Tabs>
      <Box sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", pt: 1 }}>
        {sideTab === "reminders" ? (
          <ReminderVault
            reminders={reminders}
            onDoneToggle={handleReminderToggle}
            onDelete={handleReminderDelete}
            showHeader={false}
          />
        ) : (
          <ListsPanel
            items={listItems}
            onToggle={handleListToggle}
            onDelete={handleListDelete}
            onClearDone={handleListClearDone}
          />
        )}
      </Box>
    </Box>
  );

  return (
    <Box
      sx={{
        height: "100%",
        display: "grid",
        gridTemplateColumns: { xs: "1fr", md: "minmax(0, 1fr) 320px" },
        gap: 2,
        p: { xs: 0, sm: 1.5, md: 2.5 },
        pt: { xs: 0, sm: 1.5, md: 2 },
        maxWidth: 1360,
        mx: "auto",
        color: "text.primary",
      }}
    >
      <Paper
        sx={{
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
          minHeight: 0,
          borderRadius: { xs: 0, sm: 6 },
          borderWidth: { xs: 0, sm: 1 },
        }}
      >
        {/* Header: who you're talking to */}
        <Stack
          direction="row"
          spacing={1.5}
          sx={{
            alignItems: "center",
            px: { xs: 1.75, md: 2.5 },
            py: 1.5,
            borderBottom: (t) => `1px solid ${t.glass.border}`,
            flexShrink: 0,
          }}
        >
          <FriendAvatar size={42} online />
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="h6" sx={{ fontSize: 17, lineHeight: 1.2 }}>
              RoboFriend
            </Typography>
            <Typography
              variant="caption"
              sx={{
                color: isSending ? "primary.main" : "success.main",
                fontWeight: 600,
              }}
            >
              {isSending ? "typing…" : "Online · here for you"}
            </Typography>
          </Box>

          <Tooltip title="Delete conversation">
            <span>
              <IconButton
                onClick={() => setClearDialogOpen(true)}
                disabled={isSending}
                aria-label="Delete conversation"
              >
                <DeleteSweepOutlined />
              </IconButton>
            </span>
          </Tooltip>

          <Tooltip title="Reminders & lists">
            <IconButton
              onClick={() => setRemindersOpen(true)}
              sx={{ display: { md: "none" } }}
              aria-label="Open reminders and lists"
            >
              <Badge badgeContent={upcomingCount + openListCount} color="secondary">
                <EventNote />
              </Badge>
            </IconButton>
          </Tooltip>
        </Stack>

        {/* Conversation */}
        <Box
          ref={scrollRef}
          sx={{
            flex: 1,
            minHeight: 0,
            overflowY: "auto",
            px: { xs: 1.25, md: 3 },
            py: 2,
            overscrollBehavior: "contain",
          }}
        >
          <Box ref={contentRef}>
            <ChatWindow
              messages={messages}
              isTyping={isSending && !replyStarted}
              busy={isSending}
              onCopy={handleCopy}
              onDelete={handleDeleteMessage}
              onEdit={handleEdit}
              onRegenerate={handleRegenerate}
            />
          </Box>
        </Box>

        {showQuickReplies && <QuickReplies onPick={handleSend} />}

        <MessageInput
          value={input}
          onChange={setInput}
          onSend={handleSend}
          onVoice={() => setVoiceOverlayOpen(true)}
          disabled={isSending}
        />
      </Paper>

      <Paper
        sx={{
          display: { xs: "none", md: "flex" },
          flexDirection: "column",
          minHeight: 0,
          overflow: "hidden",
          borderRadius: 6,
        }}
      >
        {reminderPanel}
      </Paper>

      <Drawer
        anchor="right"
        open={remindersOpen}
        onClose={() => setRemindersOpen(false)}
        slotProps={{
          paper: {
            sx: {
              width: "min(88vw, 340px)",
              borderRadius: "24px 0 0 24px",
            },
          },
        }}
      >
        {reminderPanel}
      </Drawer>

      <Dialog
        open={clearDialogOpen}
        onClose={() => !isClearing && setClearDialogOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle sx={{ fontWeight: 700 }}>Delete the whole conversation?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            All your messages with RoboFriend will be permanently deleted. This can't be undone.
          </DialogContentText>
          <FormControlLabel
            sx={{ mt: 1.5, alignItems: "flex-start" }}
            control={
              <Checkbox
                checked={forgetMemories}
                onChange={(e) => setForgetMemories(e.target.checked)}
                sx={{ mt: -0.75 }}
              />
            }
            label={
              <Box>
                <Box component="span" sx={{ fontWeight: 600 }}>
                  Also forget everything RoboFriend learned about me
                </Box>
                <Box component="span" sx={{ display: "block", color: "text.secondary", fontSize: 13 }}>
                  Facts, plans and stats like your personal records. Reminders are kept.
                </Box>
              </Box>
            }
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5, gap: 1 }}>
          <Button onClick={() => setClearDialogOpen(false)} disabled={isClearing}>
            Cancel
          </Button>
          <Button
            onClick={handleClearConversation}
            color="error"
            variant="contained"
            disabled={isClearing}
          >
            {isClearing ? "Deleting…" : "Delete"}
          </Button>
        </DialogActions>
      </Dialog>

      {voiceOverlayOpen && (
        <VoiceOverlay
          onClose={() => setVoiceOverlayOpen(false)}
          onSendTranscript={handleVoiceTranscript}
        />
      )}

      <Snackbar
        open={snackbarOpen}
        autoHideDuration={4000}
        onClose={() => setSnackbarOpen(false)}
        anchorOrigin={{ vertical: "top", horizontal: "center" }}
      >
        <Alert
          severity="info"
          icon={false}
          sx={(t) => ({
            width: "100%",
            borderRadius: 999,
            px: 2.5,
            background: t.glass.surfaceStrong,
            backdropFilter: "blur(20px)",
            border: `1px solid ${t.glass.border}`,
            color: "text.primary",
            fontWeight: 600,
          })}
          onClose={() => setSnackbarOpen(false)}
        >
          {snackbarMessage}
        </Alert>
      </Snackbar>

    </Box>
  );
}
