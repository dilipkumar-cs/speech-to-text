import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Transcription } from '../types';
import { TranscriptionModal } from './TranscriptionModal';

const EXISTING: Transcription = {
  id: 4,
  title: 'Meeting',
  content: 'kal meeting hai',
  languages: 'hi',
  source: 'GEMINI_LIVE',
  durationSeconds: 3,
  wordCount: 3,
  createdAt: '2026-11-24T10:00:00Z',
  updatedAt: '2026-11-24T10:00:00Z',
};

function setup(transcription: Transcription | null, onSubmit = vi.fn(async () => undefined)) {
  const onClose = vi.fn();
  const user = userEvent.setup();
  render(<TranscriptionModal transcription={transcription} onSubmit={onSubmit} onClose={onClose} />);
  return { user, onSubmit, onClose };
}

describe('TranscriptionModal', () => {
  it('opens as a modal for a new transcription', () => {
    setup(null);
    expect(screen.getByRole('heading', { name: 'New transcription' })).toBeInTheDocument();
    expect(screen.getByRole('dialog', { hidden: true })).toHaveAttribute('open');
    expect(screen.getByLabelText('Title')).toHaveValue('');
  });

  it('is prefilled when editing', () => {
    setup(EXISTING);
    expect(screen.getByRole('heading', { name: 'Edit transcription' })).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('Meeting');
    expect(screen.getByLabelText('Text')).toHaveValue('kal meeting hai');
  });

  it('refuses empty text', async () => {
    const { user, onSubmit } = setup(null);
    await user.type(screen.getByLabelText('Text'), '   ');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(screen.getByText('Text cannot be empty.')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the trimmed title and the text, then closes', async () => {
    const { user, onSubmit, onClose } = setup(null);
    await user.type(screen.getByLabelText('Title'), '  Notes  ');
    await user.type(screen.getByLabelText('Text'), 'hello world');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(onSubmit).toHaveBeenCalledWith({ title: 'Notes', content: 'hello world' });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('sends no title when it is blank', async () => {
    const { user, onSubmit } = setup(null);
    await user.type(screen.getByLabelText('Title'), '   ');
    await user.type(screen.getByLabelText('Text'), 'text');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(onSubmit).toHaveBeenCalledWith({ title: undefined, content: 'text' });
  });

  it('shows a save error and lets the user retry', async () => {
    const onSubmit = vi.fn().mockRejectedValueOnce(new Error('Validation failed')).mockResolvedValueOnce(undefined);
    const { user, onClose } = setup(EXISTING, onSubmit);

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Validation failed')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('shows Saving… while the request is in flight', async () => {
    let resolve!: () => void;
    const onSubmit = vi.fn(() => new Promise<void>((r) => (resolve = r)));
    const { user } = setup(EXISTING, onSubmit);

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    resolve();
  });

  it('cancel closes without saving', async () => {
    const { user, onSubmit, onClose } = setup(EXISTING);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
