import { Box, Typography } from "@mui/material";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ACCENT_GRADIENT } from "../../theme/theme";
import { blink } from "./chatAnimations";
import type { Message } from "./types";

type MessageBubbleProps = {
  message: Message;
  /** First message of a run from the same sender: its top corner stays round. */
  startsGroup: boolean;
};

/**
 * One bubble: yours is a purple gradient with plain text; RoboFriend's is glass and renders
 * markdown (lists, bold, code). Corners on the sender's side tighten inside a group, like a messenger.
 */
export default function MessageBubble({ message, startsGroup }: MessageBubbleProps) {
  const isUser = message.role === "user";
  const big = "20px";
  const small = "6px";
  const radius = isUser
    ? `${big} ${startsGroup ? big : small} ${small} ${big}`
    : `${startsGroup ? big : small} ${big} ${big} ${small}`;

  if (isUser) {
    return (
      <Box
        sx={{
          px: 1.75,
          py: 1.1,
          borderRadius: radius,
          maxWidth: "100%",
          overflowWrap: "anywhere",
          background: ACCENT_GRADIENT,
          color: "#fff",
          boxShadow: "0 6px 20px rgba(168, 85, 247, 0.30)",
        }}
      >
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
          {message.text}
        </Typography>
      </Box>
    );
  }

  return (
    <Box
      sx={{
        px: 1.75,
        py: 1.1,
        borderRadius: radius,
        maxWidth: "100%",
        overflowWrap: "anywhere",
        bgcolor: (t) => t.glass.surfaceStrong,
        color: "text.primary",
        border: (t) => `1px solid ${t.glass.border}`,
        backdropFilter: "blur(18px) saturate(160%)",
        boxShadow: (t) => `inset 0 1px 0 ${t.glass.highlight}`,
        typography: "body2",
        // Markdown spacing inside a bubble.
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
          borderRadius: 1,
          bgcolor: (t) => t.glass.surfaceSubtle,
        },
        "& pre": { p: 1.25, borderRadius: 2, overflowX: "auto", bgcolor: (t) => t.glass.surfaceSubtle },
        "& pre code": { p: 0, bgcolor: "transparent" },
        // Blinking cursor at the end of a reply that is still being written.
        ...(message.streaming && {
          "& > :last-child::after": {
            content: '"▍"',
            ml: 0.25,
            color: "primary.main",
            animation: `${blink} 1s step-end infinite`,
          },
        }),
      }}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.text}</ReactMarkdown>
    </Box>
  );
}
