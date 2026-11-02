import { Box, Stack, Typography } from "@mui/material";
import { LockOutlined } from "@mui/icons-material";

export default function AppFooter() {
  return (
    <Box
      component="footer"
      sx={{
        display: { xs: "none", sm: "block" },
        px: { sm: 1.5, md: 2.5 },
        pb: { sm: 1.25, md: 1.5 },
        flexShrink: 0,
      }}
    >
      <Stack
        direction="row"
        spacing={2}
        sx={{
          maxWidth: 1360,
          mx: "auto",
          px: 2.5,
          alignItems: "center",
          justifyContent: "space-between",
          color: "text.secondary",
        }}
      >
        <Typography variant="caption">
          © {new Date().getFullYear()} RoboFriend · made with 💜
        </Typography>
        <Stack direction="row" spacing={0.75} sx={{ alignItems: "center" }}>
          <LockOutlined sx={{ fontSize: 13 }} />
          <Typography variant="caption">
            Your chats and memories are private to your account
          </Typography>
        </Stack>
      </Stack>
    </Box>
  );
}
