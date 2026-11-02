import { keyframes } from "@emotion/react";

/** A new bubble fades in and slides up 8px. */
export const popIn = keyframes`
  from { opacity: 0; transform: translateY(8px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
`;

/** One typing dot hopping; three dots with delays make the wave. */
export const bounce = keyframes`
  0%, 60%, 100% { transform: translateY(0); opacity: 0.45; }
  30%           { transform: translateY(-5px); opacity: 1; }
`;

/** The cursor at the end of a reply that is still being written. */
export const blink = keyframes`
  0%, 50%  { opacity: 1; }
  51%, 100% { opacity: 0; }
`;
