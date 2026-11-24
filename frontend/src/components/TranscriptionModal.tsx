import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { Transcription, TranscriptionInput } from '../types';

interface Props {
  /** null = create a new transcription */
  transcription: Transcription | null;
  onSubmit: (input: TranscriptionInput) => Promise<void>;
  onClose: () => void;
}

export function TranscriptionModal({ transcription, onSubmit, onClose }: Props) {
  const [title, setTitle] = useState(transcription?.title ?? '');
  const [content, setContent] = useState(transcription?.content ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    if (dialog.current && !dialog.current.open) dialog.current.showModal();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!content.trim()) {
      setError('Text cannot be empty.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ title: title.trim() || undefined, content });
      onClose();
    } catch (err) {
      setError((err as Error).message);
      setSaving(false);
    }
  };

  return (
    <dialog ref={dialog} className="modal" onClose={onClose}>
      <form onSubmit={submit}>
        <h2>{transcription ? 'Edit transcription' : 'New transcription'}</h2>
        <label className="field">
          <span>Title</span>
          <input value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} placeholder="Untitled" />
        </label>
        <label className="field">
          <span>Text</span>
          <textarea value={content} rows={8} onChange={(e) => setContent(e.target.value)} autoFocus />
        </label>
        {error && <div className="alert error">{error}</div>}
        <div className="modal-actions">
          <button type="button" className="ghost" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </dialog>
  );
}
