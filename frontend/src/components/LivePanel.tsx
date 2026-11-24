import { useEffect, useState } from 'react';
import { api } from '../api';
import { browserSpeechSupported, useBrowserSpeech } from '../hooks/useBrowserSpeech';
import { useLiveTranscription } from '../hooks/useLiveTranscription';
import type { AppInfo, Transcription } from '../types';
import { SpeakButton } from './SpeakButton';

type Engine = 'gemini' | 'browser';

interface Props {
  info: AppInfo | null;
  onSaved: () => void;
  onOpenHistory: () => void;
}

const formatTime = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;

export function LivePanel({ info, onSaved, onOpenHistory }: Props) {
  const [engine, setEngine] = useState<Engine>('gemini');
  const [browserLang, setBrowserLang] = useState('hi-IN');
  const [title, setTitle] = useState('');
  const [saved, setSaved] = useState<Transcription | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [saveError, setSaveError] = useState<string | null>(null);

  const live = useLiveTranscription((t) => {
    setSaved(t);
    setNotice(t ? null : 'No speech was detected, so nothing was saved.');
    if (t) onSaved();
  });
  const browser = useBrowserSpeech();

  useEffect(() => {
    if (info && !info.geminiEnabled) setEngine('browser');
  }, [info]);

  const recording = engine === 'gemini' ? live.status !== 'idle' : browser.recording;
  const finalText = engine === 'gemini' ? live.finalText : browser.finalText;
  const interim = engine === 'gemini' ? live.interim : browser.interim;
  const error = (engine === 'gemini' ? live.error : browser.error) ?? saveError;

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
    setSaveError(null);
    if (engine === 'gemini') void live.start();
    else browser.start(browserLang);
  };

  const stopRecording = async () => {
    if (engine === 'gemini') {
      await live.stop(title.trim() || undefined);
      return;
    }
    const { text, durationSeconds } = browser.stop();
    if (!text.trim()) {
      setNotice('No speech was detected, so nothing was saved.');
      return;
    }
    try {
      const t = await api.create({ title: title.trim() || undefined, content: text, source: 'BROWSER', durationSeconds });
      setSaved(t);
      onSaved();
    } catch (e) {
      setSaveError(`Could not save: ${(e as Error).message}`);
    }
  };

  const statusLabel =
    engine === 'gemini'
      ? { idle: 'Ready', connecting: 'Connecting…', recording: live.engineReady ? 'Listening' : 'Starting engine…', stopping: 'Finalising & saving…' }[live.status]
      : browser.recording ? 'Listening' : 'Ready';

  const level = engine === 'gemini' ? live.level : browser.recording ? 0.05 : 0;
  const maxSeconds = info?.maxSessionSeconds ?? 570;

  return (
    <section className="card live">
      <div className="live-controls">
        <label className="field">
          <span>Engine</span>
          <select value={engine} disabled={recording} onChange={(e) => setEngine(e.target.value as Engine)}>
            <option value="gemini" disabled={info !== null && !info.geminiEnabled}>
              Gemini Live (server, auto language detect){info && !info.geminiEnabled ? ' – not configured' : ''}
            </option>
            <option value="browser" disabled={!browserSpeechSupported()}>
              Browser Web Speech API{browserSpeechSupported() ? '' : ' – unsupported in this browser'}
            </option>
          </select>
        </label>
        {engine === 'browser' && (
          <label className="field narrow">
            <span>Language</span>
            <select value={browserLang} disabled={recording} onChange={(e) => setBrowserLang(e.target.value)}>
              <option value="hi-IN">Hindi + English (hi-IN)</option>
              <option value="kn-IN">Kannada + English (kn-IN)</option>
              <option value="en-IN">English – India (en-IN)</option>
            </select>
          </label>
        )}
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
      </div>

      <div className="recorder">
        <button
          className={`mic ${recording ? 'on' : ''}`}
          onClick={recording ? stopRecording : startRecording}
          disabled={live.status === 'connecting' || live.status === 'stopping'}
          aria-label={recording ? 'Stop recording' : 'Start recording'}
          style={{ ['--level' as string]: Math.min(1, level * 6) }}
        >
          {recording ? '■' : '🎙'}
        </button>
        <div className="recorder-status">
          <strong>{statusLabel}</strong>
          <span className="muted">
            {recording ? `${formatTime(elapsed)} / ${formatTime(maxSeconds)}` : 'Click the mic and speak in Hindi, Kannada, English or a mix'}
          </span>
        </div>
        {recording && engine === 'gemini' && (
          <button className="ghost" onClick={() => void live.cancel()}>
            Discard
          </button>
        )}
      </div>

      {error && <div className="alert error">{error}</div>}
      {notice && <div className="alert">{notice}</div>}

      <div className={`transcript ${recording ? 'active' : ''}`} aria-live="polite">
        {finalText || interim ? (
          <>
            <span>{finalText}</span> <span className="interim">{interim}</span>
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
