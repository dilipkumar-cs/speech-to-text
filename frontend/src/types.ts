export type TranscriptionSource = 'GEMINI_LIVE' | 'BROWSER' | 'MANUAL';

export interface Transcription {
  id: number;
  title: string;
  content: string;
  languages: string | null;
  source: TranscriptionSource;
  durationSeconds: number | null;
  wordCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Stats {
  totalTranscriptions: number;
  totalDurationSeconds: number;
}

export interface AppInfo {
  geminiEnabled: boolean;
  geminiModel: string;
  maxSessionSeconds: number;
}

export interface TranscriptionInput {
  title?: string;
  content: string;
  source?: TranscriptionSource;
  durationSeconds?: number;
}
