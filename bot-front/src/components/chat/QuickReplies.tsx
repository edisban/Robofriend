import { Box, Chip } from "@mui/material";

const SUGGESTIONS = [
  "I'm feeling a bit stressed today",
  "Remind me tomorrow at 9am to call mom",
  "What do you remember about me?",
  "Help me plan my evening",
];

type QuickRepliesProps = {
  onPick: (text: string) => void;
  disabled?: boolean;
};

export default function QuickReplies({ onPick, disabled = false }: QuickRepliesProps) {
  return (
    <Box
      sx={{
        display: "flex",
        gap: 1,
        px: { xs: 1.25, md: 2.5 },
        pt: 1,
        overflowX: "auto",
        scrollbarWidth: "none",
        "&::-webkit-scrollbar": { display: "none" },
        flexShrink: 0,
      }}
    >
      {SUGGESTIONS.map((text) => (
        <Chip
          key={text}
          label={text}
          disabled={disabled}
          onClick={() => onPick(text)}
          sx={{
            flexShrink: 0,
            bgcolor: (t) => t.glass.surfaceStrong,
            border: (t) => `1px solid ${t.glass.border}`,
            backdropFilter: "blur(14px)",
            "&:hover": { bgcolor: (t) => t.glass.surfaceStrong, borderColor: "primary.main" },
          }}
        />
      ))}
    </Box>
  );
}
