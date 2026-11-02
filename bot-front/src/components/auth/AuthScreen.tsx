import { useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  IconButton,
  InputAdornment,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { Lock, Person, Visibility, VisibilityOff } from "@mui/icons-material";
import FriendAvatar from "../common/FriendAvatar";

type AuthScreenProps = {
  authMode: "login" | "register";
  username: string;
  password: string;
  authError: string;
  loading?: boolean;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  onToggleMode: () => void;
  onSubmit: (event: React.FormEvent) => void;
};

export default function AuthScreen({
  authMode,
  username,
  password,
  authError,
  loading = false,
  onUsernameChange,
  onPasswordChange,
  onToggleMode,
  onSubmit,
}: AuthScreenProps) {
  const [showPassword, setShowPassword] = useState(false);

  return (
    <Paper
      sx={{
        width: "100%",
        maxWidth: 420,
        p: { xs: 3, md: 4.5 },
        borderRadius: 7,
      }}
    >
      <Stack spacing={3.5}>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center" }}>
          <FriendAvatar size={64} online />
          <Typography variant="h4">RoboFriend</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 300 }}>
            {authMode === "login"
              ? "Good to see you again. I remembered where we left off."
              : "A friend who listens, remembers what matters, and keeps your plans on track."}
          </Typography>
        </Stack>

        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            <TextField
              label="Username"
              value={username}
              onChange={(event) => onUsernameChange(event.target.value)}
              fullWidth
              autoComplete="username"
              autoFocus
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <Person fontSize="small" />
                    </InputAdornment>
                  ),
                },
              }}
            />

            <TextField
              label="Password"
              type={showPassword ? "text" : "password"}
              value={password}
              onChange={(event) => onPasswordChange(event.target.value)}
              fullWidth
              autoComplete={
                authMode === "login" ? "current-password" : "new-password"
              }
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <Lock fontSize="small" />
                    </InputAdornment>
                  ),
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        size="small"
                        edge="end"
                        aria-label={showPassword ? "Hide password" : "Show password"}
                        onClick={() => setShowPassword((prev) => !prev)}
                      >
                        {showPassword ? (
                          <VisibilityOff fontSize="small" />
                        ) : (
                          <Visibility fontSize="small" />
                        )}
                      </IconButton>
                    </InputAdornment>
                  ),
                },
              }}
            />

            {authError && (
              <Alert severity="error" sx={{ borderRadius: 3 }}>
                {authError}
              </Alert>
            )}

            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={loading}
              sx={{ py: 1.4 }}
            >
              {loading ? (
                <CircularProgress size={22} sx={{ color: "#fff" }} />
              ) : authMode === "login" ? (
                "Sign in"
              ) : (
                "Create my profile"
              )}
            </Button>

            <Button type="button" variant="text" onClick={onToggleMode}>
              {authMode === "login"
                ? "New here? Create a profile"
                : "I already have an account"}
            </Button>
          </Stack>
        </Box>
      </Stack>
    </Paper>
  );
}
