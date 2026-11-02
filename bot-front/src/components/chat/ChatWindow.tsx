import { Fragment, useState } from "react";
import { Box, Stack, Typography } from "@mui/material";
import { ContentCopy, DeleteOutlined, EditOutlined, Refresh } from "@mui/icons-material";
import FriendAvatar from "../common/FriendAvatar";
import { dayKey, dayLabel, formatTime } from "../../utils/dates";
import ActionButton from "./ActionButton";
import DaySeparator from "./DaySeparator";
import EditBox from "./EditBox";
import MessageBubble from "./MessageBubble";
import TypingBubble from "./TypingBubble";
import { popIn } from "./chatAnimations";
import type { Message } from "./types";

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

const AVATAR_SIZE = 30;

/**
 * The list of messages: groups consecutive messages from the same sender, adds day separators,
 * and shows the actions under each bubble (copy, try another answer, edit, delete).
 */
export default function ChatWindow({
  messages,
  isTyping = false,
  busy = false,
  onCopy,
  onDelete,
  onEdit,
  onRegenerate,
}: ChatWindowProps) {
  const [editingIndex, setEditingIndex] = useState<number | null>(null);

  const lastIndex = messages.length - 1;
  const lastUserIndex = messages.findLastIndex((m) => m.role === "user");

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
      {messages.map((msg, index) => {
        const isUser = msg.role === "user";
        const prev = messages[index - 1];
        const next = messages[index + 1];
        const day = dayKey(msg.createdAt);
        const newDay = day !== null && day !== dayKey(prev?.createdAt);
        const startsGroup = !prev || prev.role !== msg.role || newDay;
        const endsGroup = !next || next.role !== msg.role || dayKey(next.createdAt) !== day;

        const canEdit = isUser && index === lastUserIndex && !busy && msg.id !== undefined;
        const canRegenerate = !isUser && index === lastIndex && lastUserIndex >= 0 && !busy;

        return (
          <Fragment key={msg.id ?? `${msg.role}-${index}`}>
            {newDay && msg.createdAt && <DaySeparator label={dayLabel(msg.createdAt)} />}
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
              {/* RoboFriend's avatar sits next to the last bubble of a group. */}
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
                sx={{ maxWidth: { xs: "82%", sm: "75%", md: "68%" }, alignItems: isUser ? "flex-end" : "flex-start" }}
              >
                {editingIndex === index ? (
                  <EditBox
                    initial={msg.text}
                    onCancel={() => setEditingIndex(null)}
                    onSave={(text) => {
                      setEditingIndex(null);
                      onEdit(index, text);
                    }}
                  />
                ) : (
                  <MessageBubble message={msg} startsGroup={startsGroup} />
                )}

                {editingIndex !== index && !msg.streaming && (
                  <Stack
                    direction={isUser ? "row-reverse" : "row"}
                    spacing={0.25}
                    sx={{ alignItems: "center", minHeight: 26, px: 0.5 }}
                  >
                    {endsGroup && (
                      <Typography variant="caption" sx={{ color: "text.secondary", fontSize: 11, px: 0.5 }}>
                        {formatTime(msg.createdAt)}
                      </Typography>
                    )}
                    {/* Actions appear on hover (always faintly visible on phones). */}
                    <Stack
                      className="msg-actions"
                      direction={isUser ? "row-reverse" : "row"}
                      sx={{ opacity: { xs: 0.75, md: 0 }, transition: "opacity 0.15s ease" }}
                    >
                      <ActionButton title="Copy" onClick={() => onCopy(msg.text)}>
                        <ContentCopy sx={{ fontSize: 15 }} />
                      </ActionButton>
                      {canRegenerate && (
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
    </Box>
  );
}
