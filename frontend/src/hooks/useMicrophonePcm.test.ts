import { beforeEach, describe, expect, it, vi } from 'vitest';
import { startPcmCapture } from './useMicrophonePcm';

const contexts: FakeAudioContext[] = [];
const nodes: FakeWorkletNode[] = [];
let failSourceAt16k = false;

class FakeSource {
  connect = vi.fn();
  disconnect = vi.fn();
}

class FakeAudioContext {
  source = new FakeSource();
  audioWorklet = { addModule: vi.fn(async () => undefined) };
  close = vi.fn(async () => undefined);
  constructor(public options?: AudioContextOptions) {
    contexts.push(this);
  }
  createMediaStreamSource = vi.fn(() => {
    if (failSourceAt16k && this.options?.sampleRate === 16000) {
      throw new DOMException('different sample-rate', 'NotSupportedError');
    }
    return this.source;
  });
}

class FakeWorkletNode {
  port = { onmessage: null as ((e: MessageEvent) => void) | null, postMessage: vi.fn() };
  disconnect = vi.fn();
  constructor(
    public context: FakeAudioContext,
    public name: string,
  ) {
    nodes.push(this);
  }
}

describe('startPcmCapture', () => {
  const track = { stop: vi.fn() };
  const getUserMedia = vi.fn();

  beforeEach(() => {
    contexts.length = 0;
    nodes.length = 0;
    failSourceAt16k = false;
    track.stop.mockClear();
    getUserMedia.mockReset().mockResolvedValue({ getTracks: () => [track] });
    vi.stubGlobal('AudioContext', FakeAudioContext);
    vi.stubGlobal('AudioWorkletNode', FakeWorkletNode);
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
  });

  it('asks for a mono, cleaned-up microphone stream', async () => {
    await startPcmCapture(vi.fn(), vi.fn());
    expect(getUserMedia).toHaveBeenCalledWith({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
  });

  it('records through a 16 kHz context and the pcm-recorder worklet', async () => {
    await startPcmCapture(vi.fn(), vi.fn());

    expect(contexts).toHaveLength(1);
    expect(contexts[0].options).toEqual({ sampleRate: 16000 });
    expect(contexts[0].audioWorklet.addModule).toHaveBeenCalledWith('/pcm-recorder-worklet.js');
    expect(nodes[0].name).toBe('pcm-recorder');
    expect(contexts[0].source.connect).toHaveBeenCalledWith(nodes[0]);
  });

  it('falls back to the default sample rate when the browser cannot use 16 kHz', async () => {
    failSourceAt16k = true;
    await startPcmCapture(vi.fn(), vi.fn());

    expect(contexts).toHaveLength(2);
    expect(contexts[1].options).toBeUndefined();
    expect(nodes[0].context).toBe(contexts[1]);
  });

  it('forwards PCM chunks and levels from the worklet', async () => {
    const onChunk = vi.fn();
    const onLevel = vi.fn();
    await startPcmCapture(onChunk, onLevel);

    const pcm = new ArrayBuffer(3200);
    nodes[0].port.onmessage?.({ data: { pcm, level: 0.3 } } as MessageEvent);

    expect(onChunk).toHaveBeenCalledWith(pcm);
    expect(onLevel).toHaveBeenCalledWith(0.3);
  });

  it('stop flushes the worklet and releases the microphone', async () => {
    const capture = await startPcmCapture(vi.fn(), vi.fn());
    await capture.stop();

    expect(nodes[0].port.postMessage).toHaveBeenCalledWith('flush');
    expect(contexts[0].source.disconnect).toHaveBeenCalled();
    expect(nodes[0].disconnect).toHaveBeenCalled();
    expect(track.stop).toHaveBeenCalled();
    expect(contexts[0].close).toHaveBeenCalled();
  });

  it('stop still releases the microphone when a cleanup step throws', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const capture = await startPcmCapture(vi.fn(), vi.fn());
    contexts[0].source.disconnect.mockImplementation(() => {
      throw new DOMException('not connected', 'InvalidAccessError');
    });

    await expect(capture.stop()).resolves.toBeUndefined();

    expect(nodes[0].disconnect).toHaveBeenCalled();
    expect(track.stop).toHaveBeenCalled();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('stop does not wait for an AudioContext that never closes or fails to close', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const hanging = await startPcmCapture(vi.fn(), vi.fn());
    contexts[0].close.mockImplementation(() => new Promise(() => undefined));
    await expect(hanging.stop()).resolves.toBeUndefined();

    const failing = await startPcmCapture(vi.fn(), vi.fn());
    contexts[1].close.mockImplementation(async () => {
      throw new DOMException('already closed', 'InvalidStateError');
    });
    await expect(failing.stop()).resolves.toBeUndefined();
    await vi.waitFor(() => expect(warn).toHaveBeenCalled());
    warn.mockRestore();
  });

  it('propagates a denied microphone permission', async () => {
    getUserMedia.mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    await expect(startPcmCapture(vi.fn(), vi.fn())).rejects.toThrow('denied');
  });
});
