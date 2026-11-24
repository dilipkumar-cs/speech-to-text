import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SpeakButton } from './SpeakButton';

const tts = vi.hoisted(() => ({
  speak: vi.fn(),
  stopSpeaking: vi.fn(),
  ttsSupported: vi.fn(() => true),
}));
vi.mock('../tts', () => tts);

describe('SpeakButton', () => {
  beforeEach(() => {
    tts.speak.mockReset();
    tts.stopSpeaking.mockReset();
    tts.ttsSupported.mockReturnValue(true);
  });

  it('renders nothing when the browser cannot speak', () => {
    tts.ttsSupported.mockReturnValue(false);
    const { container } = render(<SpeakButton text="hi" languages={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('speaks the text with its languages and switches to a stop button', async () => {
    const user = userEvent.setup();
    render(<SpeakButton text="ನಮಸ್ಕಾರ" languages="kn" />);

    await user.click(screen.getByTitle('Listen (text to speech)'));

    expect(tts.speak).toHaveBeenCalledWith('ನಮಸ್ಕಾರ', 'kn', expect.any(Function));
    expect(screen.getByTitle('Stop')).toHaveTextContent('⏹');
  });

  it('stops when clicked again', async () => {
    const user = userEvent.setup();
    render(<SpeakButton text="hi" languages={null} />);
    await user.click(screen.getByTitle('Listen (text to speech)'));
    await user.click(screen.getByTitle('Stop'));

    expect(tts.stopSpeaking).toHaveBeenCalledTimes(1);
    expect(screen.getByTitle('Listen (text to speech)')).toBeInTheDocument();
  });

  it('resets when speech finishes on its own', async () => {
    const user = userEvent.setup();
    render(<SpeakButton text="hi" languages={null} />);
    await user.click(screen.getByTitle('Listen (text to speech)'));

    const onEnd = tts.speak.mock.calls[0][2] as () => void;
    act(() => onEnd());

    expect(screen.getByTitle('Listen (text to speech)')).toBeInTheDocument();
  });

  it('stops its own speech when removed while playing', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<SpeakButton text="hi" languages={null} />);
    await user.click(screen.getByTitle('Listen (text to speech)'));

    unmount();
    expect(tts.stopSpeaking).toHaveBeenCalledTimes(1);
  });

  it('does not interrupt other speech when removed while idle', () => {
    const { unmount } = render(<SpeakButton text="hi" languages={null} />);
    unmount();
    expect(tts.stopSpeaking).not.toHaveBeenCalled();
  });
});
