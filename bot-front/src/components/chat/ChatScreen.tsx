import { useCallback, useState } from "react";
import {
  Alert,
  Badge,
  Box,
  Drawer,
  IconButton,
  Paper,
  Snackbar,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { DeleteSweepOutlined, EventNote } from "@mui/icons-material";
import VoiceOverlay from "../VoiceOverlay";
import FriendAvatar from "../common/FriendAvatar";
import ChatWindow from "./ChatWindow";
import ClearConversationDialog from "./ClearConversationDialog";
import MessageInput from "./MessageInput";
import QuickReplies from "./QuickReplies";
import SidePanel from "./SidePanel";
import { useChat } from "../../hooks/useChat";
import { useOrganizer } from "../../hooks/useOrganizer";
import { useStickToBottom } from "../../hooks/useStickToBottom";

type ChatScreenProps = {
  currentUser: { userId: number; username: string };
};

/** The main screen: the conversation on the left, reminders and lists on the right. */
export default function ChatScreen({ currentUser }: ChatScreenProps) {
  const [notice, setNotice] = useState<string | null>(null);
  const notify = useCallback((text: string) => setNotice(text), []);

  const organizer = useOrganizer(currentUser.userId);
  const chat = useChat({
    userId: currentUser.userId,
    username: currentUser.username,
    onActivity: organizer.refresh,
    notify,
  });
  const { scrollRef, contentRef } = useStickToBottom<HTMLDivElement, HTMLDivElement>();

  const [input, setInput] = useState("");
  const [voiceOpen, setVoiceOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [clearDialogOpen, setClearDialogOpen] = useState(false);

  const handleSend = (value: string) => {
    const text = value.trim();
    if (!text || chat.isSending) return;
    setInput("");
    chat.send(text);
  };

  const handleClear = async (forgetMemories: boolean) => {
    try {
      await chat.clear(forgetMemories);
      notify(forgetMemories ? "Conversation deleted, and I've forgotten what I knew. Fresh start! 🌱" : "Conversation deleted.");
      setClearDialogOpen(false);
    } catch {
      notify("Couldn't delete the conversation. Try again?");
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    notify("Copied to clipboard");
  };

  const onlyBotMessages = chat.messages.every((m) => m.role === "bot");

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
              sx={{ color: chat.isSending ? "primary.main" : "success.main", fontWeight: 600 }}
            >
              {chat.isSending ? "typing…" : "Online · here for you"}
            </Typography>
          </Box>
          <Tooltip title="Delete conversation">
            <span>
              <IconButton
                onClick={() => setClearDialogOpen(true)}
                disabled={chat.isSending}
                aria-label="Delete conversation"
              >
                <DeleteSweepOutlined />
              </IconButton>
            </span>
          </Tooltip>
          <Tooltip title="Reminders & lists">
            <IconButton
              onClick={() => setDrawerOpen(true)}
              sx={{ display: { md: "none" } }}
              aria-label="Open reminders and lists"
            >
              <Badge badgeContent={organizer.upcomingCount + organizer.openListCount} color="secondary">
                <EventNote />
              </Badge>
            </IconButton>
          </Tooltip>
        </Stack>

        {/* Conversation */}
        <Box
          ref={scrollRef}
          sx={{ flex: 1, minHeight: 0, overflowY: "auto", px: { xs: 1.25, md: 3 }, py: 2, overscrollBehavior: "contain" }}
        >
          <Box ref={contentRef}>
            <ChatWindow
              messages={chat.messages}
              isTyping={chat.isTyping}
              busy={chat.isSending}
              onCopy={handleCopy}
              onDelete={chat.remove}
              onEdit={(index, text) => chat.edit(index, text.trim())}
              onRegenerate={chat.regenerate}
            />
          </Box>
        </Box>

        {onlyBotMessages && !chat.isSending && <QuickReplies onPick={handleSend} />}

        <MessageInput
          value={input}
          onChange={setInput}
          onSend={handleSend}
          onVoice={() => setVoiceOpen(true)}
          disabled={chat.isSending}
        />
      </Paper>

      {/* Reminders & lists: a column on desktop, a drawer on phones */}
      <Paper sx={{ display: { xs: "none", md: "flex" }, flexDirection: "column", minHeight: 0, overflow: "hidden", borderRadius: 6 }}>
        <SidePanel organizer={organizer} />
      </Paper>
      <Drawer
        anchor="right"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        slotProps={{ paper: { sx: { width: "min(88vw, 340px)", borderRadius: "24px 0 0 24px" } } }}
      >
        <SidePanel organizer={organizer} />
      </Drawer>

      <ClearConversationDialog
        open={clearDialogOpen}
        onClose={() => setClearDialogOpen(false)}
        onConfirm={handleClear}
      />

      {voiceOpen && <VoiceOverlay onClose={() => setVoiceOpen(false)} onSendTranscript={chat.send} />}

      <Snackbar
        open={notice !== null}
        autoHideDuration={4000}
        onClose={() => setNotice(null)}
        anchorOrigin={{ vertical: "top", horizontal: "center" }}
      >
        <Alert
          severity="info"
          icon={false}
          onClose={() => setNotice(null)}
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
        >
          {notice}
        </Alert>
      </Snackbar>
    </Box>
  );
}
