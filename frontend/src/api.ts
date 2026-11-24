import type { AppInfo, Page, Stats, Transcription, TranscriptionInput } from './types';

/** Empty = same origin (production build served by Spring Boot, or Vite dev proxy). */
const API_BASE: string = import.meta.env.VITE_API_BASE ?? '';

export class ApiError extends Error {}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API_BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const problem = await res.json();
      const fieldErrors = problem.errors ? Object.entries(problem.errors).map(([f, m]) => `${f} ${m}`) : [];
      message = [problem.detail, ...fieldErrors].filter(Boolean).join(': ') || message;
    } catch {
      /* body was not JSON */
    }
    throw new ApiError(message);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export const api = {
  info: () => request<AppInfo>('/api/info'),
  list: (q: string, page: number, size = 10) =>
    request<Page<Transcription>>(
      `/api/transcriptions?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
    ),
  stats: () => request<Stats>('/api/transcriptions/stats'),
  create: (body: TranscriptionInput) =>
    request<Transcription>('/api/transcriptions', { method: 'POST', body: JSON.stringify(body) }),
  update: (id: number, body: TranscriptionInput) =>
    request<Transcription>(`/api/transcriptions/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id: number) => request<void>(`/api/transcriptions/${id}`, { method: 'DELETE' }),
};

export function webSocketUrl(path: string): string {
  if (API_BASE) {
    return API_BASE.replace(/^http/, 'ws') + path;
  }
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${protocol}://${window.location.host}${path}`;
}
