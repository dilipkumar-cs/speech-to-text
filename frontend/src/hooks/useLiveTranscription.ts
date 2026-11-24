import { useCallback, useEffect, useRef, useState } from 'react';
import { webSocketUrl } from '../api';
import type { Transcription } from '../types';
import { startPcmCapture, type PcmCapture } from './useMicrophonePcm';

export type LiveStatus = 'idle' | 'connecting' | 'recording' | 'stopping';

/** Longest we wait for the microphone to shut down before sending "stop" anyway. */
export const MIC_STOP_TIMEOUT_MS = 1500;
/** Longest we wait for the server to confirm the save after "stop". */
export const SAVE_TIMEOUT_MS = 15000;

/**
 * Streams microphone audio to the backend (/ws/transcribe), which relays it to the Gemini Live API,
 * and receives interim + final transcripts in real time. On stop, the backend saves the transcript.
 */
export function useLiveTranscription(onSaved: (t: Transcription | null) => void) {
  const [status, setStatus] = useState<LiveStatus>('idle');
  const [finalText, setFinalText] = useState('');
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [level, setLevel] = useState(0);
  const [engineReady, setEngineReady] = useState(false);

  const socket = useRef<WebSocket | null>(null);
  const capture = useRef<PcmCapture | null>(null);
  const saveTimer = useRef<ReturnType<typeof setTimeout>>();
  const statusRef = useRef<LiveStatus>('idle');
  const onSavedRef = useRef(onSaved);
  onSavedRef.current = onSaved;

  const update = (s: LiveStatus) => {
    statusRef.current = s;
    setStatus(s);
  };

  /** Releases the microphone. Never throws and never waits longer than MIC_STOP_TIMEOUT_MS. */
  const stopCapture = useCallback(async () => {
    const c = capture.current;
    capture.current = null;
    setLevel(0);
    if (!c) return;
    try {
      await Promise.race([c.stop(), new Promise((resolve) => setTimeout(resolve, MIC_STOP_TIMEOUT_MS))]);
    } catch (e) {
      console.warn('Microphone did not stop cleanly', e);
    }
  }, []);

  const start = useCallback(async () => {
    if (statusRef.current !== 'idle') return;
    setError(null);
    setFinalText('');
    setInterim('');
    setEngineReady(false);
    update('connecting');

    const ws = new WebSocket(webSocketUrl('/ws/transcribe'));
    ws.binaryType = 'arraybuffer';
    socket.current = ws;

    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data);
      switch (msg.type) {
        case 'ready':
          setEngineReady(true);
          break;
        case 'transcript':
          setFinalText(msg.final);
          setInterim(msg.interim);
          break;
        case 'saved':
          // The save is confirmed: we're done, even if the server's close frame is slow to arrive.
          clearTimeout(saveTimer.current);
          if (msg.transcription) {
            setFinalText(msg.transcription.content);
            setInterim('');
          }
          onSavedRef.current(msg.transcription ?? null);
          update('idle');
          ws.close();
          break;
        case 'error':
          setError(msg.message);
          break;
      }
    };

    ws.onclose = () => {
      clearTimeout(saveTimer.current);
      socket.current = null;
      void stopCapture();
      update('idle');
    };

    ws.onopen = async () => {
      try {
        capture.current = await startPcmCapture(
          (pcm) => {
            // the final flushed chunk arrives while 'stopping' and is sent before the stop command
            if (ws.readyState === WebSocket.OPEN) ws.send(pcm);
          },
          setLevel,
        );
        update('recording');
      } catch (e) {
        setError(
          e instanceof DOMException && e.name === 'NotAllowedError'
            ? 'Microphone permission was denied.'
            : `Could not access the microphone: ${(e as Error).message}`,
        );
        ws.close();
      }
    };

    ws.onerror = () => setError('Lost connection to the transcription server.');
  }, [stopCapture]);

  /** Ends the recording; the server finalises, saves and then closes the socket. */
  const stop = useCallback(
    async (title?: string) => {
      const ws = socket.current;
      if (!ws || statusRef.current !== 'recording') return;
      update('stopping');
      await stopCapture(); // flushes the last audio chunk first; bounded, never throws
      if (ws.readyState !== WebSocket.OPEN) {
        setError('Lost connection to the server before the recording could be saved.');
        update('idle');
        return;
      }
      ws.send(JSON.stringify({ type: 'stop', title }));
      // The server normally saves and closes within ~2 s; never leave the user stuck on "saving".
      saveTimer.current = setTimeout(() => {
        if (statusRef.current === 'stopping') {
          setError('The server did not confirm the save in time. Please check History or try again.');
          ws.close();
        }
      }, SAVE_TIMEOUT_MS);
    },
    [stopCapture],
  );

  const cancel = useCallback(async () => {
    const ws = socket.current;
    await stopCapture();
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'cancel' }));
    ws?.close();
    setFinalText('');
    setInterim('');
  }, [stopCapture]);

  useEffect(
    () => () => {
      clearTimeout(saveTimer.current);
      socket.current?.close();
      void capture.current?.stop();
    },
    [],
  );

  return { status, finalText, interim, error, level, engineReady, start, stop, cancel };
}
