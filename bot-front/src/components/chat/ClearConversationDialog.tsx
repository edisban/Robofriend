import { useState } from "react";
import {
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
} from "@mui/material";

type ClearConversationDialogProps = {
  open: boolean;
  onClose: () => void;
  /** Deletes the conversation; resolves when done, rejects on failure. */
  onConfirm: (forgetMemories: boolean) => Promise<void>;
};

/** "Delete the whole conversation?" with an option to also forget what RoboFriend learned. */
export default function ClearConversationDialog({ open, onClose, onConfirm }: ClearConversationDialogProps) {
  const [forgetMemories, setForgetMemories] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const confirm = async () => {
    setDeleting(true);
    try {
      await onConfirm(forgetMemories);
      setForgetMemories(false);
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Dialog open={open} onClose={() => !deleting && onClose()} maxWidth="xs" fullWidth>
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
        <Button onClick={onClose} disabled={deleting}>
          Cancel
        </Button>
        <Button onClick={confirm} color="error" variant="contained" disabled={deleting}>
          {deleting ? "Deleting…" : "Delete"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
