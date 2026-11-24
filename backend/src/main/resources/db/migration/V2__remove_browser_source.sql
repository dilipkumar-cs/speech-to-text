-- The browser (Web Speech API) engine was removed. Keep any recordings it made, relabelled as MANUAL,
-- so they still map to the TranscriptionSource enum.
UPDATE transcriptions SET source = 'MANUAL' WHERE source = 'BROWSER';

-- Only allow values the application knows about.
ALTER TABLE transcriptions
    ADD CONSTRAINT chk_transcriptions_source CHECK (source IN ('GEMINI_LIVE', 'MANUAL'));
