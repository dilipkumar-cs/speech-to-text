import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Page, Transcription } from '../types';
import { HistoryDashboard } from './HistoryDashboard';

const api = vi.hoisted(() => ({
  list: vi.fn(),
  stats: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  removeMany: vi.fn(),
}));
vi.mock('../api', () => ({ api }));

const item = (id: number, overrides: Partial<Transcription> = {}): Transcription => ({
  id,
  title: `Item ${id}`,
  content: `content ${id}`,
  languages: 'en',
  source: 'GEMINI_LIVE',
  durationSeconds: 30,
  wordCount: 2,
  createdAt: '2026-11-24T10:00:00Z',
  updatedAt: '2026-11-24T10:00:00Z',
  ...overrides,
});

const page = (items: Transcription[], pageNo = 0, totalPages = 1): Page<Transcription> => ({
  items,
  page: pageNo,
  size: 10,
  totalItems: items.length,
  totalPages,
});

const ITEMS = [
  item(1, { title: 'Kannada note', content: 'ನಾಳೆ office ಗೆ ಹೋಗಬೇಕು', languages: 'kn+en', wordCount: 4, durationSeconds: 75 }),
  item(2, { title: 'Manual note', source: 'MANUAL', languages: null, durationSeconds: null, updatedAt: '2026-11-25T10:00:00Z' }),
];

function setup() {
  const user = userEvent.setup();
  render(<HistoryDashboard refreshKey={0} />);
  return { user };
}

const row = (title: string) => screen.getByText(title).closest('li') as HTMLElement;
const selectAll = () => screen.getByLabelText('Select all on this page') as HTMLInputElement;

