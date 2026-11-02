import React from "react";
import { Box, IconButton, Tooltip } from "@mui/material";
import { ArrowUpward, Mic } from "@mui/icons-material";
import { glassPanel, ACCENT_GRADIENT, FRIEND_GRADIENT } from "../../theme/theme";

type MessageInputProps = {
  value: string;
  onChange: (value: string) => void;
  onSend: (value: string) => void;
  onVoice: () => void;
  disabled?: boolean;
};

const MAX_HEIGHT = 160;

export default function MessageInput({
  value,
  onChange,
  onSend,
  onVoice,
  disabled = false,
}: MessageInputProps) {
  const textareaRef = React.useRef<HTMLTextAreaElement>(null);
  const canSend = !disabled && value.trim().length > 0;

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (canSend) onSend(value.trim());
    }
  };

  React.useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`;
    el.style.overflowY = el.scrollHeight > MAX_HEIGHT ? "auto" : "hidden";
  }, [value]);

  React.useEffect(() => {
    if (!disabled) textareaRef.current?.focus();
  }, [disabled]);

  const roundButton = {
    width: 42,
    height: 42,
    flexShrink: 0,
    color: "#fff",
    transition: "transform 0.15s ease, box-shadow 0.2s ease, opacity 0.2s ease",
    "&:hover": { transform: "scale(1.06)" },
    "&.Mui-disabled": { color: "rgba(255,255,255,0.7)", opacity: 0.45 },
  };

  return (
    <Box sx={{ px: { xs: 1.25, md: 2.5 }, pb: { xs: 1.25, md: 2 }, pt: 1, flexShrink: 0 }}>
      <Box
        sx={(t) => ({
          ...glassPanel(t, true),
          display: "flex",
          alignItems: "flex-end",
          gap: 1,
          borderRadius: "26px",
          pl: 2.25,
          pr: 0.75,
          py: 0.75,
          transition: "box-shadow 0.2s ease, border-color 0.2s ease",
          "&:focus-within": {
            borderColor: t.palette.primary.main,
            boxShadow: `0 0 0 3px rgba(167, 139, 250, 0.22), ${t.glass.shadow}`,
          },
        })}
      >
        <Box
          component="textarea"
          ref={textareaRef}
          rows={1}
          value={value}
          onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
            onChange(event.target.value)
          }
          onKeyDown={handleKeyDown}
          placeholder="Message RoboFriend…"
          aria-label="Message"
          disabled={disabled}
          sx={{
            flex: 1,
            minWidth: 0,
            alignSelf: "center",
            py: 1.1,
            font: "inherit",
            fontSize: 15,
            lineHeight: 1.5,
            color: "text.primary",
            resize: "none",
            border: "none",
            outline: "none",
            background: "transparent",
            "&::placeholder": { color: "text.secondary", opacity: 1 },
          }}
        />
        <Tooltip title="Talk with your voice">
          <span>
            <IconButton
              onClick={onVoice}
              disabled={disabled}
              aria-label="Voice mode"
              sx={{
                ...roundButton,
                background: FRIEND_GRADIENT,
                boxShadow: "0 6px 18px rgba(99, 102, 241, 0.35)",
              }}
            >
              <Mic fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title="Send (Enter)">
          <span>
            <IconButton
              onClick={() => onSend(value.trim())}
              disabled={!canSend}
              aria-label="Send message"
              sx={{
                ...roundButton,
                background: ACCENT_GRADIENT,
                boxShadow: canSend ? "0 6px 20px rgba(236, 72, 153, 0.4)" : "none",
              }}
            >
              <ArrowUpward fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
      </Box>
    </Box>
  );
}
