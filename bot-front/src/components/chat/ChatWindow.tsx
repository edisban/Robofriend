import { Fragment, useState } from "react";
import {
  Box,
  Button,
  IconButton,
  ListItemIcon,
  Menu,
  MenuItem,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import {
  ContentCopy,
  DeleteOutlined,
  EditOutlined,
  Refresh,
} from "@mui/icons-material";
import { keyframes } from "@emotion/react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import FriendAvatar from "../common/FriendAvatar";
import { ACCENT_GRADIENT } from "../../theme/theme";

export type Message = {
  id?: number;
  role: "bot" | "user";
  text: string;
  createdAt?: string;
  /** True while the reply is still being written. */
  streaming?: boolean;
};

type ChatWindowProps = {
  messages: Message[];
  isTyping?: boolean;
  /** A reply is in progress; editing and regenerating wait until it's done. */
  busy?: boolean;
  onCopy: (text: string) => void;
  onDelete: (index: number) => void;
  onEdit: (index: number, text: string) => void;
  onRegenerate: () => void;
};

const popIn = keyframes`
  from { opacity: 0; transform: translateY(8px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
`;

const bounce = keyframes`
  0%, 60%, 100% { transform: translateY(0); opacity: 0.45; }
  30%           { transform: translateY(-5px); opacity: 1; }
`;

const blink = keyframes`
  0%, 50%  { opacity: 1; }
  51%, 100% { opacity: 0; }
`;

const AVATAR_SIZE = 30;

function formatTime(iso?: string) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function dayKey(iso?: string) {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toDateString();
}

function dayLabel(iso: string) {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return "Today";
  if (date.toDateString() === yesterday.toDateString()) return "Yesterday";
  return date.toLocaleDateString([], {
    weekday: "long",
    month: "short",
    day: "numeric",
  });
}

function DaySeparator({ label }: { label: string }) {
  return (
    <Box sx={{ display: "flex", justifyContent: "center", my: 1.5 }}>
      <Typography
        variant="caption"
        sx={{
          px: 1.5,
          py: 0.4,
          borderRadius: 999,
          color: "text.secondary",
          bgcolor: (t) => t.glass.surfaceSubtle,
          border: (t) => `1px solid ${t.glass.border}`,
          backdropFilter: "blur(12px)",
          fontWeight: 600,
        }}
      >
        {label}
      </Typography>
    </Box>
  );
}

export function TypingBubble() {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: "flex-end", animation: `${popIn} 0.25s ease-out` }}
      aria-label="RoboFriend is typing"
    >
      <FriendAvatar size={AVATAR_SIZE} />
      <Box
        sx={{
          px: 2,
          py: 1.5,
          borderRadius: "20px 20px 20px 6px",
          bgcolor: (t) => t.glass.surfaceStrong,
          border: (t) => `1px solid ${t.glass.border}`,
          backdropFilter: "blur(18px)",
          display: "flex",
          gap: 0.6,
        }}
      >
        {[0, 1, 2].map((i) => (
          <Box
            key={i}
            sx={{
              width: 7,
              height: 7,
              borderRadius: "50%",
              bgcolor: "text.secondary",
              animation: `${bounce} 1.2s ${i * 0.15}s infinite ease-in-out`,
            }}
          />
        ))}
      </Box>
    </Stack>
  );
}

function ActionButton({
  title,
  onClick,
  children,
}: {
  title: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Tooltip title={title}>
      <IconButton
        size="small"
        aria-label={title}
        onClick={onClick}
        sx={{ p: 0.5, color: "text.secondary", "&:hover": { color: "text.primary" } }}
      >
        {children}
      </IconButton>
    </Tooltip>
  );
}

function EditBox({
  initial,
  onCancel,
  onSave,
}: {
  initial: string;
  onCancel: () => void;
  onSave: (text: string) => void;
}) {
  const [draft, setDraft] = useState(initial);
  const canSave = draft.trim().length > 0 && draft.trim() !== initial.trim();

  return (
    <Box
      sx={(t) => ({
        width: { xs: "82vw", sm: 420 },
        maxWidth: "100%",
        p: 1.25,
        borderRadius: "20px",
        bgcolor: t.glass.surfaceStrong,
        border: `1px solid ${t.palette.primary.main}`,
        backdropFilter: "blur(18px)",
      })}
    >
      <Box
        component="textarea"
        autoFocus
        value={draft}
        rows={Math.min(6, Math.max(2, draft.split("\n").length))}
        onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setDraft(e.target.value)}
        onKeyDown={(e: React.KeyboardEvent<HTMLTextAreaElement>) => {
          if (e.key === "Escape") onCancel();
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            if (canSave) onSave(draft);
          }
        }}
        aria-label="Edit message"
        sx={{
          width: "100%",
          px: 0.75,
          font: "inherit",
          fontSize: 14.5,
          lineHeight: 1.5,
          color: "text.primary",
          background: "transparent",
          border: "none",
          outline: "none",
          resize: "none",
        }}
      />
      <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end", mt: 0.5 }}>
        <Button size="small" onClick={onCancel}>
          Cancel
        </Button>
        <Button size="small" variant="contained" disabled={!canSave} onClick={() => onSave(draft)}>
          Send
        </Button>
      </Stack>
    </Box>
  );
}

