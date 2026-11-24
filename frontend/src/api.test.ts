import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, webSocketUrl } from './api';

function jsonResponse(body: unknown, status = 200, statusText = 'OK'): Response {
  return new Response(JSON.stringify(body), {
    status,
    statusText,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('api', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  const lastCall = () => {
    const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1];
    return { url: url as string, init: init as RequestInit };
  };

  it('list builds the query string with search, page and size', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [], page: 2, size: 5, totalItems: 0, totalPages: 0 }));

    const page = await api.list('stand up', 2, 5);

    expect(lastCall().url).toBe('/api/transcriptions?q=stand+up&page=2&size=5');
    expect(lastCall().init.headers).toMatchObject({ 'Content-Type': 'application/json' });
    expect(page.page).toBe(2);
  });

  it('list defaults the page size to 10', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [] }));
    await api.list('', 0);
    expect(lastCall().url).toBe('/api/transcriptions?q=&page=0&size=10');
  });

  it('info and stats call their endpoints', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ geminiEnabled: true }));
    await api.info();
    expect(lastCall().url).toBe('/api/info');

    fetchMock.mockResolvedValue(jsonResponse({ totalTranscriptions: 1 }));
    await api.stats();
    expect(lastCall().url).toBe('/api/transcriptions/stats');
  });

  it('create posts the body as JSON', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 1 }, 201));
    const created = await api.create({ title: 'T', content: 'hello', source: 'MANUAL' });

    expect(lastCall().url).toBe('/api/transcriptions');
    expect(lastCall().init.method).toBe('POST');
    expect(JSON.parse(lastCall().init.body as string)).toEqual({ title: 'T', content: 'hello', source: 'MANUAL' });
    expect(created).toEqual({ id: 1 });
  });

  it('update puts to the item URL', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 7 }));
    await api.update(7, { content: 'new text' });

    expect(lastCall().url).toBe('/api/transcriptions/7');
    expect(lastCall().init.method).toBe('PUT');
    expect(JSON.parse(lastCall().init.body as string)).toEqual({ content: 'new text' });
  });

  it('remove sends DELETE and returns undefined for 204', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const result = await api.remove(3);

    expect(lastCall().url).toBe('/api/transcriptions/3');
    expect(lastCall().init.method).toBe('DELETE');
    expect(result).toBeUndefined();
  });

  it('removeMany joins ids into one bulk DELETE', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ deleted: 3 }));
    const result = await api.removeMany([1, 2, 5]);

    expect(lastCall().url).toBe('/api/transcriptions?ids=1,2,5');
    expect(lastCall().init.method).toBe('DELETE');
    expect(result).toEqual({ deleted: 3 });
  });

  it('turns problem details with field errors into an ApiError message', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ detail: 'Validation failed', errors: { content: 'must not be blank' } }, 400, 'Bad Request'),
    );

    const error = await api.create({ content: ' ' }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe('Validation failed: content must not be blank');
  });

  it('uses the problem detail alone when there are no field errors', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ detail: 'Transcription 9 not found' }, 404, 'Not Found'));
    await expect(api.remove(9)).rejects.toThrow('Transcription 9 not found');
  });

  it('falls back to status text when the error body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('<html>oops</html>', { status: 500, statusText: 'Internal Server Error' }));
    await expect(api.stats()).rejects.toThrow('500 Internal Server Error');
  });

  it('falls back to status text when the problem has no detail', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 503, 'Service Unavailable'));
    await expect(api.stats()).rejects.toThrow('503 Service Unavailable');
  });
});

describe('webSocketUrl', () => {
  const original = window.location;

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });

  const setLocation = (protocol: string, host: string) =>
    Object.defineProperty(window, 'location', { value: { ...original, protocol, host }, configurable: true });

  it('uses ws:// for http pages', () => {
    setLocation('http:', 'localhost:5173');
    expect(webSocketUrl('/ws/transcribe')).toBe('ws://localhost:5173/ws/transcribe');
  });

  it('uses wss:// for https pages', () => {
    setLocation('https:', 'stt.example.com');
    expect(webSocketUrl('/ws/transcribe')).toBe('wss://stt.example.com/ws/transcribe');
  });
});
