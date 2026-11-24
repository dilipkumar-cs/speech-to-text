package com.stt.dto;

import com.stt.entity.TranscriptionSource;

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
}
