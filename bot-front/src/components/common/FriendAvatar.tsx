import { Box } from "@mui/material";
import { keyframes } from "@emotion/react";
import RobotGlyph from "./RobotGlyph";

const pulse = keyframes`
  0%   { box-shadow: 0 0 0 0 rgba(52, 211, 153, 0.6); }
  70%  { box-shadow: 0 0 0 7px rgba(52, 211, 153, 0); }
  100% { box-shadow: 0 0 0 0 rgba(52, 211, 153, 0); }
`;

type FriendAvatarProps = {
  size?: number;
  online?: boolean;
};

export default function FriendAvatar({ size = 32, online = false }: FriendAvatarProps) {
  return (
    <Box sx={{ position: "relative", flexShrink: 0, width: size, height: size }}>
      <Box
        sx={{
          width: size,
          height: size,
          borderRadius: "50%",
          display: "grid",
          placeItems: "center",
          background: "radial-gradient(circle at 50% 40%, rgba(99, 102, 241, 0.35), rgba(15, 16, 40, 0.9))",
          border: "1px solid rgba(196, 181, 253, 0.35)",
          boxShadow: "0 6px 18px rgba(99, 102, 241, 0.4)",
        }}
      >
        <RobotGlyph size={size * 0.78} />
      </Box>
      {online && (
        <Box
          sx={{
            position: "absolute",
            right: -1,
            bottom: -1,
            width: Math.max(10, size * 0.28),
            height: Math.max(10, size * 0.28),
            borderRadius: "50%",
            bgcolor: "success.main",
            border: (t) => `2px solid ${t.glass.base}`,
            animation: `${pulse} 2.2s infinite`,
          }}
        />
      )}
    </Box>
  );
}
