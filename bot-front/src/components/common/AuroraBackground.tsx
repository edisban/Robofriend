import { Box } from "@mui/material";
import { keyframes } from "@emotion/react";

const drift = keyframes`
  0%   { transform: translate(0, 0) scale(1); }
  33%  { transform: translate(6vw, -4vh) scale(1.08); }
  66%  { transform: translate(-4vw, 5vh) scale(0.95); }
  100% { transform: translate(0, 0) scale(1); }
`;

const BLOBS = [
  { top: "-12%", left: "-8%", size: "52vmax", duration: 26 },
  { top: "35%", left: "60%", size: "46vmax", duration: 32 },
  { top: "70%", left: "-10%", size: "40vmax", duration: 29 },
  { top: "-20%", left: "55%", size: "34vmax", duration: 35 },
];

/** Soft, slowly drifting colour blobs that the glass panels blur over. */
export default function AuroraBackground() {
  return (
    <Box
      aria-hidden
      sx={{
        position: "fixed",
        inset: 0,
        zIndex: 0,
        overflow: "hidden",
        pointerEvents: "none",
        bgcolor: (t) => t.glass.base,
      }}
    >
      {BLOBS.map((blob, i) => (
        <Box
          key={i}
          sx={{
            position: "absolute",
            top: blob.top,
            left: blob.left,
            width: blob.size,
            height: blob.size,
            borderRadius: "50%",
            background: (t) =>
              `radial-gradient(circle at 50% 50%, ${t.glass.blobs[i]} 0%, transparent 65%)`,
            opacity: (t) => (t.palette.mode === "dark" ? 0.45 : 0.75),
            filter: "blur(40px)",
            animation: `${drift} ${blob.duration}s ease-in-out infinite`,
            animationDelay: `${-i * 6}s`,
          }}
        />
      ))}
    </Box>
  );
}
