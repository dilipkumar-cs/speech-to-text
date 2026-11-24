package com.stt.mapper;

import com.stt.dto.PageResponse;
import com.stt.dto.TranscriptionResponse;
import com.stt.entity.Transcription;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/** Converts entities to API DTOs so JPA entities never leave the service layer. */
@Component
public class TranscriptionMapper {

    public TranscriptionResponse toResponse(Transcription t) {
        return new TranscriptionResponse(t.getId(), t.getTitle(), t.getContent(), t.getLanguages(), t.getSource(),
                t.getDurationSeconds(), countWords(t.getContent()), t.getCreatedAt(), t.getUpdatedAt());
    }

    public PageResponse<TranscriptionResponse> toPageResponse(Page<Transcription> page) {
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    static int countWords(String content) {
        return content == null || content.isBlank() ? 0 : content.trim().split("\\s+").length;
    }
}
