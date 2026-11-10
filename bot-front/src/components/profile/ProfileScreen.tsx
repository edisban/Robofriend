import { useEffect, useState, type FormEvent } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { ArrowBack } from "@mui/icons-material";
import { getProfile, updateProfile, type Profile } from "../../api/profileApi";
import DeleteAccountDialog from "./DeleteAccountDialog";

type ProfileScreenProps = {
  onBack: () => void;
  onAccountDeleted: () => void;
  notify: (message: string) => void;
};

/** Today as yyyy-MM-dd in the user's timezone, the latest birthday the date picker allows. */
function today() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}

/**
 * The user's own page: the name RoboFriend calls them and their birthday (which RoboFriend
 * remembers and celebrates), and deleting the account.
 */
export default function ProfileScreen({ onBack, onAccountDeleted, notify }: ProfileScreenProps) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [displayName, setDisplayName] = useState("");
  const [birthday, setBirthday] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [deleteOpen, setDeleteOpen] = useState(false);

  useEffect(() => {
    getProfile()
      .then((loaded) => {
        setProfile(loaded);
        setDisplayName(loaded.displayName ?? "");
        setBirthday(loaded.birthday ?? "");
      })
      .catch(() => setLoadError(true));
  }, []);

  const unchanged =
    profile !== null && displayName.trim() === (profile.displayName ?? "") && birthday === (profile.birthday ?? "");

  const handleSave = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setSaveError("");
    try {
      const saved = await updateProfile({ displayName: displayName.trim(), birthday: birthday || null });
      setProfile(saved);
      setDisplayName(saved.displayName ?? "");
      setBirthday(saved.birthday ?? "");
      notify("Profile saved. I'll remember that! 🎉");
    } catch (error) {
      setSaveError(error instanceof Error ? error.message : "Couldn't save your profile.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Box sx={{ minHeight: "100%", display: "grid", placeItems: "center", px: 2, py: 4 }}>
      <Paper sx={{ width: "100%", maxWidth: 540, p: { xs: 3, md: 4.5 }, borderRadius: 7 }}>
        <Button startIcon={<ArrowBack />} onClick={onBack} sx={{ mb: 1, ml: -1 }}>
          Back to chat
        </Button>
        <Typography variant="h4" sx={{ fontWeight: 800, fontSize: { xs: "1.7rem", md: "2rem" } }}>
          Your profile
        </Typography>

        {loadError && (
          <Alert severity="error" sx={{ mt: 3, borderRadius: 3 }}>
            Couldn't load your profile. Try again in a moment.
          </Alert>
        )}
        {!profile && !loadError && (
          <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
            <CircularProgress />
          </Box>
        )}

        {profile && (
          <>
            <Typography color="text.secondary" sx={{ mt: 0.5 }}>
              Signed in as <b>{profile.username}</b> · friends since{" "}
              {new Date(profile.memberSince).toLocaleDateString(undefined, { month: "long", year: "numeric" })}
            </Typography>

            <Stack component="form" spacing={2.5} sx={{ mt: 3.5 }} onSubmit={handleSave}>
              <TextField
                label="What should RoboFriend call you?"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder={profile.username}
                slotProps={{ htmlInput: { maxLength: 50 } }}
                fullWidth
              />
              <TextField
                label="Birthday"
                type="date"
                value={birthday}
                onChange={(event) => setBirthday(event.target.value)}
                helperText="RoboFriend will wish you a happy birthday 🎂"
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today(), min: "1900-01-01" } }}
                fullWidth
              />
              {saveError && (
                <Alert severity="error" sx={{ borderRadius: 3 }}>
                  {saveError}
                </Alert>
              )}
              <Button type="submit" variant="contained" size="large" disabled={saving || unchanged}>
                {saving ? <CircularProgress size={22} sx={{ color: "#fff" }} /> : "Save changes"}
              </Button>
            </Stack>

            <Divider sx={{ my: 4 }} />

            <Box
              sx={{ border: 1, borderColor: "error.main", borderRadius: 4, p: 2.5 }}
              aria-labelledby="danger-zone-title"
              role="region"
            >
              <Typography id="danger-zone-title" sx={{ fontWeight: 700, color: "error.main" }}>
                Delete account
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
                Permanently deletes your account, our whole conversation, everything I remember about you,
                your reminders and your lists.
              </Typography>
              <Button color="error" variant="outlined" onClick={() => setDeleteOpen(true)}>
                Delete my account
              </Button>
            </Box>
          </>
        )}
      </Paper>

      <DeleteAccountDialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onDeleted={onAccountDeleted}
      />
    </Box>
  );
}
