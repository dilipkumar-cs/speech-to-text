/**
 * Text-to-speech playback using the browser's built-in speech synthesis (no API key, works offline
 * with OS voices). Picks a Kannada or Hindi voice for those scripts / language tags, otherwise Indian English.
 */
function preferredLanguages(text: string, languages: string | null): string[] {
  const tags = (languages ?? '').split('+');
  if (/[ಀ-೿]/.test(text) || tags.includes('kn')) return ['kn-IN', 'en-IN'];
  if (/[ऀ-ॿ]/.test(text) || tags.includes('hi')) return ['hi-IN', 'en-IN'];
  return ['en-IN', 'en-GB', 'en-US'];
}

export function speak(text: string, languages: string | null, onEnd?: () => void): void {
  const synth = window.speechSynthesis;
  if (!synth) return;
  synth.cancel();

  const preferred = preferredLanguages(text, languages);
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
