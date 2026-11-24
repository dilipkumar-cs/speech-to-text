package com.stt.service.impl;

import com.stt.dto.PageResponse;
import com.stt.dto.StatsResponse;
import com.stt.dto.TranscriptionRequest;
import com.stt.dto.TranscriptionResponse;
import com.stt.entity.Transcription;
import com.stt.entity.TranscriptionSource;
import com.stt.exception.TranscriptionNotFoundException;
import com.stt.mapper.TranscriptionMapper;
import com.stt.repository.TranscriptionRepository;
import com.stt.service.TranscriptionService;
import com.stt.util.LanguageDetector;
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
public class TranscriptionServiceImpl implements TranscriptionService {

    private static final DateTimeFormatter DEFAULT_TITLE =
            DateTimeFormatter.ofPattern("'Recording' dd MMM yyyy, HH:mm 'UTC'");
    static final int MAX_PAGE_SIZE = 100;

    private final TranscriptionRepository repository;
    private final TranscriptionMapper mapper;

    public TranscriptionServiceImpl(TranscriptionRepository repository, TranscriptionMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public PageResponse<TranscriptionResponse> list(String query, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Transcription> result = (query == null || query.isBlank())
                ? repository.findAll(pageable)
                : repository.search(query.trim(), pageable);
        return mapper.toPageResponse(result);
    }

    @Override
    public TranscriptionResponse get(long id) {
        return mapper.toResponse(find(id));
    }

    @Override
    @Transactional
    public TranscriptionResponse create(TranscriptionRequest request) {
        TranscriptionSource source = request.source() == null ? TranscriptionSource.MANUAL : request.source();
        return save(request.title(), request.content(), source, request.durationSeconds());
    }

    @Override
    @Transactional
    public TranscriptionResponse save(String title, String content, TranscriptionSource source, Integer durationSeconds) {
        String text = content.strip();
        Transcription entity = new Transcription(titleOrDefault(title), text, LanguageDetector.detect(text),
                source, durationSeconds);
        return mapper.toResponse(repository.save(entity));
    }

    @Override
    @Transactional
    public TranscriptionResponse update(long id, TranscriptionRequest request) {
        Transcription entity = find(id);
        String text = request.content().strip();
        if (request.title() != null && !request.title().isBlank()) {
            entity.setTitle(request.title().strip());
        }
        entity.setContent(text);
        entity.setLanguages(LanguageDetector.detect(text));
        return mapper.toResponse(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
    }

    @Override
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
