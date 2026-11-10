import { useState, type FormEvent } from "react";
import {
  Alert,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  TextField,
} from "@mui/material";
import { deleteAccount } from "../../api/profileApi";

type DeleteAccountDialogProps = {
  open: boolean;
  onClose: () => void;
  onDeleted: () => void;
};

/** "Are you sure?" for deleting the account; asks for the password before anything is deleted. */
export default function DeleteAccountDialog({ open, onClose, onDeleted }: DeleteAccountDialogProps) {
  const [password, setPassword] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState("");

  const close = () => {
    if (deleting) return;
    setPassword("");
    setError("");
    onClose();
  };

  const handleDelete = async (event: FormEvent) => {
    event.preventDefault();
    setDeleting(true);
    setError("");
    try {
      await deleteAccount(password);
      onDeleted();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Couldn't delete your account.");
      setDeleting(false);
    }
  };

  return (
    <Dialog open={open} onClose={close} maxWidth="xs" fullWidth>
      <form onSubmit={handleDelete}>
        <DialogTitle sx={{ fontWeight: 700 }}>Delete your account?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Are you sure? This permanently deletes your account, our conversation, everything I remember about
            you, your reminders and your lists. <b>This can't be undone.</b>
          </DialogContentText>
          <TextField
            label="Your password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            helperText="Enter your password to confirm."
            autoFocus
            fullWidth
            sx={{ mt: 2.5 }}
          />
          {error && (
            <Alert severity="error" sx={{ mt: 2, borderRadius: 3 }}>
              {error}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5, gap: 1 }}>
          <Button onClick={close} disabled={deleting}>
            Keep my account
          </Button>
          <Button type="submit" color="error" variant="contained" disabled={!password || deleting}>
            {deleting ? <CircularProgress size={20} sx={{ color: "#fff" }} /> : "Delete forever"}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
