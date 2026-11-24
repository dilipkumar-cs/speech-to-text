import { describe, expect, it, vi } from 'vitest';
import workletSource from '../../public/pcm-recorder-worklet.js?raw';

interface Port {
  postMessage: ReturnType<typeof vi.fn>;
  onmessage: ((e: { data: unknown }) => void) | null;
}
interface Processor {
  port: Port;
  process(inputs: Float32Array[][]): boolean;
}
type ProcessorClass = new () => Processor;

/** Evaluates the worklet file with the AudioWorkletGlobalScope globals it expects. */
function loadProcessor(sampleRate: number): Processor {
  class AudioWorkletProcessor {
    port: Port = { postMessage: vi.fn(), onmessage: null };
  }
  let registered: { name: string; cls: ProcessorClass } | undefined;
  const registerProcessor = (name: string, cls: ProcessorClass) => {
    registered = { name, cls };
  };
  new Function('sampleRate', 'AudioWorkletProcessor', 'registerProcessor', workletSource)(
    sampleRate,
    AudioWorkletProcessor,
    registerProcessor,
  );
  expect(registered?.name).toBe('pcm-recorder');
  return new registered!.cls();
}

const posted = (p: Processor) =>
  p.port.postMessage.mock.calls.map(([msg]) => ({
    pcm: new Int16Array(msg.pcm as ArrayBuffer),
    level: msg.level as number,
  }));

describe('pcm-recorder worklet', () => {
  it('emits one 100 ms chunk of 16-bit PCM at 16 kHz with correct conversion and clipping', () => {
    const p = loadProcessor(16000);
    const input = new Float32Array(1600).fill(0.5);
    input[0] = 1;
    input[1] = -1;
    input[2] = 2; // clipped to 1
    input[3] = -3; // clipped to -1
    input[4] = 0;

    expect(p.process([[input]])).toBe(true);

    const chunks = posted(p);
    expect(chunks).toHaveLength(1);
    const pcm = chunks[0].pcm;
    expect(pcm).toHaveLength(1600);
    expect([pcm[0], pcm[1], pcm[2], pcm[3], pcm[4]]).toEqual([32767, -32768, 32767, -32768, 0]);
    expect(pcm[5]).toBe(Math.trunc(0.5 * 0x7fff));
  });

  it('does not emit anything until a full chunk is collected', () => {
    const p = loadProcessor(16000);
    p.process([[new Float32Array(128)]]);
    expect(p.port.postMessage).not.toHaveBeenCalled();
  });

  it('decimates 48 kHz input to 16 kHz', () => {
    const p = loadProcessor(48000);
    // 4800 samples at 48 kHz = 100 ms -> 1600 samples at 16 kHz, delivered in 128-frame render quanta
    const quantum = new Float32Array(128).fill(0.25);
    for (let i = 0; i < 4800 / 128; i++) p.process([[quantum]]);
    p.process([[quantum.subarray(0, 4800 % 128)]]);

    const chunks = posted(p);
    expect(chunks).toHaveLength(1);
    expect(chunks[0].pcm).toHaveLength(1600);
    expect(chunks[0].pcm.every((s) => s === Math.trunc(0.25 * 0x7fff))).toBe(true);
  });

  it('flushes a partial chunk on request and reports the RMS level', () => {
    const p = loadProcessor(16000);
    const input = new Float32Array(400);
    input.fill(0.6, 0, 200);
    input.fill(-0.6, 200);
    p.process([[input]]);

    p.port.onmessage?.({ data: 'flush' });

    const [chunk] = posted(p);
    expect(chunk.pcm).toHaveLength(400);
    expect(chunk.level).toBeCloseTo(0.6, 5);
  });

  it('ignores flush when nothing is buffered and unknown messages', () => {
    const p = loadProcessor(16000);
    p.port.onmessage?.({ data: 'flush' });
    p.port.onmessage?.({ data: 'something-else' });
    expect(p.port.postMessage).not.toHaveBeenCalled();
  });

  it('keeps running when there is no input', () => {
    const p = loadProcessor(16000);
    expect(p.process([])).toBe(true);
    expect(p.process([[]])).toBe(true);
    expect(p.port.postMessage).not.toHaveBeenCalled();
  });
});
