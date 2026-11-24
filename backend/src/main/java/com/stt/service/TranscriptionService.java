package com.stt.service;

import com.stt.dto.PageResponse;
import com.stt.dto.StatsResponse;
import com.stt.dto.TranscriptionRequest;
import com.stt.dto.TranscriptionResponse;
import com.stt.entity.TranscriptionSource;

/** Business operations on stored transcriptions. */
public interface TranscriptionService {

    /** Newest first; {@code query} filters on title or text (case-insensitive). */
    PageResponse<TranscriptionResponse> list(String query, int page, int size);

    TranscriptionResponse get(long id);

    TranscriptionResponse create(TranscriptionRequest request);

    /** Stores a transcript produced by a live speech-to-text session. */
    TranscriptionResponse save(String title, String content, TranscriptionSource source, Integer durationSeconds);

    TranscriptionResponse update(long id, TranscriptionRequest request);

    void delete(long id);

    StatsResponse stats();
}
