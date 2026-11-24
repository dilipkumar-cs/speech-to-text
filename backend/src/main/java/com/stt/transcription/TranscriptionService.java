package com.stt.transcription;

import com.stt.transcription.dto.PageResponse;
import com.stt.transcription.dto.StatsResponse;
import com.stt.transcription.dto.TranscriptionRequest;
import com.stt.transcription.dto.TranscriptionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class TranscriptionService {

    private static final DateTimeFormatter DEFAULT_TITLE =
            DateTimeFormatter.ofPattern("'Recording' dd MMM yyyy, HH:mm 'UTC'");
    static final int MAX_PAGE_SIZE = 100;

    private final TranscriptionRepository repository;

    public TranscriptionService(TranscriptionRepository repository) {
        this.repository = repository;
    }

    public PageResponse<TranscriptionResponse> list(String query, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Transcription> result = (query == null || query.isBlank())
                ? repository.findAll(pageable)
                : repository.search(query.trim(), pageable);
        return PageResponse.of(result, TranscriptionResponse::from);
    }

    public TranscriptionResponse get(long id) {
        return TranscriptionResponse.from(find(id));
    }

    @Transactional
    public TranscriptionResponse create(TranscriptionRequest request) {
        TranscriptionSource source = request.source() == null ? TranscriptionSource.MANUAL : request.source();
        return save(request.title(), request.content(), source, request.durationSeconds());
    }

    /** Used by the live WebSocket pipeline once a recording is finished. */
    @Transactional
    public TranscriptionResponse save(String title, String content, TranscriptionSource source, Integer durationSeconds) {
        String text = content.strip();
        Transcription entity = new Transcription(titleOrDefault(title), text, LanguageDetector.detect(text),
                source, durationSeconds);
        return TranscriptionResponse.from(repository.save(entity));
    }

    @Transactional
    public TranscriptionResponse update(long id, TranscriptionRequest request) {
        Transcription entity = find(id);
        String text = request.content().strip();
        if (request.title() != null && !request.title().isBlank()) {
            entity.setTitle(request.title().strip());
        }
        entity.setContent(text);
        entity.setLanguages(LanguageDetector.detect(text));
        return TranscriptionResponse.from(repository.saveAndFlush(entity));
    }

    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
    }

    public StatsResponse stats() {
        return new StatsResponse(repository.count(), repository.totalDurationSeconds());
    }

    private Transcription find(long id) {
        return repository.findById(id).orElseThrow(() -> new TranscriptionNotFoundException(id));
    }

    private static String titleOrDefault(String title) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        return ZonedDateTime.now(ZoneOffset.UTC).format(DEFAULT_TITLE);
    }
}
