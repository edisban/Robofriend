import { Box, Button, Paper, Stack, Typography } from "@mui/material";
import {
  ArrowForward,
  ChatBubbleOutlined,
  EventAvailable,
  GraphicEq,
  Psychology,
} from "@mui/icons-material";
import FriendAvatar from "../common/FriendAvatar";
import { timeOfDayGreeting } from "../../utils/greeting";

type WelcomeScreenProps = {
  username: string | undefined;
  onEnterChat: () => void;
};

const FEATURES = [
  { icon: <ChatBubbleOutlined fontSize="small" />, label: "Talk about anything" },
  { icon: <Psychology fontSize="small" />, label: "Remembers what matters" },
  { icon: <EventAvailable fontSize="small" />, label: "Keeps your reminders" },
  { icon: <GraphicEq fontSize="small" />, label: "Hands-free voice chat" },
];

export default function WelcomeScreen({
  username,
  onEnterChat,
}: WelcomeScreenProps) {
  return (
    <Box
      sx={{
        minHeight: "100%",
        display: "grid",
        placeItems: "center",
        px: 2,
        py: 4,
      }}
    >
      <Paper
        sx={{
          width: "100%",
          maxWidth: 540,
          p: { xs: 3, md: 5 },
          textAlign: "center",
          borderRadius: 7,
        }}
      >
        <Stack spacing={3} sx={{ alignItems: "center" }}>
          <FriendAvatar size={84} online />

          <Box>
            <Typography
              variant="h3"
              sx={{ fontSize: { xs: "1.9rem", md: "2.6rem" }, lineHeight: 1.15 }}
            >
              {timeOfDayGreeting()}, {username || "friend"} 👋
            </Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mt: 1.5 }}>
              How are you feeling today? I'm here whenever you want to talk,
              plan your day, or just think out loud.
            </Typography>
          </Box>

          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" },
              gap: 1.25,
              width: "100%",
            }}
          >
            {FEATURES.map((feature) => (
              <Stack
                key={feature.label}
                direction="row"
                spacing={1.25}
                sx={{
                  alignItems: "center",
                  px: 1.75,
                  py: 1.25,
                  borderRadius: 3,
                  bgcolor: (t) => t.glass.surfaceSubtle,
                  border: (t) => `1px solid ${t.glass.border}`,
                  color: "text.secondary",
                  textAlign: "left",
                }}
              >
                <Box sx={{ color: "primary.main", display: "flex" }}>{feature.icon}</Box>
                <Typography variant="body2">{feature.label}</Typography>
              </Stack>
            ))}
          </Box>

          <Button
            variant="contained"
            size="large"
            onClick={onEnterChat}
            endIcon={<ArrowForward />}
            sx={{ px: 4, py: 1.4 }}
          >
            Let's talk
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}
