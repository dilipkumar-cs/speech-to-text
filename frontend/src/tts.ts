/**
 * Text-to-speech playback using the browser's built-in speech synthesis (no API key, works offline
 * with OS voices). Picks a Hindi voice for Devanagari / Hinglish text, otherwise Indian English.
 */
export function speak(text: string, languages: string | null, onEnd?: () => void): void {
  const synth = window.speechSynthesis;
  if (!synth) return;
  synth.cancel();

  const hindi = /[ऀ-ॿ]/.test(text) || (languages ?? '').includes('hi');
  const preferred = hindi ? ['hi-IN', 'en-IN'] : ['en-IN', 'en-GB', 'en-US'];
  const voices = synth.getVoices();
  const voice = preferred.map((lang) => voices.find((v) => v.lang === lang)).find(Boolean);

  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = voice?.lang ?? preferred[0];
  if (voice) utterance.voice = voice;
  utterance.onend = () => onEnd?.();
  utterance.onerror = () => onEnd?.();
  synth.speak(utterance);
}

export function stopSpeaking(): void {
  window.speechSynthesis?.cancel();
}

export const ttsSupported = () => typeof window !== 'undefined' && 'speechSynthesis' in window;
