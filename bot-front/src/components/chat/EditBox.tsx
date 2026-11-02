import { useState } from "react";
import { Box, Button, Stack } from "@mui/material";

type EditBoxProps = {
  initial: string;
  onCancel: () => void;
  onSave: (text: string) => void;
};

/** Inline editor that replaces a bubble while you edit your last message. Enter sends, Esc cancels. */
export default function EditBox({ initial, onCancel, onSave }: EditBoxProps) {
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
