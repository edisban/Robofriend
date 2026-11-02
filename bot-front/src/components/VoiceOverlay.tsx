import { useCallback, useEffect, useRef, useState } from "react";
import {
  Box,
  Button,
  Chip,
  IconButton,
  ListItemIcon,
  ListSubheader,
  Menu,
  MenuItem,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import {
  CallEnd,
  Check,
  Mic,
  MicOff,
  RecordVoiceOver,
  VolumeOff,
  VolumeUp,
} from "@mui/icons-material";
import { keyframes } from "@emotion/react";
import FriendAvatar from "./common/FriendAvatar";
import SiriWave, { type WavePhase } from "./voice/SiriWave";
import {
  detectLang,
  isNaturalVoice,
  preferredVoice,
  rememberVoice,
  shortVoiceName,
  toSpeakable,
  useVoices,
  voicesFor,
  type VoiceLang,
} from "../utils/voices";

interface SpeechRecognitionResult {
  [index: number]: { transcript: string; confidence: number };
  length: number;
  isFinal: boolean;
}

interface SpeechRecognitionErrorEvent extends Event {
  error: string;
  message: string;
}

interface SpeechRecognitionEvent {
  results: SpeechRecognitionResult[];
  resultIndex: number;
}

interface SpeechRecognitionInstance {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEvent) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}

interface WindowWithSpeechAPI extends Window {
  SpeechRecognition?: new () => SpeechRecognitionInstance;
  webkitSpeechRecognition?: new () => SpeechRecognitionInstance;
}

type Phase = "listening" | "thinking" | "speaking";

interface VoiceOverlayProps {
  onClose: () => void;
  /** Sends what the user said and resolves with RoboFriend's reply. */
  onSendTranscript: (transcript: string) => Promise<string>;
}

const RECOGNITION_LANG = "el";
const SILENCE_MS = 1800;

const PHASE_LABEL: Record<WavePhase, string> = {
  listening: "Listening…",
  thinking: "Thinking…",
  speaking: "Speaking…",
  muted: "Mic is muted",
};

const glow = keyframes`
  0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 0.55; }
  50%      { transform: translate(-50%, -50%) scale(1.15); opacity: 0.85; }
`;

