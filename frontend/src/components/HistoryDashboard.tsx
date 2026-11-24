import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import type { Page, Stats, Transcription, TranscriptionInput } from '../types';
import { SpeakButton } from './SpeakButton';
import { TranscriptionModal } from './TranscriptionModal';

const SOURCE_LABEL = { GEMINI_LIVE: 'Gemini Live', BROWSER: 'Browser', MANUAL: 'Manual' } as const;
const LANG_LABEL: Record<string, string> = { hi: 'Hindi', en: 'English', 'hi+en': 'Hindi + English' };

const formatDuration = (s: number | null) =>
  s == null ? '—' : s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;

/** Transcription history with search, pagination and create / read / update / delete. */
export function HistoryDashboard({ refreshKey }: { refreshKey: number }) {
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Transcription> | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Transcription | null | undefined>(undefined); // undefined = closed
  const [expanded, setExpanded] = useState<number | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(query.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [query]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, s] = await Promise.all([api.list(debounced, page), api.stats()]);
      // deleting the last item of the last page: step back a page
      if (list.items.length === 0 && page > 0) {
        setPage(page - 1);
        return;
      }
      setData(list);
      setStats(s);
    } catch (e) {
      setError(`Could not load transcriptions: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [debounced, page]);

  useEffect(() => {
    void load();
  }, [load, refreshKey]);

  const save = async (input: TranscriptionInput) => {
    if (editing) await api.update(editing.id, input);
    else await api.create({ ...input, source: 'MANUAL' });
    await load();
  };

  const remove = async (t: Transcription) => {
    if (!window.confirm(`Delete "${t.title}"? This cannot be undone.`)) return;
    try {
      await api.remove(t.id);
      await load();
    } catch (e) {
      setError(`Delete failed: ${(e as Error).message}`);
    }
  };

  return (
    <section className="card">
      <div className="stats">
        <div>
          <span className="stat-value">{stats?.totalTranscriptions ?? '–'}</span>
          <span className="muted">transcriptions</span>
        </div>
        <div>
          <span className="stat-value">{stats ? Math.round(stats.totalDurationSeconds / 60) : '–'}</span>
          <span className="muted">minutes recorded</span>
        </div>
      </div>

      <div className="toolbar">
        <input
          type="search"
          placeholder="Search title or text…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search transcriptions"
        />
        <button className="primary" onClick={() => setEditing(null)}>
          + New
        </button>
      </div>

      {error && <div className="alert error">{error}</div>}

      {data && data.items.length === 0 && !loading && (
        <p className="empty">
          {debounced ? `No transcriptions match "${debounced}".` : 'No transcriptions yet. Record one from the Live tab.'}
        </p>
      )}

      <ul className={`history ${loading ? 'loading' : ''}`}>
        {data?.items.map((t) => (
          <li key={t.id}>
            <div className="history-head">
              <button className="title" onClick={() => setExpanded(expanded === t.id ? null : t.id)}>
                {t.title}
              </button>
              <div className="actions">
                <SpeakButton text={t.content} languages={t.languages} />
                <button className="icon" title="Copy text" onClick={() => void navigator.clipboard.writeText(t.content)}>
                  ⧉
                </button>
                <button className="icon" title="Edit" onClick={() => setEditing(t)}>
                  ✎
                </button>
                <button className="icon danger" title="Delete" onClick={() => void remove(t)}>
                  🗑
                </button>
              </div>
            </div>
            <p className={`content ${expanded === t.id ? 'expanded' : ''}`} onClick={() => setExpanded(expanded === t.id ? null : t.id)}>
              {t.content}
            </p>
            <div className="meta">
              <span className={`badge src-${t.source.toLowerCase()}`}>{SOURCE_LABEL[t.source]}</span>
              {t.languages && <span className="badge">{LANG_LABEL[t.languages] ?? t.languages}</span>}
              <span>{t.wordCount} words</span>
              <span>{formatDuration(t.durationSeconds)}</span>
              <span title={new Date(t.createdAt).toLocaleString()}>{new Date(t.createdAt).toLocaleString()}</span>
              {t.updatedAt !== t.createdAt && <span className="muted">(edited)</span>}
            </div>
          </li>
        ))}
      </ul>

      {data && data.totalPages > 1 && (
        <div className="pager">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            ← Prev
          </button>
          <span>
            Page {page + 1} of {data.totalPages}
          </span>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
            Next →
          </button>
        </div>
      )}

      {editing !== undefined && (
        <TranscriptionModal transcription={editing} onSubmit={save} onClose={() => setEditing(undefined)} />
      )}
    </section>
  );
}
