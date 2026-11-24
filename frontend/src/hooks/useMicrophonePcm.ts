/**
 * Captures the microphone as 16 kHz mono 16-bit PCM chunks via an AudioWorklet.
 */
export interface PcmCapture {
  stop: () => Promise<void>;
}

export async function startPcmCapture(
  onChunk: (pcm: ArrayBuffer) => void,
  onLevel: (level: number) => void,
): Promise<PcmCapture> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  });

  let context: AudioContext;
  let source: MediaStreamAudioSourceNode;
  try {
    // Let the browser resample to 16 kHz with its own high-quality filter when it can.
    context = new AudioContext({ sampleRate: 16000 });
    source = context.createMediaStreamSource(stream);
  } catch {
    // Firefox refuses to connect a mic stream to a context with a different rate; resample in the worklet instead.
    context = new AudioContext();
    source = context.createMediaStreamSource(stream);
  }

  await context.audioWorklet.addModule('/pcm-recorder-worklet.js');
  const worklet = new AudioWorkletNode(context, 'pcm-recorder');
  worklet.port.onmessage = (e: MessageEvent<{ pcm: ArrayBuffer; level: number }>) => {
    onChunk(e.data.pcm);
    onLevel(e.data.level);
  };
  source.connect(worklet);

  return {
    stop: async () => {
      // Each step is independent so one failure can't keep the microphone on or block saving.
      const attempt = (step: () => void) => {
        try {
          step();
        } catch (e) {
          console.warn('Audio cleanup step failed', e);
        }
      };
      attempt(() => worklet.port.postMessage('flush'));
      // give the worklet one render quantum to deliver the final partial chunk
      await new Promise((r) => setTimeout(r, 50));
      attempt(() => source.disconnect());
      attempt(() => worklet.disconnect());
      attempt(() => stream.getTracks().forEach((t) => t.stop()));
      // Closing the context only frees audio resources; don't let a slow or failing close block anything.
      context.close().catch((e) => console.warn('AudioContext did not close cleanly', e));
    },
  };
}
