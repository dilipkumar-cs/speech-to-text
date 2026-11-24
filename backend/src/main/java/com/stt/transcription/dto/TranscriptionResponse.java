package com.stt.transcription.dto;

import com.stt.transcription.Transcription;
import com.stt.transcription.TranscriptionSource;

import java.time.Instant;

public record TranscriptionResponse(
        Long id,
        String title,
        String content,
        String languages,
        TranscriptionSource source,
        Integer durationSeconds,
        int wordCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static TranscriptionResponse from(Transcription t) {
        String content = t.getContent();
        int words = content.isBlank() ? 0 : content.trim().split("\\s+").length;
        return new TranscriptionResponse(t.getId(), t.getTitle(), content, t.getLanguages(), t.getSource(),
                t.getDurationSeconds(), words, t.getCreatedAt(), t.getUpdatedAt());
    }
}