export default function ChatWindow({
  messages,
  isTyping = false,
  busy = false,
  onCopy,
  onDelete,
  onEdit,
  onRegenerate,
}: ChatWindowProps) {
  const [menu, setMenu] = useState<{ x: number; y: number; index: number } | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  let lastUserIndex = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "user") {
      lastUserIndex = i;
      break;
    }
  }
  const lastIndex = messages.length - 1;
  const canRegenerate =
    !busy && lastUserIndex >= 0 && messages[lastIndex]?.role === "bot" && lastIndex > lastUserIndex;

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
      {messages.map((msg, index) => {
        const key = msg.id !== undefined ? `m-${msg.id}` : `${msg.role}-${index}`;
        const isUser = msg.role === "user";
        const prev = messages[index - 1];
        const next = messages[index + 1];
        const currentDay = dayKey(msg.createdAt);
        const showDay =
          currentDay !== null && currentDay !== dayKey(prev?.createdAt);
        const startsGroup = !prev || prev.role !== msg.role || showDay;
        const endsGroup =
          !next ||
          next.role !== msg.role ||
          (dayKey(next.createdAt) !== null &&
            dayKey(next.createdAt) !== currentDay);
        const isEditing = editingIndex === index;
        const canEdit = isUser && index === lastUserIndex && !busy && msg.id !== undefined;
        const showRegenerate = !isUser && index === lastIndex && canRegenerate;

        // Corners facing the sender's side tighten inside a group, like a messenger thread.
        const big = "20px";
        const small = "6px";
        const radius = isUser
          ? `${big} ${startsGroup ? big : small} ${small} ${big}`
          : `${startsGroup ? big : small} ${big} ${big} ${small}`;

        return (
          <Fragment key={key}>
            {showDay && msg.createdAt && <DaySeparator label={dayLabel(msg.createdAt)} />}
            <Stack
              direction="row"
              spacing={1}
              sx={{
                justifyContent: isUser ? "flex-end" : "flex-start",
                alignItems: "flex-end",
                mt: startsGroup && index > 0 ? 1.25 : 0,
                animation: `${popIn} 0.28s ease-out`,
                "&:hover .msg-actions": { opacity: 1 },
              }}
            >
              {!isUser &&
                (endsGroup ? (
                  <Box sx={{ mb: 3.25 }}>
                    <FriendAvatar size={AVATAR_SIZE} />
                  </Box>
                ) : (
                  <Box sx={{ width: AVATAR_SIZE, flexShrink: 0 }} />
                ))}

              <Stack
                spacing={0.25}
                sx={{
                  maxWidth: { xs: "82%", sm: "75%", md: "68%" },
                  alignItems: isUser ? "flex-end" : "flex-start",
                }}
              >
                {isEditing ? (
                  <EditBox
                    initial={msg.text}
                    onCancel={() => setEditingIndex(null)}
                    onSave={(text) => {
                      setEditingIndex(null);
                      onEdit(index, text);
                    }}
                  />
                ) : (
                  <Box
                    onContextMenu={(event) => {
                      if (msg.streaming) return;
                      event.preventDefault();
                      setMenu({ x: event.clientX, y: event.clientY, index });
                    }}
                    sx={{
                      px: 1.75,
                      py: 1.1,
                      borderRadius: radius,
                      minWidth: 0,
                      maxWidth: "100%",
                      overflowWrap: "anywhere",
                      ...(isUser
                        ? {
                            background: ACCENT_GRADIENT,
                            color: "#fff",
                            boxShadow: "0 6px 20px rgba(168, 85, 247, 0.30)",
                          }
                        : {
                            bgcolor: (t) => t.glass.surfaceStrong,
                            color: "text.primary",
                            border: (t) => `1px solid ${t.glass.border}`,
                            backdropFilter: "blur(18px) saturate(160%)",
                            boxShadow: (t) => `inset 0 1px 0 ${t.glass.highlight}`,
                          }),
                    }}
                  >
                    {isUser ? (
                      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
                        {msg.text}
                      </Typography>
                    ) : (
                      <Box
                        sx={{
                          typography: "body2",
                          "& > :first-of-type": { mt: 0 },
                          "& > :last-child": { mb: 0 },
                          "& p": { my: 0.75 },
                          "& ul, & ol": { my: 0.75, pl: 2.5 },
                          "& li": { my: 0.25 },
                          "& a": { color: "primary.main" },
                          "& code": {
                            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                            fontSize: 13,
                            px: 0.6,
                            py: 0.1,
                            borderRadius: 1,
                            bgcolor: (t) => t.glass.surfaceSubtle,
                          },
                          "& pre": {
                            p: 1.25,
                            borderRadius: 2,
                            overflowX: "auto",
                            bgcolor: (t) => t.glass.surfaceSubtle,
                          },
                          "& pre code": { p: 0, bgcolor: "transparent" },
                          // Blinking cursor at the end of a reply that is still being written.
                          ...(msg.streaming && {
                            "& > :last-child::after": {
                              content: '"▍"',
                              ml: 0.25,
                              color: "primary.main",
                              animation: `${blink} 1s step-end infinite`,
                            },
                          }),
                        }}
                      >
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {msg.text}
                        </ReactMarkdown>
                      </Box>
                    )}
                  </Box>
                )}

                {!isEditing && !msg.streaming && (
                  <Stack
                    direction={isUser ? "row-reverse" : "row"}
                    spacing={0.25}
                    sx={{ alignItems: "center", minHeight: 26, px: 0.5 }}
                  >
                    {endsGroup && formatTime(msg.createdAt) && (
                      <Typography
                        variant="caption"
                        sx={{ color: "text.secondary", fontSize: 11, px: 0.5 }}
                      >
                        {formatTime(msg.createdAt)}
                      </Typography>
                    )}
                    <Stack
                      className="msg-actions"
                      direction={isUser ? "row-reverse" : "row"}
                      sx={{
                        opacity: { xs: 0.75, md: 0 },
                        transition: "opacity 0.15s ease",
                      }}
                    >
                      <ActionButton title="Copy" onClick={() => onCopy(msg.text)}>
                        <ContentCopy sx={{ fontSize: 15 }} />
                      </ActionButton>
                      {showRegenerate && (
                        <ActionButton title="Try another answer" onClick={onRegenerate}>
                          <Refresh sx={{ fontSize: 17 }} />
                        </ActionButton>
                      )}
                      {canEdit && (
                        <ActionButton title="Edit" onClick={() => setEditingIndex(index)}>
                          <EditOutlined sx={{ fontSize: 16 }} />
                        </ActionButton>
                      )}
                      {!busy && (
                        <ActionButton title="Delete" onClick={() => onDelete(index)}>
                          <DeleteOutlined sx={{ fontSize: 16 }} />
                        </ActionButton>
                      )}
                    </Stack>
                  </Stack>
                )}
              </Stack>
            </Stack>
          </Fragment>
        );
      })}

      {isTyping && (
        <Box sx={{ mt: 1.25 }}>
          <TypingBubble />
        </Box>
      )}

      <Menu
        open={menu !== null}
        onClose={() => setMenu(null)}
        anchorReference="anchorPosition"
        anchorPosition={menu ? { top: menu.y, left: menu.x } : undefined}
      >
        <MenuItem
          onClick={() => {
            if (menu) onCopy(messages[menu.index]?.text ?? "");
            setMenu(null);
          }}
        >
          <ListItemIcon>
            <ContentCopy fontSize="small" />
          </ListItemIcon>
          Copy
        </MenuItem>
        {menu && menu.index === lastUserIndex && !busy && messages[menu.index]?.id !== undefined && (
          <MenuItem
            onClick={() => {
              setEditingIndex(menu.index);
              setMenu(null);
            }}
          >
            <ListItemIcon>
              <EditOutlined fontSize="small" />
            </ListItemIcon>
            Edit
          </MenuItem>
        )}
        <MenuItem
          disabled={busy}
          onClick={() => {
            if (menu) onDelete(menu.index);
            setMenu(null);
          }}
          sx={{ color: "error.main" }}
        >
          <ListItemIcon sx={{ color: "inherit" }}>
            <DeleteOutlined fontSize="small" />
          </ListItemIcon>
          Delete
        </MenuItem>
      </Menu>
    </Box>
  );
}
