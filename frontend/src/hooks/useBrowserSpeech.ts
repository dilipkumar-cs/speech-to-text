import { useCallback, useEffect, useRef, useState } from 'react';

// Minimal typings for the (still prefixed in Chromium) Web Speech API.
interface SpeechRecognitionResultLike {
  isFinal: boolean;
  0: { transcript: string };
}
interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: ArrayLike<SpeechRecognitionResultLike>;
}
interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}
type Ctor = new () => SpeechRecognitionLike;

function recognitionCtor(): Ctor | undefined {
  const w = window as unknown as { SpeechRecognition?: Ctor; webkitSpeechRecognition?: Ctor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition;
}

export const browserSpeechSupported = () => recognitionCtor() !== undefined;

/**
 * Fallback engine that runs entirely in the browser (Chrome / Edge). "hi-IN" recognises Hindi in
 * Devanagari and keeps English words in Latin script, which handles most Hinglish speech.
 */
export function useBrowserSpeech() {
  const [recording, setRecording] = useState(false);
  const [finalText, setFinalText] = useState('');
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string | null>(null);

  const recognition = useRef<SpeechRecognitionLike | null>(null);
  const wantRecording = useRef(false);
  const committed = useRef('');
  const startedAt = useRef(0);

  const start = useCallback((lang: string) => {
    const SR = recognitionCtor();
    if (!SR) {
      setError('This browser does not support the Web Speech API. Use Chrome or Edge, or the Gemini engine.');
      return;
    }
    setError(null);
    setFinalText('');
    setInterim('');
    committed.current = '';
    startedAt.current = Date.now();

    const rec = new SR();
    rec.lang = lang;
    rec.continuous = true;
    rec.interimResults = true;
    rec.onresult = (e) => {
      let pending = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) committed.current = `${committed.current} ${r[0].transcript}`.trim();
        else pending += r[0].transcript;
      }
      setFinalText(committed.current);
      setInterim(pending.trim());
    };
    rec.onerror = (e) => {
      if (e.error !== 'no-speech' && e.error !== 'aborted') setError(`Speech recognition error: ${e.error}`);
      if (e.error === 'not-allowed') wantRecording.current = false;
    };
    // Chrome ends recognition after a pause; restart transparently while the user is still recording.
    rec.onend = () => {
      if (wantRecording.current) rec.start();
      else setRecording(false);
    };
    recognition.current = rec;
    wantRecording.current = true;
    rec.start();
    setRecording(true);
  }, []);

  /** @returns the final transcript and the recording length in seconds */
  const stop = useCallback(() => {
    wantRecording.current = false;
    recognition.current?.stop();
    setInterim('');
    return {
      text: committed.current,
      durationSeconds: Math.round((Date.now() - startedAt.current) / 1000),
    };
  }, []);

  const reset = useCallback(() => {
    wantRecording.current = false;
    recognition.current?.abort();
    committed.current = '';
    setFinalText('');
    setInterim('');
  }, []);

  useEffect(() => () => recognition.current?.abort(), []);

  return { recording, finalText, interim, error, start, stop, reset };
}
