import { useCallback, useEffect, useRef, useState } from 'react';
import { webSocketUrl } from '../api';
import type { Transcription } from '../types';
import { startPcmCapture, type PcmCapture } from './useMicrophonePcm';

export type LiveStatus = 'idle' | 'connecting' | 'recording' | 'stopping';

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
  const statusRef = useRef<LiveStatus>('idle');
  const onSavedRef = useRef(onSaved);
  onSavedRef.current = onSaved;

  const update = (s: LiveStatus) => {
    statusRef.current = s;
    setStatus(s);
  };

  const stopCapture = useCallback(async () => {
    const c = capture.current;
    capture.current = null;
    setLevel(0);
    if (c) await c.stop();
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
          if (msg.transcription) {
            setFinalText(msg.transcription.content);
            setInterim('');
          }
          onSavedRef.current(msg.transcription ?? null);
          break;
        case 'error':
          setError(msg.message);
          break;
      }
    };

    ws.onclose = () => {
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
      await stopCapture();
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'stop', title }));
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
      socket.current?.close();
      void capture.current?.stop();
    },
    [],
  );

  return { status, finalText, interim, error, level, engineReady, start, stop, cancel };
}
