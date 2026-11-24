import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MIC_STOP_TIMEOUT_MS, SAVE_TIMEOUT_MS } from '../hooks/useLiveTranscription';
import type { AppInfo } from '../types';
import { LivePanel } from './LivePanel';

const capture = vi.hoisted(() => ({
  onChunk: null as ((pcm: ArrayBuffer) => void) | null,
  onLevel: null as ((level: number) => void) | null,
  stop: vi.fn(async () => undefined),
  start: vi.fn(),
}));

vi.mock('../hooks/useMicrophonePcm', () => ({
  startPcmCapture: capture.start,
}));

class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.CONNECTING;
  binaryType = 'blob';
  sent: unknown[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
  }
  send(data: unknown) {
    this.sent.push(data);
  }
  close() {
    if (this.readyState === FakeWebSocket.CLOSED) return;
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
  // test helpers
  async open() {
    this.readyState = FakeWebSocket.OPEN;
    await this.onopen?.();
  }
  receive(msg: object) {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
  sentJson() {
    return this.sent.filter((d) => typeof d === 'string').map((d) => JSON.parse(d as string));
  }
}

const INFO: AppInfo = { geminiEnabled: true, geminiModel: 'models/gemini', maxSessionSeconds: 570 };

const SAVED = {
  id: 1,
  title: 'Standup',
  content: 'namaste team',
  languages: 'hi+en',
  source: 'GEMINI_LIVE',
  durationSeconds: 4,
  wordCount: 2,
  createdAt: '2026-11-24T10:00:00Z',
  updatedAt: '2026-11-24T10:00:00Z',
};

function setup(info: AppInfo | null = INFO) {
  const onSaved = vi.fn();
  const onOpenHistory = vi.fn();
  const user = userEvent.setup();
  const { unmount } = render(<LivePanel info={info} onSaved={onSaved} onOpenHistory={onOpenHistory} />);
  return { user, onSaved, onOpenHistory, unmount };
}

/** Clicks the mic and opens the socket; returns the socket. */
async function startRecording(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Start recording' }));
  const ws = FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  await act(async () => {
    await ws.open();
  });
  return ws;
}

describe('LivePanel', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    capture.stop.mockClear();
    capture.start.mockReset().mockImplementation(async (onChunk, onLevel) => {
      capture.onChunk = onChunk;
      capture.onLevel = onLevel;
      return { stop: capture.stop };
    });
  });

  it('connects to the transcription socket and only starts the microphone once it is open', async () => {
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: 'Start recording' }));

    const ws = FakeWebSocket.instances[0];
    expect(ws.url).toMatch(/\/ws\/transcribe$/);
    expect(ws.binaryType).toBe('arraybuffer');
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
    expect(capture.start).not.toHaveBeenCalled();

    await act(async () => {
      await ws.open();
    });
    expect(capture.start).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Starting engine…')).toBeInTheDocument();

    act(() => ws.receive({ type: 'ready' }));
    expect(screen.getByText('Listening')).toBeInTheDocument();
    expect(screen.getByText(/0:0\d \/ 9:30/)).toBeInTheDocument();
  });

  it('streams microphone audio as binary frames', async () => {
    const { user } = setup();
    const ws = await startRecording(user);

    const pcm = new ArrayBuffer(3200);
    act(() => capture.onChunk?.(pcm));
    act(() => capture.onLevel?.(0.2));

    expect(ws.sent).toContain(pcm);
  });

  it('renders final and interim text as it arrives', async () => {
    const { user } = setup();
    const ws = await startRecording(user);

    act(() => ws.receive({ type: 'transcript', final: 'Namaste team,', interim: 'aaj ka plan' }));

    expect(screen.getByText('Namaste team,')).toBeInTheDocument();
    expect(screen.getByText('aaj ka plan')).toHaveClass('interim');
  });

  it('stops the microphone, then sends stop with the title, and shows the saved result', async () => {
    const { user, onSaved, onOpenHistory } = setup();
    await user.type(screen.getByPlaceholderText(/Team standup/), '  Standup  ');
    const ws = await startRecording(user);

    await user.click(screen.getByRole('button', { name: 'Stop recording' }));

    expect(capture.stop).toHaveBeenCalled();
    expect(ws.sentJson()).toContainEqual({ type: 'stop', title: 'Standup' });
    expect(screen.getByText('Finalising & saving…')).toBeInTheDocument();

    act(() => ws.receive({ type: 'saved', transcription: SAVED }));
    expect(screen.getByText('Standup', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText(/2 words/)).toBeInTheDocument();
    expect(screen.getByText('namaste team')).toBeInTheDocument();
    expect(onSaved).toHaveBeenCalledTimes(1);

    act(() => ws.close());
    expect(screen.getByText('Ready')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /View history/ }));
    expect(onOpenHistory).toHaveBeenCalled();
  });

  it('still sends stop when the microphone fails to shut down', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    capture.stop.mockRejectedValueOnce(new DOMException('already closed', 'InvalidStateError'));
    const { user } = setup();
    const ws = await startRecording(user);

    await user.click(screen.getByRole('button', { name: 'Stop recording' }));

    expect(ws.sentJson()).toContainEqual({ type: 'stop' });
    expect(screen.getByText('Finalising & saving…')).toBeInTheDocument();
    warn.mockRestore();
  });

  it('sends stop anyway when the microphone never finishes shutting down', async () => {
    capture.stop.mockImplementationOnce(() => new Promise(() => undefined));
    const { user } = setup();
    const ws = await startRecording(user);

    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Stop recording' }));
      expect(ws.sentJson()).not.toContainEqual({ type: 'stop' });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(MIC_STOP_TIMEOUT_MS);
      });
      expect(ws.sentJson()).toContainEqual({ type: 'stop' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops waiting and tells the user when the server never confirms the save', async () => {
    const { user } = setup();
    const ws = await startRecording(user);

    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Stop recording' }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(ws.sentJson()).toContainEqual({ type: 'stop' });

      await act(async () => {
        await vi.advanceTimersByTimeAsync(SAVE_TIMEOUT_MS);
      });
      expect(screen.getByText(/did not confirm the save in time/)).toBeInTheDocument();
      expect(ws.readyState).toBe(FakeWebSocket.CLOSED);
      expect(screen.getByText('Ready')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not report a timeout when the save is confirmed in time', async () => {
    const { user } = setup();
    const ws = await startRecording(user);

    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Stop recording' }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      act(() => ws.receive({ type: 'saved', transcription: SAVED }));
      act(() => ws.close());
      await act(async () => {
        await vi.advanceTimersByTimeAsync(SAVE_TIMEOUT_MS);
      });
      expect(screen.queryByText(/did not confirm/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports a lost connection instead of hanging when the socket dropped before stop', async () => {
    const { user } = setup();
    const ws = await startRecording(user);
    ws.readyState = FakeWebSocket.CLOSED; // dropped without a close event yet

    await user.click(screen.getByRole('button', { name: 'Stop recording' }));

    expect(ws.sentJson()).not.toContainEqual({ type: 'stop' });
    expect(screen.getByText(/Lost connection to the server before the recording could be saved/)).toBeInTheDocument();
    expect(screen.getByText('Ready')).toBeInTheDocument();
  });

  it('sends no title when the title is blank', async () => {
    const { user } = setup();
    const ws = await startRecording(user);
    await user.click(screen.getByRole('button', { name: 'Stop recording' }));
    expect(ws.sentJson()).toContainEqual({ type: 'stop' });
  });

  it('tells the user when nothing was saved', async () => {
    const { user, onSaved } = setup();
    const ws = await startRecording(user);
    await user.click(screen.getByRole('button', { name: 'Stop recording' }));

    act(() => ws.receive({ type: 'saved', transcription: null }));

    expect(screen.getByText('No speech was detected, so nothing was saved.')).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('shows server errors', async () => {
    const { user } = setup();
    const ws = await startRecording(user);
    act(() => ws.receive({ type: 'error', message: 'Gemini error: quota exceeded' }));
    expect(screen.getByText('Gemini error: quota exceeded')).toBeInTheDocument();
  });

  it('shows a connection error when the socket fails', async () => {
    const { user } = setup();
    const ws = await startRecording(user);
    act(() => ws.onerror?.());
    expect(screen.getByText('Lost connection to the transcription server.')).toBeInTheDocument();
  });

  it('discard cancels the recording and clears the transcript', async () => {
    const { user } = setup();
    const ws = await startRecording(user);
    act(() => ws.receive({ type: 'transcript', final: 'throw this away', interim: '' }));

    await user.click(screen.getByRole('button', { name: 'Discard' }));

    expect(ws.sentJson()).toContainEqual({ type: 'cancel' });
    expect(ws.readyState).toBe(FakeWebSocket.CLOSED);
    expect(screen.queryByText('throw this away')).not.toBeInTheDocument();
    expect(screen.getByText(/Your words will appear here/)).toBeInTheDocument();
  });

  it('explains a denied microphone permission and closes the socket', async () => {
    capture.start.mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    const { user } = setup();
    const ws = await startRecording(user);

    expect(screen.getByText('Microphone permission was denied.')).toBeInTheDocument();
    expect(ws.readyState).toBe(FakeWebSocket.CLOSED);
    expect(screen.getByText('Ready')).toBeInTheDocument();
  });

  it('reports other microphone errors', async () => {
    capture.start.mockRejectedValue(new Error('no device'));
    const { user } = setup();
    await startRecording(user);
    expect(screen.getByText('Could not access the microphone: no device')).toBeInTheDocument();
  });

  it('disables the mic when the server has no Gemini key', () => {
    setup({ ...INFO, geminiEnabled: false });
    expect(screen.getByRole('button', { name: 'Start recording' })).toBeDisabled();
    expect(screen.getByText(/not configured on the server/)).toBeInTheDocument();
  });

  it('closes the socket and releases the microphone when unmounted mid-recording', async () => {
    const { user, unmount } = setup();
    const ws = await startRecording(user);

    unmount();

    expect(ws.readyState).toBe(FakeWebSocket.CLOSED);
    expect(capture.stop).toHaveBeenCalled();
  });
});
