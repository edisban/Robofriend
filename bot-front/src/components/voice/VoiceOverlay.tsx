import { useState } from "react";
import { Box, Button, Stack, Typography } from "@mui/material";
import FriendAvatar from "../common/FriendAvatar";
import { useVoiceConversation } from "../../hooks/useVoiceConversation";
import SiriWave from "./SiriWave";
import VoiceControls from "./VoiceControls";
import VoicePicker from "./VoicePicker";
import { glow } from "./voiceStyles";

type VoiceOverlayProps = {
  onClose: () => void;
  /** Sends what the user said and resolves with RoboFriend's reply. */
  onSendTranscript: (transcript: string) => Promise<string>;
};

const PHASE_LABEL = {
  listening: "Listening…",
  thinking: "Thinking…",
  speaking: "Speaking…",
  muted: "Mic is muted",
};

/** Full-screen voice mode: status, what's being said, the Siri wave and the controls. */
export default function VoiceOverlay({ onClose, onSendTranscript }: VoiceOverlayProps) {
  const voice = useVoiceConversation(onSendTranscript);
  // Re-render after a new voice is picked, so its name shows under the title.
  const [, setVoicePicked] = useState(0);

  return (
    <Box
      role="dialog"
      aria-label="Voice conversation with RoboFriend"
      sx={{
        position: "fixed",
        inset: 0,
        zIndex: 1400,
        display: "flex",
        flexDirection: "column",
        color: "#f5f3ff",
        background:
          "radial-gradient(ellipse at 50% 55%, rgba(99, 102, 241, 0.30), transparent 60%), radial-gradient(ellipse at 20% 10%, rgba(236, 72, 153, 0.18), transparent 50%), rgba(6, 7, 20, 0.78)",
        backdropFilter: "blur(30px) saturate(170%)",
        px: 2,
        py: { xs: 2.5, md: 4 },
      }}
    >
      <VoicePicker onChange={() => setVoicePicked((n) => n + 1)} />

      {/* Who you're talking to */}
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", justifyContent: "center" }}>
        <FriendAvatar size={40} online />
        <Box>
          <Typography sx={{ fontWeight: 700, lineHeight: 1.2 }}>RoboFriend</Typography>
          <Typography variant="caption" sx={{ color: "rgba(226,232,240,0.7)" }}>
            Voice conversation{voice.voiceName ? ` · ${voice.voiceName}` : ""}
          </Typography>
        </Box>
      </Stack>

      {/* Status + what's being said */}
      <Stack spacing={2.5} sx={{ flex: 1, alignItems: "center", justifyContent: "center", textAlign: "center", minHeight: 0 }}>
        <Typography
          sx={{
            fontWeight: 800,
            fontSize: { xs: 30, md: 40 },
            letterSpacing: -0.8,
            background: "linear-gradient(90deg, #67e8f9, #c4b5fd, #f9a8d4)",
            backgroundClip: "text",
            color: "transparent",
          }}
        >
          {voice.supported ? PHASE_LABEL[voice.wavePhase] : "Voice isn't available"}
        </Typography>

        <Box
          sx={{
            maxWidth: 620,
            width: "100%",
            maxHeight: "28vh",
            overflowY: "auto",
            px: 3,
            py: 2,
            borderRadius: 5,
            bgcolor: "rgba(255, 255, 255, 0.06)",
            border: "1px solid rgba(255, 255, 255, 0.12)",
            backdropFilter: "blur(18px)",
            boxShadow: "inset 0 1px 0 rgba(255,255,255,0.10)",
          }}
        >
          {voice.phase === "speaking" && voice.lastHeard && (
            <Typography variant="caption" component="p" sx={{ color: "rgba(226,232,240,0.6)", mb: 0.75 }}>
              You said: “{voice.lastHeard}”
            </Typography>
          )}
          <Typography
            sx={{
              fontSize: { xs: 16, md: 19 },
              lineHeight: 1.5,
              color: voice.phase === "listening" && !voice.hasTranscript ? "rgba(226,232,240,0.55)" : "#f5f3ff",
              fontStyle: voice.phase === "thinking" ? "italic" : "normal",
            }}
          >
            {voice.supported ? voice.caption : "This browser can't do speech recognition. Try Chrome or Edge."}
          </Typography>
        </Box>

        {voice.phase === "speaking" && (
          <Button onClick={voice.interrupt} size="small" sx={{ color: "rgba(226,232,240,0.75)" }}>
            Tap to interrupt
          </Button>
        )}
      </Stack>

      {/* The wave, with a soft glow behind it */}
      <Box sx={{ position: "relative", mx: { xs: -2, md: "auto" }, width: { md: "min(1000px, 100%)" } }}>
        <Box
          aria-hidden
          sx={{
            position: "absolute",
            left: "50%",
            top: "50%",
            width: { xs: 260, md: 420 },
            height: { xs: 140, md: 200 },
            borderRadius: "50%",
            background: "radial-gradient(ellipse, rgba(139, 92, 246, 0.55), rgba(34, 211, 238, 0.18) 55%, transparent 72%)",
            filter: "blur(30px)",
            animation: `${glow} ${voice.phase === "thinking" ? 1.2 : 3}s ease-in-out infinite`,
            pointerEvents: "none",
          }}
        />
        <SiriWave phase={voice.wavePhase} analyser={voice.analyser} height={200} />
      </Box>

      <VoiceControls
        micMuted={voice.micMuted}
        speakerOn={voice.speakerOn}
        onToggleMic={voice.toggleMic}
        onToggleSpeaker={voice.toggleSpeaker}
        onHangUp={onClose}
      />
    </Box>
  );
}