const GLASS_BUTTON = {
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

const OFF_BUTTON = {
  bgcolor: "rgba(255, 255, 255, 0.9)",
  color: "#1e1b4b",
  "&:hover": { bgcolor: "#fff", transform: "scale(1.05)" },
};

const LANG_LABEL: Record<VoiceLang, string> = { el: "Greek", en: "English" };

export default function VoiceOverlay({ onClose, onSendTranscript }: VoiceOverlayProps) {
  const [phase, setPhaseState] = useState<Phase>("listening");
  const [micMuted, setMicMuted] = useState(false);
  const [volumeEnabled, setVolumeEnabled] = useState(true);
  const [transcript, setTranscript] = useState("");
  const [lastHeard, setLastHeard] = useState("");
  const [reply, setReply] = useState("");
  const [analyser, setAnalyser] = useState<AnalyserNode | null>(null);
  const [voiceMenuAnchor, setVoiceMenuAnchor] = useState<HTMLElement | null>(null);
  const [, setVoiceChoiceVersion] = useState(0);
  const voices = useVoices();
  const voicesRef = useRef(voices);
  const [unsupported] = useState(() => {
    const win = window as unknown as WindowWithSpeechAPI;
    return !(win.SpeechRecognition || win.webkitSpeechRecognition);
  });

  const mountedRef = useRef(true);
  const phaseRef = useRef<Phase>("listening");
  const micMutedRef = useRef(false);
  const volumeRef = useRef(true);
  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const finalTranscriptRef = useRef("");
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const onSendRef = useRef(onSendTranscript);
  // Set below; lets the recognition callback submit without a circular dependency.
  const submitRef = useRef<(text: string) => void>(() => undefined);

  useEffect(() => {
    onSendRef.current = onSendTranscript;
  }, [onSendTranscript]);

  useEffect(() => {
    voicesRef.current = voices;
  }, [voices]);

  const setPhase = useCallback((next: Phase) => {
    phaseRef.current = next;
    setPhaseState(next);
  }, []);

  const stopRecognition = useCallback(() => {
    if (silenceTimerRef.current) {
      clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = null;
    }
    const rec = recognitionRef.current;
    recognitionRef.current = null;
    if (rec) {
      try {
        rec.abort();
      } catch {
        void 0;
      }
    }
  }, []);

  const startRecognition = useCallback(function start() {
    if (recognitionRef.current || micMutedRef.current || !mountedRef.current) return;
    const win = window as unknown as WindowWithSpeechAPI;
    const SpeechCtor = win.SpeechRecognition || win.webkitSpeechRecognition;
    if (!SpeechCtor) return;

    const rec = new SpeechCtor();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = RECOGNITION_LANG;

    rec.onresult = (event) => {
      let full = "";
      for (let i = 0; i < event.results.length; i++) {
        full += event.results[i][0].transcript;
        if (event.results[i].isFinal) full += " ";
      }
      finalTranscriptRef.current = full;
      setTranscript(full);

      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = setTimeout(() => {
        const text = finalTranscriptRef.current.trim();
        if (text) submitRef.current(text);
      }, SILENCE_MS);
    };

    rec.onerror = (e) => {
      if (e.error !== "no-speech" && e.error !== "aborted") {
        console.warn("Speech recognition error:", e.error);
      }
    };

    // Browsers end recognition on their own after a while; keep it alive while listening.
    rec.onend = () => {
      if (recognitionRef.current !== rec) return;
      recognitionRef.current = null;
      if (phaseRef.current === "listening") start();
    };

    recognitionRef.current = rec;
    try {
      rec.start();
    } catch (err) {
      console.error("SpeechRecognition start error:", err);
    }
  }, []);

  const backToListening = useCallback(() => {
    if (!mountedRef.current) return;
    finalTranscriptRef.current = "";
    setTranscript("");
    setPhase("listening");
    startRecognition();
  }, [setPhase, startRecognition]);

  const speak = useCallback(
    (text: string) => {
      const synth = window.speechSynthesis;
      const speakable = toSpeakable(text);
      if (!synth || !volumeRef.current || !speakable) {
        backToListening();
        return;
      }
      synth.cancel();
      const lang = detectLang(speakable);
      const utterance = new SpeechSynthesisUtterance(speakable);
      const voice = preferredVoice(voicesRef.current, lang);
      utterance.lang = voice?.lang ?? (lang === "el" ? "el-GR" : "en-US");
      if (voice) utterance.voice = voice;
      // Neural voices already pace themselves naturally; speeding up older ones helps a little.
      utterance.rate = voice && isNaturalVoice(voice) ? 1 : 1.05;
      const finish = () => {
        if (utteranceRef.current !== utterance) return;
        utteranceRef.current = null;
        backToListening();
      };
      utterance.onend = finish;
      utterance.onerror = finish;
      utteranceRef.current = utterance;
      setPhase("speaking");
      synth.speak(utterance);
    },
    [backToListening, setPhase],
  );

  useEffect(() => {
    submitRef.current = (text: string) => {
      stopRecognition();
      setLastHeard(text);
      setReply("");
      setPhase("thinking");
      onSendRef
        .current(text)
        .then((answer) => {
          if (!mountedRef.current) return;
          setReply(answer);
          speak(answer);
        })
        .catch(() => backToListening());
    };
  }, [stopRecognition, setPhase, speak, backToListening]);

  // Mic + analyser + first listen on mount; tear everything down on unmount.
  useEffect(() => {
    mountedRef.current = true;
    let cancelled = false;

    navigator.mediaDevices
      ?.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      .then(async (stream) => {
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;
        const AudioCtor =
          window.AudioContext ||
          (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!AudioCtor) return;
        const ctx = new AudioCtor();
        if (ctx.state === "suspended") await ctx.resume();
        const node = ctx.createAnalyser();
        node.fftSize = 1024;
        node.smoothingTimeConstant = 0.6;
        ctx.createMediaStreamSource(stream).connect(node);
        audioContextRef.current = ctx;
        if (!cancelled) setAnalyser(node);
      })
      .catch((err) => console.error("Mic access denied:", err));

    startRecognition();

    return () => {
      cancelled = true;
      mountedRef.current = false;
      stopRecognition();
      utteranceRef.current = null;
      window.speechSynthesis?.cancel();
      if (audioContextRef.current && audioContextRef.current.state !== "closed") {
        audioContextRef.current.close().catch(() => undefined);
      }
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    };
  }, [startRecognition, stopRecognition]);

  const toggleMic = () => {
    const next = !micMutedRef.current;
    micMutedRef.current = next;
    setMicMuted(next);
    streamRef.current?.getAudioTracks().forEach((track) => {
      track.enabled = !next;
    });
    if (next) {
      stopRecognition();
    } else if (phaseRef.current === "listening") {
      startRecognition();
    }
  };

  const toggleVolume = () => {
    const next = !volumeRef.current;
    volumeRef.current = next;
    setVolumeEnabled(next);
    // Turning the speaker off mid-answer skips straight back to listening.
    if (!next && phaseRef.current === "speaking") window.speechSynthesis?.cancel();
  };

  const interrupt = () => {
    if (phaseRef.current === "speaking") window.speechSynthesis?.cancel();
  };

  const wavePhase: WavePhase = phase === "listening" && micMuted ? "muted" : phase;

  const caption =
    phase === "listening"
      ? transcript || (micMuted ? "Unmute to keep talking" : "Go ahead, I'm listening")
      : phase === "thinking"
        ? lastHeard
        : reply;

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
        WebkitBackdropFilter: "blur(30px) saturate(170%)",
        px: 2,
        py: { xs: 2.5, md: 4 },
      }}
    >
      <Tooltip title="Choose RoboFriend's voice">
        <IconButton
          onClick={(e) => setVoiceMenuAnchor(e.currentTarget)}
          aria-label="Choose voice"
          sx={{ ...GLASS_BUTTON, width: 44, height: 44, position: "absolute", top: { xs: 16, md: 28 }, right: { xs: 16, md: 28 } }}
        >
          <RecordVoiceOver fontSize="small" />
        </IconButton>
      </Tooltip>

      <Menu
        anchorEl={voiceMenuAnchor}
        open={voiceMenuAnchor !== null}
        onClose={() => setVoiceMenuAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        sx={{ zIndex: 1500 }}
        slotProps={{ paper: { sx: { maxHeight: "70vh", minWidth: 280 } } }}
      >
        {(["el", "en"] as VoiceLang[]).flatMap((lang) => {
          const options = voicesFor(voices, lang);
          const current = preferredVoice(voices, lang);
          return [
            <ListSubheader key={`h-${lang}`} sx={{ bgcolor: "transparent", lineHeight: "32px", fontWeight: 700 }}>
              {LANG_LABEL[lang]}
            </ListSubheader>,
            ...(options.length === 0
              ? [
                  <MenuItem key={`none-${lang}`} disabled>
                    No {LANG_LABEL[lang]} voices in this browser
                  </MenuItem>,
                ]
              : options.map((v) => (
                  <MenuItem
                    key={`${lang}-${v.name}`}
                    selected={current?.name === v.name}
                    onClick={() => {
                      rememberVoice(lang, v.name);
                      setVoiceChoiceVersion((n) => n + 1);
                      setVoiceMenuAnchor(null);
                    }}
                  >
                    <ListItemIcon>{current?.name === v.name && <Check fontSize="small" />}</ListItemIcon>
                    <Typography variant="body2" sx={{ flex: 1 }}>
                      {shortVoiceName(v)}
                    </Typography>
                    {isNaturalVoice(v) && (
                      <Chip label="natural" size="small" color="secondary" variant="outlined" sx={{ ml: 1, height: 20 }} />
                    )}
                  </MenuItem>
                ))),
            ...(lang === "el" && !options.some(isNaturalVoice)
              ? [
                  <Typography
                    key="hint-el"
                    variant="caption"
                    component="p"
                    sx={{ px: 2, pb: 1, color: "text.secondary", maxWidth: 280 }}
                  >
                    Tip: open RoboFriend in Microsoft Edge for natural-sounding Greek voices.
                  </Typography>,
                ]
              : []),
          ];
        })}
      </Menu>

      {/* Who you're talking to */}
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", justifyContent: "center" }}>
        <FriendAvatar size={40} online />
        <Box>
          <Typography sx={{ fontWeight: 700, lineHeight: 1.2 }}>RoboFriend</Typography>
          <Typography variant="caption" sx={{ color: "rgba(226,232,240,0.7)" }}>
            Voice conversation
            {(() => {
              const v = preferredVoice(voices, detectLang(reply || lastHeard || transcript));
              return v ? ` · ${shortVoiceName(v)}` : "";
            })()}
          </Typography>
        </Box>
      </Stack>

      {/* Status + what's being said */}
      <Stack
        spacing={2.5}
        sx={{ flex: 1, alignItems: "center", justifyContent: "center", textAlign: "center", minHeight: 0 }}
      >
        <Typography
          sx={{
            fontWeight: 800,
            fontSize: { xs: 30, md: 40 },
            letterSpacing: -0.8,
            background: "linear-gradient(90deg, #67e8f9, #c4b5fd, #f9a8d4)",
            WebkitBackgroundClip: "text",
            backgroundClip: "text",
            color: "transparent",
          }}
        >
          {unsupported ? "Voice isn't available" : PHASE_LABEL[wavePhase]}
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
          {phase === "speaking" && lastHeard && (
            <Typography variant="caption" component="p" sx={{ color: "rgba(226,232,240,0.6)", mb: 0.75 }}>
              You said: “{lastHeard}”
            </Typography>
          )}
          <Typography
            sx={{
              fontSize: { xs: 16, md: 19 },
              lineHeight: 1.5,
              color: phase === "listening" && !transcript ? "rgba(226,232,240,0.55)" : "#f5f3ff",
              fontStyle: phase === "thinking" ? "italic" : "normal",
            }}
          >
            {unsupported
              ? "This browser can't do speech recognition. Try Chrome or Edge."
              : caption}
          </Typography>
        </Box>

        {phase === "speaking" && (
          <Button onClick={interrupt} size="small" sx={{ color: "rgba(226,232,240,0.75)" }}>
            Tap to interrupt
          </Button>
        )}
      </Stack>

      {/* The wave */}
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
            background:
              "radial-gradient(ellipse, rgba(139, 92, 246, 0.55), rgba(34, 211, 238, 0.18) 55%, transparent 72%)",
            filter: "blur(30px)",
            animation: `${glow} ${phase === "thinking" ? 1.2 : 3}s ease-in-out infinite`,
            pointerEvents: "none",
          }}
        />
        <SiriWave phase={wavePhase} analyser={analyser} height={200} />
      </Box>

      {/* Controls */}
      <Stack direction="row" spacing={3} sx={{ justifyContent: "center", alignItems: "center", pt: 2 }}>
        <Tooltip title={micMuted ? "Unmute" : "Mute"}>
          <IconButton
            onClick={toggleMic}
            aria-label={micMuted ? "Unmute microphone" : "Mute microphone"}
            sx={{ ...GLASS_BUTTON, ...(micMuted ? OFF_BUTTON : {}) }}
          >
            {micMuted ? <MicOff /> : <Mic />}
          </IconButton>
        </Tooltip>

        <Tooltip title="End voice chat">
          <IconButton
            onClick={onClose}
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

        <Tooltip title={volumeEnabled ? "Mute RoboFriend's voice" : "Turn RoboFriend's voice on"}>
          <IconButton
            onClick={toggleVolume}
            aria-label={volumeEnabled ? "Disable speaker" : "Enable speaker"}
            sx={{ ...GLASS_BUTTON, ...(volumeEnabled ? {} : OFF_BUTTON) }}
          >
            {volumeEnabled ? <VolumeUp /> : <VolumeOff />}
          </IconButton>
        </Tooltip>
      </Stack>
    </Box>
  );
}
