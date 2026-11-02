import { useState } from "react";
import {
  Avatar,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  ListItemIcon,
  Menu,
  MenuItem,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { DarkMode, LightMode, Logout } from "@mui/icons-material";
import FriendAvatar from "../common/FriendAvatar";
import { ACCENT_GRADIENT, glassPanel } from "../../theme/theme";

type AppHeaderProps = {
  username?: string;
  darkMode: boolean;
  onToggleTheme: () => void;
  onLogout?: () => void;
};

export default function AppHeader({
  username,
  darkMode,
  onToggleTheme,
  onLogout,
}: AppHeaderProps) {
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  return (
    <Box
      component="header"
      sx={{ px: { xs: 0, sm: 1.5, md: 2.5 }, pt: { xs: 0, sm: 1.5, md: 2 }, flexShrink: 0 }}
    >
      <Stack
        direction="row"
        spacing={1.5}
        sx={(t) => ({
          ...glassPanel(t),
          maxWidth: 1360,
          mx: "auto",
          alignItems: "center",
          px: { xs: 1.75, md: 2.5 },
          py: 1,
          borderRadius: { xs: 0, sm: "999px" },
          borderWidth: { xs: "0 0 1px 0", sm: 1 },
        })}
      >
        <FriendAvatar size={30} />
        <Typography
          sx={{
            fontWeight: 800,
            fontSize: 19,
            letterSpacing: -0.5,
            background: ACCENT_GRADIENT,
            WebkitBackgroundClip: "text",
            backgroundClip: "text",
            color: "transparent",
          }}
        >
          RoboFriend
        </Typography>
        <Chip
          label="your AI friend"
          size="small"
          variant="outlined"
          sx={{ display: { xs: "none", sm: "flex" }, color: "text.secondary", borderColor: (t) => t.glass.border }}
        />

        <Box sx={{ flex: 1 }} />

        <Tooltip title={darkMode ? "Light mode" : "Dark mode"}>
          <IconButton onClick={onToggleTheme} aria-label="Toggle theme">
            {darkMode ? <LightMode /> : <DarkMode />}
          </IconButton>
        </Tooltip>

        {username && onLogout && (
          <>
            <Tooltip title={username}>
              <IconButton
                onClick={(event) => setMenuAnchor(event.currentTarget)}
                aria-label="Account menu"
                sx={{ p: 0.5 }}
              >
                <Avatar
                  sx={{
                    width: 32,
                    height: 32,
                    fontSize: 14,
                    fontWeight: 700,
                    background: ACCENT_GRADIENT,
                    color: "#fff",
                  }}
                >
                  {username.charAt(0).toUpperCase()}
                </Avatar>
              </IconButton>
            </Tooltip>

            <Menu
              anchorEl={menuAnchor}
              open={menuAnchor !== null}
              onClose={() => setMenuAnchor(null)}
              anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
              transformOrigin={{ vertical: "top", horizontal: "right" }}
            >
              <Box sx={{ px: 2, py: 1 }}>
                <Typography variant="caption" color="text.secondary">
                  Signed in as
                </Typography>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {username}
                </Typography>
              </Box>
              <MenuItem
                onClick={() => {
                  setMenuAnchor(null);
                  setConfirmOpen(true);
                }}
              >
                <ListItemIcon>
                  <Logout fontSize="small" />
                </ListItemIcon>
                Log out
              </MenuItem>
            </Menu>

            <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)} maxWidth="xs" fullWidth>
              <DialogTitle sx={{ fontWeight: 700 }}>Heading out?</DialogTitle>
              <DialogContent>
                <DialogContentText>
                  I'll remember our conversation for next time. Log out now?
                </DialogContentText>
              </DialogContent>
              <DialogActions sx={{ px: 3, pb: 2.5, gap: 1 }}>
                <Button onClick={() => setConfirmOpen(false)}>Stay</Button>
                <Button
                  onClick={() => {
                    setConfirmOpen(false);
                    onLogout();
                  }}
                  color="error"
                  variant="contained"
                >
                  Log out
                </Button>
              </DialogActions>
            </Dialog>
          </>
        )}
      </Stack>
    </Box>
  );
}
