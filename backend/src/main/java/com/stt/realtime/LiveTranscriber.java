package com.stt.realtime;

/**
 * A streaming speech-to-text engine. Implementations receive raw audio (16 kHz, 16-bit, mono, little-endian PCM)
 * and report interim and final transcripts as they are produced.
 * <p>
 * Keeping this behind an interface lets the engine be swapped (Gemini Live today; a self-hosted Whisper or
 * Vosk server tomorrow) without touching the WebSocket or persistence code.
 */
public interface LiveTranscriber {

    boolean isAvailable();

    /** True when each final transcript contains the whole session text instead of only the newest segment. */
    default boolean cumulativeFinals() {
        return false;
    }

    Session open(Listener listener);

    interface Session {
        /** @param pcm16 16 kHz mono little-endian 16-bit PCM */
        void sendAudio(byte[] pcm16);

        /** Signals that the user stopped speaking so the engine can finalise immediately. */
        void endAudio();

        void close();
    }

    interface Listener {
        void onReady();

        /** Low-latency partial hypothesis for the utterance in progress (replaces the previous interim). */
        void onInterim(String text);

        /** A finalised segment of speech. */
        void onFinal(String text);

        void onError(String message);

        /** The upstream connection is gone (normally or not). */
        void onClosed();
    }
}
