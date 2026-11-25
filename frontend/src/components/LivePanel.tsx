import { useEffect, useRef, useState } from 'react';
import { useLiveTranscription } from '../hooks/useLiveTranscription';
import type { AppInfo, Transcription } from '../types';
import { SpeakButton } from './SpeakButton';

interface Props {
  info: AppInfo | null;
  /** Whether the Live tab is currently shown. The panel stays mounted so a recording survives tab switches. */
  active: boolean;
  onSaved: () => void;
  onOpenHistory: () => void;
}

const formatTime = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;

const STATUS_LABEL = {
  idle: 'Ready',
  connecting: 'Connecting…',
  recording: 'Listening',
  stopping: 'Finalising & saving…',
} as const;

export function LivePanel({ info, active, onSaved, onOpenHistory }: Props) {
  const [title, setTitle] = useState('');
  const [saved, setSaved] = useState<Transcription | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);

  const live = useLiveTranscription((t) => {
    setSaved(t);
    setNotice(t ? null : 'No speech was detected, so nothing was saved.');
    if (t) onSaved();
  });

  const recording = live.status !== 'idle';
  const engineMissing = info !== null && !info.geminiEnabled;

  // Coming back to the Live tab after a finished recording starts fresh: the old result may have been
  // edited or deleted in History meanwhile. A recording that is still in progress is left untouched.
  const wasActive = useRef(active);
  const { reset } = live;
  useEffect(() => {
    if (active && !wasActive.current && !recording) {
      reset();
      setSaved(null);
      setNotice(null);
    }
    wasActive.current = active;
  }, [active, recording, reset]);

  useEffect(() => {
    if (!recording) return;
    setElapsed(0);
    const started = Date.now();
    const timer = setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 250);
    return () => clearInterval(timer);
  }, [recording]);

  const startRecording = () => {
    setSaved(null);
    setNotice(null);
    void live.start();
  };

  const statusLabel =
    live.status === 'recording' && !live.engineReady ? 'Starting engine…' : STATUS_LABEL[live.status];
  const maxSeconds = info?.maxSessionSeconds ?? 570;

  return (
    <section className="card live">
      <label className="field">
        <span>Title (optional)</span>
        <input
          value={title}
          maxLength={200}
          disabled={live.status === 'stopping'}
          placeholder="e.g. Team standup notes"
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>

      <div className="recorder">
        <button
          className={`mic ${recording ? 'on' : ''}`}
          onClick={recording ? () => void live.stop(title.trim() || undefined) : startRecording}
          disabled={engineMissing || live.status === 'connecting' || live.status === 'stopping'}
          aria-label={recording ? 'Stop recording' : 'Start recording'}
          style={{ ['--level' as string]: Math.min(1, live.level * 6) }}
        >
          {recording ? '■' : '🎙'}
        </button>
        <div className="recorder-status">
          <strong>{statusLabel}</strong>
          <span className="muted">
            {recording
              ? `${formatTime(elapsed)} / ${formatTime(maxSeconds)}`
              : 'Click the mic and speak in Hindi, Kannada, English or a mix'}
          </span>
        </div>
        {recording && (
          <button className="ghost" onClick={() => void live.cancel()}>
            Discard
          </button>
        )}
      </div>

      {engineMissing && (
        <div className="alert error">
          Live transcription is not configured on the server. Set the Gemini API key and restart the backend.
        </div>
      )}
      {live.error && <div className="alert error">{live.error}</div>}
      {notice && <div className="alert">{notice}</div>}

      <div className={`transcript ${recording ? 'active' : ''}`} aria-live="polite">
        {live.finalText || live.interim ? (
          <>
            <span>{live.finalText}</span> <span className="interim">{live.interim}</span>
          </>
        ) : (
          <span className="placeholder">Your words will appear here as you speak…</span>
        )}
      </div>

      {saved && (
        <div className="alert success">
          Saved as <strong>{saved.title}</strong> · {saved.wordCount} words
          {saved.languages ? ` · ${saved.languages}` : ''}
          <span className="spacer" />
          <SpeakButton text={saved.content} languages={saved.languages} />
          <button className="link" onClick={onOpenHistory}>
            View history →
          </button>
        </div>
      )}
    </section>
  );
}
