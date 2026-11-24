package com.stt.dto;

import com.stt.entity.TranscriptionSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Payload for creating or updating a transcription. */
public record TranscriptionRequest(
        @Size(max = 200) String title,
        @NotBlank @Size(max = 100_000) String content,
        TranscriptionSource source,
        @PositiveOrZero @Max(86_400) Integer durationSeconds
) {
}
