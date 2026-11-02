import { useId } from "react";
import { Box, type SxProps, type Theme } from "@mui/material";

type RobotGlyphProps = {
  size: number | Record<string, number>;
  /** Animation hooks: style `.eye`, `.smile` and `.bulb` from here (used by the intro). */
  sx?: SxProps<Theme>;
};

/** RoboFriend's face — the same character in the intro, the avatar and the header. */
export default function RobotGlyph({ size, sx }: RobotGlyphProps) {
  // Unique gradient ids, so several robots on one page don't share (and break) each other's defs.
  const id = useId().replace(/:/g, "");
  return (
    <Box
      component="svg"
      viewBox="0 0 120 120"
      aria-hidden
      sx={[{ width: size, height: size, overflow: "visible", display: "block" }, ...(Array.isArray(sx) ? sx : [sx])]}
    >
      <defs>
        <linearGradient id={`${id}-head`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#22d3ee" />
          <stop offset="55%" stopColor="#6366f1" />
          <stop offset="100%" stopColor="#a855f7" />
        </linearGradient>
        <linearGradient id={`${id}-shine`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
      {/* antenna */}
      <line x1="60" y1="24" x2="60" y2="11" stroke="#c4b5fd" strokeWidth="4" strokeLinecap="round" />
      <circle className="bulb" cx="60" cy="8" r="6" fill="#67e8f9" />
      {/* ears */}
      <rect x="8" y="50" width="12" height="22" rx="5" fill="#8b5cf6" />
      <rect x="100" y="50" width="12" height="22" rx="5" fill="#8b5cf6" />
      {/* head */}
      <rect x="16" y="24" width="88" height="74" rx="28" fill={`url(#${id}-head)`} />
      <rect x="22" y="28" width="76" height="26" rx="14" fill={`url(#${id}-shine)`} />
      {/* face screen */}
      <rect x="28" y="38" width="64" height="48" rx="19" fill="#0b0c1f" fillOpacity="0.88" />
      {/* eyes */}
      <rect className="eye" x="40" y="50" width="12" height="15" rx="6" fill="#67e8f9" />
      <rect className="eye" x="68" y="50" width="12" height="15" rx="6" fill="#67e8f9" />
      {/* cheeks */}
      <circle cx="36" cy="72" r="4" fill="#f472b6" fillOpacity="0.45" />
      <circle cx="84" cy="72" r="4" fill="#f472b6" fillOpacity="0.45" />
      {/* smile */}
      <path className="smile" d="M48 72 Q60 81 72 72" stroke="#f9a8d4" strokeWidth="4" strokeLinecap="round" fill="none" />
    </Box>
  );
}
