package com.stt.entity;

/** Where a transcription came from. */
public enum TranscriptionSource {
    /** Streamed through the backend to the Gemini Live API. */
    GEMINI_LIVE,
    /** Typed or pasted by the user from the dashboard. */
    MANUAL
}
