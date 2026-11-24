/**
 * AudioWorklet that turns microphone audio into 16 kHz, 16-bit, mono, little-endian PCM
 * (the format the Gemini Live API expects) and posts it in ~100 ms chunks.
 *
 * If the AudioContext already runs at 16 kHz the browser has done the resampling; otherwise
 * we decimate here (with a simple averaging low-pass filter to limit aliasing).
 */
const TARGET_RATE = 16000;
const CHUNK_SAMPLES = 1600; // 100 ms

class PcmRecorderProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / TARGET_RATE;
    this.position = 0;
    this.chunk = new Int16Array(CHUNK_SAMPLES);
    this.offset = 0;
    this.sumSquares = 0;
    this.port.onmessage = (e) => {
      if (e.data === 'flush') this.flush();
    };
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;

    while (this.position < channel.length) {
      const start = Math.floor(this.position);
      const end = Math.min(channel.length, Math.max(start + 1, Math.floor(this.position + this.ratio)));
      let sample = 0;
      for (let i = start; i < end; i++) sample += channel[i];
      sample /= end - start;

      sample = Math.max(-1, Math.min(1, sample));
      this.sumSquares += sample * sample;
      this.chunk[this.offset++] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
      if (this.offset === CHUNK_SAMPLES) this.flush();

      this.position += this.ratio;
    }
    this.position -= channel.length;
    return true;
  }

  flush() {
    if (this.offset === 0) return;
    const pcm = this.chunk.slice(0, this.offset);
    const level = Math.sqrt(this.sumSquares / this.offset);
    this.port.postMessage({ pcm: pcm.buffer, level }, [pcm.buffer]);
    this.offset = 0;
    this.sumSquares = 0;
  }
}

registerProcessor('pcm-recorder', PcmRecorderProcessor);