describe('HistoryDashboard', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.list.mockResolvedValue(page(ITEMS));
    api.stats.mockResolvedValue({ totalTranscriptions: 2, totalDurationSeconds: 150 });
    api.remove.mockResolvedValue(undefined);
    api.removeMany.mockResolvedValue({ deleted: 2 });
    api.create.mockResolvedValue(item(3));
    api.update.mockResolvedValue(item(1));
  });

  it('lists transcriptions with their details and the stats', async () => {
    setup();

    expect(await screen.findByText('Kannada note')).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledWith('', 0);
    expect(within(row('Kannada note')).getByText('Kannada + English')).toBeInTheDocument();
    expect(within(row('Kannada note')).getByText('Gemini Live')).toBeInTheDocument();
    expect(within(row('Kannada note')).getByText('4 words')).toBeInTheDocument();
    expect(within(row('Kannada note')).getByText('1m 15s')).toBeInTheDocument();
    expect(within(row('Manual note')).getByText('Manual')).toBeInTheDocument();
    expect(within(row('Manual note')).getByText('—')).toBeInTheDocument();
    expect(within(row('Manual note')).getByText('(edited)')).toBeInTheDocument();
    expect(screen.getByText('transcriptions').previousElementSibling).toHaveTextContent('2');
    expect(screen.getByText('minutes recorded').previousElementSibling).toHaveTextContent('3');
  });

  it('shows an empty state', async () => {
    api.list.mockResolvedValue(page([]));
    setup();
    expect(await screen.findByText(/No transcriptions yet/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Select all on this page')).not.toBeInTheDocument();
  });

  it('searches after the user stops typing', async () => {
    const { user } = setup();
    await screen.findByText('Kannada note');
    api.list.mockResolvedValue(page([]));

    await user.type(screen.getByLabelText('Search transcriptions'), 'xyz');

    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('xyz', 0));
    expect(await screen.findByText('No transcriptions match "xyz".')).toBeInTheDocument();
  });

  it('shows load errors', async () => {
    api.list.mockRejectedValue(new Error('500 Internal Server Error'));
    setup();
    expect(await screen.findByText('Could not load transcriptions: 500 Internal Server Error')).toBeInTheDocument();
  });

  it('pages through results', async () => {
    api.list.mockImplementation(async (_q: string, p: number) => page([item(p * 10 + 1)], p, 2));
    const { user } = setup();

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '← Prev' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Next →' }));
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByText('Item 11')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next →' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '← Prev' }));
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument();
  });

  it('goes back to the first page when a new search is typed', async () => {
    api.list.mockImplementation(async (_q: string, p: number) => page([item(p * 10 + 1)], p, 2));
    const { user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Next →' }));
    await screen.findByText('Page 2 of 2');

    await user.type(screen.getByLabelText('Search transcriptions'), 'item');
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('item', 0));
  });

  it('steps back a page when the current page becomes empty', async () => {
    api.list.mockImplementation(async (_q: string, p: number) => (p === 0 ? page([item(1)], 0, 2) : page([], 1, 1)));
    const { user } = setup();
    await user.click(await screen.findByRole('button', { name: 'Next →' }));

    await waitFor(() => expect(api.list).toHaveBeenCalledWith('', 1));
    await waitFor(() => expect(api.list).toHaveBeenLastCalledWith('', 0));
  });

  it('deletes one item after confirmation', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { user } = setup();
    await screen.findByText('Kannada note');

    await user.click(within(row('Kannada note')).getByTitle('Delete'));

    expect(confirm).toHaveBeenCalledWith('Delete "Kannada note"? This cannot be undone.');
    expect(api.remove).toHaveBeenCalledWith(1);
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });

  it('does not delete when the confirmation is cancelled', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { user } = setup();
    await screen.findByText('Kannada note');

    await user.click(within(row('Kannada note')).getByTitle('Delete'));
    expect(api.remove).not.toHaveBeenCalled();
  });

  it('shows delete errors', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    api.remove.mockRejectedValue(new Error('Transcription 1 not found'));
    const { user } = setup();
    await screen.findByText('Kannada note');

    await user.click(within(row('Kannada note')).getByTitle('Delete'));
    expect(await screen.findByText('Delete failed: Transcription 1 not found')).toBeInTheDocument();
  });

  describe('multi-select', () => {
    it('selects rows one by one and shows the count', async () => {
      const { user } = setup();
      await screen.findByText('Kannada note');
      expect(screen.getByText('Select all')).toBeInTheDocument();

      await user.click(screen.getByLabelText('Select Kannada note'));

      expect(screen.getByText('1 selected')).toBeInTheDocument();
      expect(row('Kannada note')).toHaveClass('selected');
      expect(selectAll().indeterminate).toBe(true);
      expect(selectAll().checked).toBe(false);

      await user.click(screen.getByLabelText('Select Kannada note'));
      expect(screen.getByText('Select all')).toBeInTheDocument();
    });

    it('select all toggles every row on the page', async () => {
      const { user } = setup();
      await screen.findByText('Kannada note');

      await user.click(selectAll());
      expect(screen.getByText('2 selected')).toBeInTheDocument();
      expect(selectAll().checked).toBe(true);
      expect(selectAll().indeterminate).toBe(false);

      await user.click(selectAll());
      expect(screen.getByText('Select all')).toBeInTheDocument();
    });

    it('deletes the selected rows in one request', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
      const { user } = setup();
      await screen.findByText('Kannada note');
      await user.click(screen.getByLabelText('Select Manual note'));
      await user.click(screen.getByLabelText('Select Kannada note'));

      await user.click(screen.getByRole('button', { name: /Delete selected \(2\)/ }));

      expect(confirm).toHaveBeenCalledWith('Delete 2 selected transcriptions? This cannot be undone.');
      expect(api.removeMany).toHaveBeenCalledWith([2, 1]);
      await waitFor(() => expect(screen.getByText('Select all')).toBeInTheDocument());
      expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('uses singular wording for one item and respects cancel', async () => {
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
      const { user } = setup();
      await screen.findByText('Kannada note');
      await user.click(screen.getByLabelText('Select Kannada note'));

      await user.click(screen.getByRole('button', { name: /Delete selected \(1\)/ }));

      expect(confirm).toHaveBeenCalledWith('Delete 1 selected transcription? This cannot be undone.');
      expect(api.removeMany).not.toHaveBeenCalled();
      expect(screen.getByText('1 selected')).toBeInTheDocument();
    });

    it('shows bulk delete errors and keeps the selection', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      api.removeMany.mockRejectedValue(new Error('400 Bad Request'));
      const { user } = setup();
      await screen.findByText('Kannada note');
      await user.click(screen.getByLabelText('Select Kannada note'));

      await user.click(screen.getByRole('button', { name: /Delete selected/ }));

      expect(await screen.findByText('Delete failed: 400 Bad Request')).toBeInTheDocument();
      expect(screen.getByText('1 selected')).toBeInTheDocument();
    });

    it('Clear empties the selection', async () => {
      const { user } = setup();
      await screen.findByText('Kannada note');
      await user.click(selectAll());

      await user.click(screen.getByRole('button', { name: 'Clear' }));
      expect(screen.getByText('Select all')).toBeInTheDocument();
    });

    it('drops a row from the selection when it is deleted individually', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const { user } = setup();
      await screen.findByText('Kannada note');
      await user.click(selectAll());

      await user.click(within(row('Kannada note')).getByTitle('Delete'));
      await waitFor(() => expect(screen.getByText('1 selected')).toBeInTheDocument());
    });

    it('resets the selection when the page changes', async () => {
      api.list.mockImplementation(async (_q: string, p: number) => page([item(p * 10 + 1)], p, 2));
      const { user } = setup();
      await screen.findByText('Item 1');
      await user.click(screen.getByLabelText('Select Item 1'));
      expect(screen.getByText('1 selected')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Next →' }));
      await screen.findByText('Item 11');
      expect(screen.getByText('Select all')).toBeInTheDocument();
    });
  });

  it('creates a manual transcription from the New dialog', async () => {
    const { user } = setup();
    await screen.findByText('Kannada note');

    await user.click(screen.getByRole('button', { name: '+ New' }));
    expect(screen.getByRole('heading', { name: 'New transcription' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Text'), 'typed note');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(api.create).toHaveBeenCalledWith({ title: undefined, content: 'typed note', source: 'MANUAL' });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'New transcription' })).not.toBeInTheDocument());
  });

  it('edits an existing transcription', async () => {
    const { user } = setup();
    await screen.findByText('Kannada note');

    await user.click(within(row('Manual note')).getByTitle('Edit'));
    const text = screen.getByLabelText('Text');
    expect(text).toHaveValue('content 2');
    await user.clear(text);
    await user.type(text, 'fixed text');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(api.update).toHaveBeenCalledWith(2, { title: 'Manual note', content: 'fixed text' });
  });

  it('copies the text to the clipboard', async () => {
    const { user } = setup();
    await screen.findByText('Kannada note');
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');

    await user.click(within(row('Manual note')).getByTitle('Copy text'));
    expect(writeText).toHaveBeenCalledWith('content 2');
  });

  it('expands and collapses the full text', async () => {
    const { user } = setup();
    await screen.findByText('Kannada note');
    const content = screen.getByText('content 2');

    await user.click(screen.getByRole('button', { name: 'Manual note' }));
    expect(content).toHaveClass('expanded');
    await user.click(content);
    expect(content).not.toHaveClass('expanded');
  });

  it('reloads when the refresh key changes', async () => {
    const { rerender } = render(<HistoryDashboard refreshKey={0} />);
    await screen.findAllByText('Kannada note');
    rerender(<HistoryDashboard refreshKey={1} />);
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });
});
