import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const api = vi.hoisted(() => ({ info: vi.fn() }));
vi.mock('./api', () => ({ api }));

// The panels have their own tests; here they are stubs that expose the props App gives them.
vi.mock('./components/LivePanel', () => ({
  LivePanel: (props: { onSaved: () => void; onOpenHistory: () => void; info: unknown }) => (
    <div data-testid="live">
      <span>engine-info:{props.info ? 'loaded' : 'loading'}</span>
      <button onClick={props.onSaved}>fake-save</button>
      <button onClick={props.onOpenHistory}>fake-open-history</button>
    </div>
  ),
}));
vi.mock('./components/HistoryDashboard', () => ({
  HistoryDashboard: ({ refreshKey }: { refreshKey: number }) => <div data-testid="history">refresh:{refreshKey}</div>,
}));

describe('App', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/');
    api.info.mockReset().mockResolvedValue({
      geminiEnabled: true,
      geminiModel: 'models/gemini-3.5-transcribe-live',
      maxSessionSeconds: 570,
    });
  });

  it('shows the header and the Live tab by default', async () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Speech to Text' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Live' })).toHaveClass('active');
    expect(screen.getByTestId('live')).toBeVisible();
    expect(screen.queryByTestId('history')).not.toBeInTheDocument();
    expect(await screen.findByText('engine-info:loaded')).toBeInTheDocument();
  });

  it('shows the engine model in the footer', async () => {
    render(<App />);
    expect(await screen.findByText(/Engine: gemini-3.5-transcribe-live via Gemini Live API/)).toBeInTheDocument();
  });

  it('says the engine is not configured when the key is missing or info fails', async () => {
    api.info.mockResolvedValue({ geminiEnabled: false, geminiModel: '', maxSessionSeconds: 570 });
    const { unmount } = render(<App />);
    expect(await screen.findByText(/Gemini engine not configured/)).toBeInTheDocument();
    unmount();

    api.info.mockRejectedValue(new Error('offline'));
    render(<App />);
    expect(await screen.findByText(/Gemini engine not configured/)).toBeInTheDocument();
    expect(screen.getByText('engine-info:loaded')).toBeInTheDocument();
  });

  it('switches to History and updates the URL, keeping the live panel mounted', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('button', { name: 'History' }));

    expect(window.location.pathname).toBe('/history');
    expect(screen.getByTestId('history')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'History' })).toHaveClass('active');
    expect(screen.getByTestId('live')).not.toBeVisible();
  });

  it('does not add a history entry when clicking the current tab', async () => {
    const user = userEvent.setup();
    const push = vi.spyOn(window.history, 'pushState');
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'Live' }));
    expect(push).not.toHaveBeenCalled();
  });

  it('opens History directly from the /history URL', () => {
    window.history.replaceState(null, '', '/history');
    render(<App />);
    expect(screen.getByTestId('history')).toBeInTheDocument();
  });

  it('follows the browser back button', async () => {
    const user = userEvent.setup();
    render(<App />);
    await user.click(screen.getByRole('button', { name: 'History' }));

    act(() => {
      window.history.replaceState(null, '', '/live');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    expect(screen.queryByTestId('history')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Live' })).toHaveClass('active');
  });

  it('refreshes the history after a recording is saved and opens it on request', async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole('button', { name: 'fake-save' }));
    await user.click(screen.getByRole('button', { name: 'fake-open-history' }));

    expect(screen.getByTestId('history')).toHaveTextContent('refresh:1');
    expect(window.location.pathname).toBe('/history');
  });
});
