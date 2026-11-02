import { useEffect, useState } from "react";

export type VoiceLang = "el" | "en";

const STORAGE_PREFIX = "robofriend-voice-";

// Emoji, flags, skin tones and the invisible joiners between them.
const EMOJI = /\p{Extended_Pictographic}|\p{Regional_Indicator}|\p{Emoji_Modifier}|‍|️|⃣/gu;

/** Turns a chat reply into something that sounds natural read aloud. */
export function toSpeakable(text: string) {
  return text
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/\[(.*?)\]\(.*?\)/g, "$1")
    .replace(EMOJI, "")
    .replace(/[*_`#>~|]/g, "")
    .replace(/\s*[—–]\s*/g, ", ")
    .replace(/\s+([,.!?;])/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

export function detectLang(text: string): VoiceLang {
  return /[Ͱ-Ͽἀ-῿]/.test(text) ? "el" : "en";
}

/** Neural voices (Edge "Natural", Google, Apple "Premium") sound far less robotic. */
export function isNaturalVoice(voice: SpeechSynthesisVoice) {
  return /natural|neural|online|premium|enhanced/i.test(voice.name);
}

function score(voice: SpeechSynthesisVoice, lang: VoiceLang) {
  let s = 0;
  if (/natural|neural/i.test(voice.name)) s += 100;
  else if (/online|premium|enhanced/i.test(voice.name)) s += 80;
  if (/google/i.test(voice.name)) s += 60;
  if (!voice.localService) s += 10;
  if (lang === "el" ? voice.lang === "el-GR" : /^en-(US|GB)$/.test(voice.lang)) s += 5;
  return s;
}

export function voicesFor(all: SpeechSynthesisVoice[], lang: VoiceLang) {
  return all
    .filter((v) => v.lang.toLowerCase().startsWith(lang))
    .sort((a, b) => score(b, lang) - score(a, lang) || a.name.localeCompare(b.name));
}

export function preferredVoice(all: SpeechSynthesisVoice[], lang: VoiceLang) {
  const candidates = voicesFor(all, lang);
  let saved: string | null = null;
  try {
    saved = localStorage.getItem(STORAGE_PREFIX + lang);
  } catch {
    saved = null;
  }
  return candidates.find((v) => v.name === saved) ?? candidates[0] ?? null;
}

export function rememberVoice(lang: VoiceLang, name: string) {
  try {
    localStorage.setItem(STORAGE_PREFIX + lang, name);
  } catch {
    void 0;
  }
}

/** Short display name, e.g. "Microsoft Athina Online (Natural) - Greek (Greece)" -> "Athina". */
export function shortVoiceName(voice: SpeechSynthesisVoice) {
  return voice.name
    .replace(/^(Microsoft|Google|Apple)\s+/i, "")
    .replace(/\s*\(.*$/, "")
    .replace(/\s+-\s+.*$/, "")
    .replace(/\s+Online$/i, "")
    .trim() || voice.name;
}

/** Browser voices load asynchronously (Chrome reports none at first). */
export function useVoices() {
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>(
    () => window.speechSynthesis?.getVoices() ?? [],
  );
  useEffect(() => {
    const synth = window.speechSynthesis;
    if (!synth) return;
    const update = () => setVoices(synth.getVoices());
    synth.addEventListener("voiceschanged", update);
    return () => synth.removeEventListener("voiceschanged", update);
  }, []);
  return voices;
}
