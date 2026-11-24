import { beforeEach, describe, expect, it, vi } from 'vitest';
import { speak, stopSpeaking, ttsSupported } from './tts';

class FakeUtterance {
  lang = '';
  voice: SpeechSynthesisVoice | null = null;
  onend: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public text: string) {}
}

const voice = (lang: string) => ({ lang, name: lang }) as SpeechSynthesisVoice;

describe('tts', () => {
  let synth: { cancel: ReturnType<typeof vi.fn>; speak: ReturnType<typeof vi.fn>; getVoices: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    synth = {
      cancel: vi.fn(),
      speak: vi.fn(),
      getVoices: vi.fn(() => [voice('en-US'), voice('en-IN'), voice('hi-IN'), voice('kn-IN')]),
    };
    vi.stubGlobal('speechSynthesis', synth);
    vi.stubGlobal('SpeechSynthesisUtterance', FakeUtterance);
  });

  const spoken = () => synth.speak.mock.calls[0][0] as FakeUtterance;

  it('cancels any current speech before speaking', () => {
    speak('hello', null);
    expect(synth.cancel.mock.invocationCallOrder[0]).toBeLessThan(synth.speak.mock.invocationCallOrder[0]);
    expect(spoken().text).toBe('hello');
  });

  it('uses a Kannada voice for Kannada script', () => {
    speak('ನಾನು ಚೆನ್ನಾಗಿದ್ದೀನಿ', null);
    expect(spoken().lang).toBe('kn-IN');
    expect(spoken().voice?.lang).toBe('kn-IN');
  });

  it('uses a Hindi voice for Devanagari', () => {
    speak('आज मौसम अच्छा है', null);
    expect(spoken().voice?.lang).toBe('hi-IN');
  });

  it('uses the language tags for romanised text', () => {
    speak('naale meeting ge hogbeku', 'kn+en');
    expect(spoken().voice?.lang).toBe('kn-IN');

    synth.speak.mockClear();
    speak('kya haal hai', 'hi');
    expect(spoken().voice?.lang).toBe('hi-IN');
  });

  it('uses Indian English for plain English', () => {
    speak('Good morning team', 'en');
    expect(spoken().voice?.lang).toBe('en-IN');
  });

  it('falls back to the next preferred voice, then to just the language', () => {
    synth.getVoices.mockReturnValue([voice('en-IN')]);
    speak('ನಮಸ್ಕಾರ', null);
    expect(spoken().voice?.lang).toBe('en-IN');

    synth.speak.mockClear();
    synth.getVoices.mockReturnValue([]);
    speak('ನಮಸ್ಕಾರ', null);
    expect(spoken().voice).toBeNull();
    expect(spoken().lang).toBe('kn-IN');
  });

  it('calls onEnd when speech ends or fails', () => {
    const onEnd = vi.fn();
    speak('hi', null, onEnd);
    spoken().onend?.();
    spoken().onerror?.();
    expect(onEnd).toHaveBeenCalledTimes(2);
  });

  it('does not fail without an onEnd callback', () => {
    speak('hi', null);
    expect(() => spoken().onend?.()).not.toThrow();
  });

  it('stopSpeaking cancels speech', () => {
    stopSpeaking();
    expect(synth.cancel).toHaveBeenCalled();
  });

  it('reports support based on speechSynthesis', () => {
    expect(ttsSupported()).toBe(true);
  });
});
