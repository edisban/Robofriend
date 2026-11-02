import { useEffect, useState } from "react";
import { Box, Typography } from "@mui/material";
import { keyframes } from "@emotion/react";
import { ACCENT_GRADIENT } from "../../theme/theme";
import RobotGlyph from "./RobotGlyph";

const INTRO_MS = 4400;
const REDUCED_INTRO_MS = 600;
const FADE_MS = 650;

const float = keyframes`
  0%, 100% { transform: translateY(0); }
  50%      { transform: translateY(-8px); }
`;

const popIn = keyframes`
  0%   { transform: scale(0.3) translateY(20px); opacity: 0; }
  60%  { transform: scale(1.08) translateY(-4px); opacity: 1; }
  80%  { transform: scale(0.97) translateY(0); }
  100% { transform: scale(1) translateY(0); opacity: 1; }
`;

const sayHi = keyframes`
  0%, 100% { transform: rotate(0deg); }
  25%      { transform: rotate(-9deg); }
  60%      { transform: rotate(7deg); }
`;

const blink = keyframes`
  0%, 100% { transform: scaleY(1); }
  50%      { transform: scaleY(0.1); }
`;

const eyesOpen = keyframes`
  from { transform: scaleY(0.1); }
  to   { transform: scaleY(1); }
`;

const drawSmile = keyframes`
  from { stroke-dashoffset: 40; }
  to   { stroke-dashoffset: 0; }
`;

const antennaGlow = keyframes`
  0%, 100% { opacity: 0.55; filter: drop-shadow(0 0 2px #67e8f9); }
  50%      { opacity: 1; filter: drop-shadow(0 0 10px #67e8f9); }
`;

const ripple = keyframes`
  from { transform: translate(-50%, -50%) scale(0.6); opacity: 0.55; }
  to   { transform: translate(-50%, -50%) scale(2.4); opacity: 0; }
`;

const glowPulse = keyframes`
  0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 0.7; }
  50%      { transform: translate(-50%, -50%) scale(1.12); opacity: 1; }
`;

const letterRise = keyframes`
  from { transform: translateY(18px); opacity: 0; filter: blur(6px); }
  to   { transform: translateY(0); opacity: 1; filter: blur(0); }
`;

const fadeUp = keyframes`
  from { transform: translateY(8px); opacity: 0; }
  to   { transform: translateY(0); opacity: 1; }
`;

/** The intro version of RoboFriend's face: pops in, opens its eyes, smiles and tilts to say hi. */
function RobotFace() {
  return (
    <RobotGlyph
      size={{ xs: 120, md: 140 }}
      sx={{
        animation: `${popIn} 0.7s 0.15s cubic-bezier(.2,.9,.3,1.2) both, ${sayHi} 0.9s 1.15s ease-in-out`,
        transformOrigin: "50% 90%",
        "& .eye": {
          transformBox: "fill-box",
          transformOrigin: "center",
          animation: `${eyesOpen} 0.25s 0.75s ease-out both, ${blink} 0.22s 1.7s ease-in-out, ${blink} 0.22s 3.4s ease-in-out`,
        },
        "& .smile": {
          strokeDasharray: 40,
          animation: `${drawSmile} 0.5s 1s ease-out both`,
        },
        "& .bulb": { animation: `${antennaGlow} 1.2s 0.6s ease-in-out infinite` },
      }}
    />
  );
}

/**
 * Plays each time the app opens or refreshes: RoboFriend pops in, blinks, smiles and says hi,
 * then the screen fades into the app. Click or press a key to skip.
 */
export default function SplashIntro() {
  const [reducedMotion] = useState(
    () => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false,
  );
  const [stage, setStage] = useState<"show" | "leaving" | "gone">("show");

  useEffect(() => {
    if (stage !== "show") return;
    const timer = setTimeout(() => setStage("leaving"), reducedMotion ? REDUCED_INTRO_MS : INTRO_MS);
    const skip = () => setStage("leaving");
    window.addEventListener("keydown", skip);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("keydown", skip);
    };
  }, [stage, reducedMotion]);

  useEffect(() => {
    if (stage !== "leaving") return;
    const timer = setTimeout(() => setStage("gone"), FADE_MS);
    return () => clearTimeout(timer);
  }, [stage]);

  if (stage === "gone") return null;

  const name = "RoboFriend";

  return (
    <Box
      role="presentation"
      onClick={() => setStage("leaving")}
      sx={{
        position: "fixed",
        inset: 0,
        zIndex: 2000,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        cursor: "pointer",
        background: (t) =>
          `radial-gradient(ellipse at 50% 45%, rgba(99, 102, 241, 0.35), transparent 55%),
           radial-gradient(ellipse at 20% 15%, rgba(236, 72, 153, 0.22), transparent 50%),
           radial-gradient(ellipse at 85% 85%, rgba(20, 184, 166, 0.18), transparent 50%),
           ${t.glass.base}`,
        opacity: stage === "leaving" ? 0 : 1,
        transform: stage === "leaving" ? "scale(1.04)" : "none",
        filter: stage === "leaving" ? "blur(8px)" : "none",
        transition: `opacity ${FADE_MS}ms ease, transform ${FADE_MS}ms ease, filter ${FADE_MS}ms ease`,
        pointerEvents: stage === "leaving" ? "none" : "auto",
      }}
    >
      <Box sx={{ position: "relative", mb: 3 }}>
        {/* soft glow behind the robot */}
        <Box
          aria-hidden
          sx={{
            position: "absolute",
            left: "50%",
            top: "50%",
            width: 260,
            height: 260,
            borderRadius: "50%",
            background: "radial-gradient(circle, rgba(139, 92, 246, 0.55), rgba(34, 211, 238, 0.15) 55%, transparent 70%)",
            filter: "blur(18px)",
            animation: `${glowPulse} 2.2s ease-in-out infinite`,
          }}
        />
        {/* hello ripples */}
        {[0, 1].map((i) => (
          <Box
            key={i}
            aria-hidden
            sx={{
              position: "absolute",
              left: "50%",
              top: "50%",
              width: 150,
              height: 150,
              borderRadius: "50%",
              border: "2px solid rgba(196, 181, 253, 0.6)",
              animation: `${ripple} 1.4s ${0.9 + i * 0.35}s ease-out both`,
              opacity: 0,
            }}
          />
        ))}
        {/* After saying hi, RoboFriend floats gently while you wait. */}
        <Box sx={{ position: "relative", animation: `${float} 2.4s 2.1s ease-in-out infinite` }}>
          <RobotFace />
        </Box>
      </Box>

      <Typography
        component="h1"
        aria-label={name}
        sx={{ fontWeight: 800, fontSize: { xs: 40, md: 54 }, letterSpacing: -1.5, lineHeight: 1, display: "flex" }}
      >
        {name.split("").map((letter, i) => (
          <Box
            key={i}
            component="span"
            aria-hidden
            sx={{
              display: "inline-block",
              background: ACCENT_GRADIENT,
              backgroundSize: `${name.length * 100}% 100%`,
              backgroundPosition: `${(i / (name.length - 1)) * 100}% 0`,
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              color: "transparent",
              animation: `${letterRise} 0.5s ${0.9 + i * 0.05}s cubic-bezier(.2,.8,.2,1) both`,
            }}
          >
            {letter}
          </Box>
        ))}
      </Typography>

      <Typography
        sx={{
          mt: 1.5,
          color: "text.secondary",
          fontSize: { xs: 15, md: 17 },
          animation: `${fadeUp} 0.6s 1.5s ease-out both`,
        }}
      >
        your friend who remembers 💜
      </Typography>
    </Box>
  );
}
