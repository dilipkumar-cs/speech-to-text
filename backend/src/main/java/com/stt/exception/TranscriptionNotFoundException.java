package com.stt.exception;

public class TranscriptionNotFoundException extends RuntimeException {

    public TranscriptionNotFoundException(long id) {
        super("Transcription " + id + " not found");
    }
}
