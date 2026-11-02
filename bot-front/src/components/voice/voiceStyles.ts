import { keyframes } from "@emotion/react";

/** Round frosted-glass button used for the voice controls. */
export const GLASS_BUTTON = {
  width: 60,
  height: 60,
  color: "#fff",
  bgcolor: "rgba(255, 255, 255, 0.08)",
  border: "1px solid rgba(255, 255, 255, 0.16)",
  backdropFilter: "blur(16px)",
  boxShadow: "inset 0 1px 0 rgba(255,255,255,0.12)",
  transition: "transform 0.15s ease, background-color 0.2s ease",
  "&:hover": { bgcolor: "rgba(255, 255, 255, 0.16)", transform: "scale(1.05)" },
};

/** Added on top of GLASS_BUTTON when something is switched off (mic muted, speaker off). */
export const OFF_BUTTON = {
  bgcolor: "rgba(255, 255, 255, 0.9)",
  color: "#1e1b4b",
  "&:hover": { bgcolor: "#fff", transform: "scale(1.05)" },
};

/** The soft glow behind the wave breathing in and out. */
export const glow = keyframes`
  0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 0.55; }
  50%      { transform: translate(-50%, -50%) scale(1.15); opacity: 0.85; }
`;
