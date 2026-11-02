import { Box, Stack } from "@mui/material";
import FriendAvatar from "../common/FriendAvatar";
import { bounce, popIn } from "./chatAnimations";

/** RoboFriend's avatar with three bouncing dots, shown until the first word of a reply arrives. */
export default function TypingBubble() {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: "flex-end", animation: `${popIn} 0.25s ease-out` }}
      aria-label="RoboFriend is typing"
    >
      <FriendAvatar size={30} />
      <Box
        sx={{
          px: 2,
          py: 1.5,
          borderRadius: "20px 20px 20px 6px",
          bgcolor: (t) => t.glass.surfaceStrong,
          border: (t) => `1px solid ${t.glass.border}`,
          backdropFilter: "blur(18px)",
          display: "flex",
          gap: 0.6,
        }}
      >
        {[0, 1, 2].map((i) => (
          <Box
            key={i}
            sx={{
              width: 7,
              height: 7,
              borderRadius: "50%",
              bgcolor: "text.secondary",
              animation: `${bounce} 1.2s ${i * 0.15}s infinite ease-in-out`,
            }}
          />
        ))}
      </Box>
    </Stack>
  );
}
