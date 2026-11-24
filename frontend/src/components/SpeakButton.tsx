import { useEffect, useRef, useState } from 'react';
import { speak, stopSpeaking, ttsSupported } from '../tts';

/** Reads a transcription aloud (text to speech). */
export function SpeakButton({ text, languages }: { text: string; languages: string | null }) {
  const [playing, setPlaying] = useState(false);
  const playingRef = useRef(false);
  playingRef.current = playing;

  // Stop our own playback if the button disappears (e.g. the item is deleted or the page changes).
  useEffect(() => () => {
    if (playingRef.current) stopSpeaking();
  }, []);

  if (!ttsSupported()) return null;

  const toggle = () => {
    if (playing) {
      stopSpeaking();
      setPlaying(false);
    } else {
      setPlaying(true);
      speak(text, languages, () => setPlaying(false));
    }
  };

  return (
    <button className="icon" onClick={toggle} title={playing ? 'Stop' : 'Listen (text to speech)'}>
      {playing ? '⏹' : '🔊'}
    </button>
  );
}
