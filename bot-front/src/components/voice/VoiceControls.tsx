import { IconButton, Stack, Tooltip } from "@mui/material";
import { CallEnd, Mic, MicOff, VolumeOff, VolumeUp } from "@mui/icons-material";
import { GLASS_BUTTON, OFF_BUTTON } from "./voiceStyles";

type VoiceControlsProps = {
  micMuted: boolean;
  speakerOn: boolean;
  onToggleMic: () => void;
  onToggleSpeaker: () => void;
  onHangUp: () => void;
};

/** The three round buttons at the bottom: mute, hang up (red), speaker. */
export default function VoiceControls({ micMuted, speakerOn, onToggleMic, onToggleSpeaker, onHangUp }: VoiceControlsProps) {
  return (
    <Stack direction="row" spacing={3} sx={{ justifyContent: "center", alignItems: "center", pt: 2 }}>
      <Tooltip title={micMuted ? "Unmute" : "Mute"}>
        <IconButton
          onClick={onToggleMic}
          aria-label={micMuted ? "Unmute microphone" : "Mute microphone"}
          sx={{ ...GLASS_BUTTON, ...(micMuted ? OFF_BUTTON : {}) }}
        >
          {micMuted ? <MicOff /> : <Mic />}
        </IconButton>
      </Tooltip>

      <Tooltip title="End voice chat">
        <IconButton
          onClick={onHangUp}
          aria-label="End voice chat"
          sx={{
            ...GLASS_BUTTON,
            width: 72,
            height: 72,
            bgcolor: "rgba(239, 68, 68, 0.85)",
            borderColor: "rgba(255, 255, 255, 0.25)",
            boxShadow: "0 10px 30px rgba(239, 68, 68, 0.45)",
            "&:hover": { bgcolor: "rgba(239, 68, 68, 1)", transform: "scale(1.05)" },
          }}
        >
          <CallEnd fontSize="large" />
        </IconButton>
      </Tooltip>

      <Tooltip title={speakerOn ? "Mute RoboFriend's voice" : "Turn RoboFriend's voice on"}>
        <IconButton
          onClick={onToggleSpeaker}
          aria-label={speakerOn ? "Disable speaker" : "Enable speaker"}
          sx={{ ...GLASS_BUTTON, ...(speakerOn ? {} : OFF_BUTTON) }}
        >
          {speakerOn ? <VolumeUp /> : <VolumeOff />}
        </IconButton>
      </Tooltip>
    </Stack>
  );
}
