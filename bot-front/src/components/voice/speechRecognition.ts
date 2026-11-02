// The browser's speech recognition API isn't in TypeScript's standard types yet,
// so the few parts we use are described here.

interface SpeechRecognitionResult {
  [index: number]: { transcript: string; confidence: number };
  length: number;
  isFinal: boolean;
}

interface SpeechRecognitionEvent {
  results: SpeechRecognitionResult[];
  resultIndex: number;
}

interface SpeechRecognitionErrorEvent extends Event {
  error: string;
}

export interface SpeechRecognizer {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEvent) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  abort: () => void;
}

type RecognizerConstructor = new () => SpeechRecognizer;

function recognizerClass(): RecognizerConstructor | undefined {
  const w = window as unknown as {
    SpeechRecognition?: RecognizerConstructor;
    webkitSpeechRecognition?: RecognizerConstructor;
  };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition;
}

/** Chrome, Edge and Safari support it; Firefox doesn't. */
export function speechRecognitionSupported() {
  return recognizerClass() !== undefined;
}

/**
 * A recognizer that keeps listening and reports the whole text heard so far
 * (`onText`) every time it changes.
 */
export function createRecognizer(lang: string, onText: (text: string) => void): SpeechRecognizer | null {
  const Recognizer = recognizerClass();
  if (!Recognizer) return null;

  const recognizer = new Recognizer();
  recognizer.continuous = true;
  recognizer.interimResults = true;
  recognizer.lang = lang;
  recognizer.onresult = (event) => {
    let text = "";
    for (let i = 0; i < event.results.length; i++) {
      text += event.results[i][0].transcript;
      if (event.results[i].isFinal) text += " ";
    }
    onText(text);
  };
  recognizer.onerror = (event) => {
    if (event.error !== "no-speech" && event.error !== "aborted") {
      console.warn("Speech recognition error:", event.error);
    }
  };
  return recognizer;
}
