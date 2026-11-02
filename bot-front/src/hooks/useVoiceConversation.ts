import { useCallback, useEffect, useRef, useState } from "react";
import { createRecognizer, speechRecognitionSupported, type SpeechRecognizer } from "../components/voice/speechRecognition";
import type { WavePhase } from "../components/voice/SiriWave";
import {
  detectLang,
  isNaturalVoice,
  preferredVoice,
  shortVoiceName,
  toSpeakable,
  useVoices,
} from "../utils/voices";

export type VoicePhase = "listening" | "thinking" | "speaking";

const RECOGNITION_LANG = "el";
/** How long a pause in speech ends what you're saying. */
const SILENCE_MS = 1800;

/**
 * A spoken conversation: listen → (1.8 s of silence) → send → think → speak the reply → listen
 * again. Also exposes the microphone's analyser for the wave, and mute / speaker toggles.
 *
 * @param onSend sends what was heard and resolves with RoboFriend's reply
 */
export function useVoiceConversation(onSend: (text: string) => Promise<string>) {
  const [phase, setPhaseState] = useState<VoicePhase>("listening");
  const [micMuted, setMicMuted] = useState(false);
  const [speakerOn, setSpeakerOn] = useState(true);
  const [transcript, setTranscript] = useState("");
  const [lastHeard, setLastHeard] = useState("");
  const [reply, setReply] = useState("");
  const [analyser, setAnalyser] = useState<AnalyserNode | null>(null);
  const [supported] = useState(speechRecognitionSupported);
  const voices = useVoices();

  // Browser callbacks (speech events, timers) read these refs, so they always see current values.
  const mountedRef = useRef(true);
  const phaseRef = useRef<VoicePhase>("listening");
  const micMutedRef = useRef(false);
  const speakerOnRef = useRef(true);
  const voicesRef = useRef(voices);
  const onSendRef = useRef(onSend);
  const recognizerRef = useRef<SpeechRecognizer | null>(null);
  const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const heardRef = useRef("");
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  // Set below; lets the silence timer submit without a circular dependency.
  const submitRef = useRef<(text: string) => void>(() => undefined);

  useEffect(() => {
    onSendRef.current = onSend;
    voicesRef.current = voices;
  }, [onSend, voices]);

  const setPhase = useCallback((next: VoicePhase) => {
    phaseRef.current = next;
    setPhaseState(next);
  }, []);

  const stopListening = useCallback(() => {
    if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
    silenceTimerRef.current = null;
    const recognizer = recognizerRef.current;
    recognizerRef.current = null;
    try {
      recognizer?.abort();
    } catch {
      void 0;
    }
  }, []);

  const startListening = useCallback(function start() {
    if (recognizerRef.current || micMutedRef.current || !mountedRef.current) return;
    const recognizer = createRecognizer(RECOGNITION_LANG, (text) => {
      heardRef.current = text;
      setTranscript(text);
      // Every new word restarts the silence timer; when it runs out, send what was said.
      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = setTimeout(() => {
        const said = heardRef.current.trim();
        if (said) submitRef.current(said);
      }, SILENCE_MS);
    });
    if (!recognizer) return;
    // Browsers stop recognition on their own after a while; keep it going while listening.
    recognizer.onend = () => {
      if (recognizerRef.current !== recognizer) return;
      recognizerRef.current = null;
      if (phaseRef.current === "listening") start();
    };
    recognizerRef.current = recognizer;
    try {
      recognizer.start();
    } catch (err) {
      console.error("Speech recognition could not start:", err);
    }
  }, []);

  const backToListening = useCallback(() => {
    if (!mountedRef.current) return;
    heardRef.current = "";
    setTranscript("");
    setPhase("listening");
    startListening();
  }, [setPhase, startListening]);

  /** Reads the reply aloud with the best voice for its language, then listens again. */
  const speak = useCallback(
    (text: string) => {
      const speakable = toSpeakable(text);
      if (!window.speechSynthesis || !speakerOnRef.current || !speakable) {
        backToListening();
        return;
      }
      window.speechSynthesis.cancel();
      const lang = detectLang(speakable);
      const voice = preferredVoice(voicesRef.current, lang);
      const utterance = new SpeechSynthesisUtterance(speakable);
      utterance.lang = voice?.lang ?? (lang === "el" ? "el-GR" : "en-US");
      if (voice) utterance.voice = voice;
      // Neural voices pace themselves naturally; older ones sound better slightly faster.
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
      window.speechSynthesis.speak(utterance);
    },
    [backToListening, setPhase],
  );

  useEffect(() => {
    submitRef.current = (text: string) => {
      stopListening();
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
  }, [stopListening, setPhase, speak, backToListening]);

  // On open: get the microphone (for the wave) and start listening. On close: release everything.
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
        const context = new AudioContext();
        if (context.state === "suspended") await context.resume();
        const node = context.createAnalyser();
        node.fftSize = 1024;
        node.smoothingTimeConstant = 0.6;
        context.createMediaStreamSource(stream).connect(node);
        audioContextRef.current = context;
        if (!cancelled) setAnalyser(node);
      })
      .catch((err) => console.error("Microphone access denied:", err));

    startListening();

    return () => {
      cancelled = true;
      mountedRef.current = false;
      stopListening();
      utteranceRef.current = null;
      window.speechSynthesis?.cancel();
      if (audioContextRef.current?.state !== "closed") audioContextRef.current?.close().catch(() => undefined);
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    };
  }, [startListening, stopListening]);

  const toggleMic = () => {
    const muted = !micMutedRef.current;
    micMutedRef.current = muted;
    setMicMuted(muted);
    streamRef.current?.getAudioTracks().forEach((track) => {
      track.enabled = !muted;
    });
    if (muted) stopListening();
    else if (phaseRef.current === "listening") startListening();
  };

  const toggleSpeaker = () => {
    const on = !speakerOnRef.current;
    speakerOnRef.current = on;
    setSpeakerOn(on);
    // Turning the speaker off mid-answer goes straight back to listening.
    if (!on && phaseRef.current === "speaking") window.speechSynthesis?.cancel();
  };

  /** Stops the spoken answer; the utterance's end handler then returns to listening. */
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
  const voice = preferredVoice(voices, detectLang(reply || lastHeard || transcript));

  return {
    supported,
    phase,
    wavePhase,
    caption,
    lastHeard,
    hasTranscript: transcript.length > 0,
    analyser,
    micMuted,
    speakerOn,
    voiceName: voice ? shortVoiceName(voice) : null,
    toggleMic,
    toggleSpeaker,
    interrupt,
  };
}
