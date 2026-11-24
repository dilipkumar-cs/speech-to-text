package com.stt.transcription;

import com.stt.transcription.dto.PageResponse;
import com.stt.transcription.dto.StatsResponse;
import com.stt.transcription.dto.TranscriptionRequest;
import com.stt.transcription.dto.TranscriptionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    private final TranscriptionService service;

    public TranscriptionController(TranscriptionService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<TranscriptionResponse> list(@RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "10") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    public TranscriptionResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<TranscriptionResponse> create(@Valid @RequestBody TranscriptionRequest request) {
        TranscriptionResponse created = service.create(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @PutMapping("/{id}")
    public TranscriptionResponse update(@PathVariable long id, @Valid @RequestBody TranscriptionRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
