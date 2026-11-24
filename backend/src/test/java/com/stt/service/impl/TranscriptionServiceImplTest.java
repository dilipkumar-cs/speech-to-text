package com.stt.service.impl;

import com.stt.dto.TranscriptionRequest;
import com.stt.dto.TranscriptionResponse;
import com.stt.entity.Transcription;
import com.stt.entity.TranscriptionSource;
import com.stt.exception.TranscriptionNotFoundException;
import com.stt.mapper.TranscriptionMapper;
import com.stt.repository.TranscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceImplTest {

    @Mock
    TranscriptionRepository repository;

    TranscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TranscriptionServiceImpl(repository, new TranscriptionMapper());
    }

    @Test
    void createDefaultsToManualSourceTrimsTextAndDetectsLanguage() {
        when(repository.save(any(Transcription.class))).thenAnswer(inv -> inv.getArgument(0));

        TranscriptionResponse response = service.create(
                new TranscriptionRequest("  Notes  ", "  ನಾಳೆ office ಗೆ ಹೋಗಬೇಕು  ", null, 12));

        ArgumentCaptor<Transcription> saved = ArgumentCaptor.forClass(Transcription.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Notes");
        assertThat(saved.getValue().getContent()).isEqualTo("ನಾಳೆ office ಗೆ ಹೋಗಬೇಕು");
        assertThat(saved.getValue().getSource()).isEqualTo(TranscriptionSource.MANUAL);
        assertThat(response.languages()).isEqualTo("kn+en");
        assertThat(response.wordCount()).isEqualTo(4);
    }

    @Test
    void blankTitleGetsGeneratedDefault() {
        when(repository.save(any(Transcription.class))).thenAnswer(inv -> inv.getArgument(0));

        TranscriptionResponse response = service.save(" ", "hello", TranscriptionSource.GEMINI_LIVE, 3);

        assertThat(response.title()).startsWith("Recording ");
        assertThat(response.source()).isEqualTo(TranscriptionSource.GEMINI_LIVE);
    }

    @Test
    void updateKeepsTitleWhenNotProvidedAndRedetectsLanguage() {
        Transcription existing = new Transcription("Original", "hello world", "en", TranscriptionSource.MANUAL, null);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        TranscriptionResponse response = service.update(7L, new TranscriptionRequest(null, "kya haal hai", null, null));

        assertThat(response.title()).isEqualTo("Original");
        assertThat(response.content()).isEqualTo("kya haal hai");
        assertThat(response.languages()).isEqualTo("hi");
    }

    @Test
    void missingIdThrowsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(TranscriptionNotFoundException.class)
                .hasMessageContaining("99");
        verify(repository, never()).delete(any());
    }

    @Test
    void getReturnsTheMappedTranscription() {
        Transcription existing = new Transcription("Notes", "hello", "en", TranscriptionSource.MANUAL, 5);
        when(repository.findById(3L)).thenReturn(Optional.of(existing));

        assertThat(service.get(3L).title()).isEqualTo("Notes");
    }

    @Test
    void createKeepsAnExplicitSource() {
        when(repository.save(any(Transcription.class))).thenAnswer(inv -> inv.getArgument(0));

        TranscriptionResponse response = service.create(
                new TranscriptionRequest("t", "text", TranscriptionSource.GEMINI_LIVE, null));

        assertThat(response.source()).isEqualTo(TranscriptionSource.GEMINI_LIVE);
    }

    @Test
    void updateReplacesTitleWhenProvided() {
        Transcription existing = new Transcription("Old", "text", "en", TranscriptionSource.MANUAL, null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        assertThat(service.update(1L, new TranscriptionRequest("  New  ", "text", null, null)).title()).isEqualTo("New");
    }

    @Test
    void deleteRemovesAnExistingTranscription() {
        Transcription existing = new Transcription("t", "text", "en", TranscriptionSource.MANUAL, null);
        when(repository.findById(4L)).thenReturn(Optional.of(existing));

        service.delete(4L);

        verify(repository).delete(existing);
    }

    @Test
    void deleteAllDeletesOnlyTheTranscriptionsThatExist() {
        Transcription a = new Transcription("a", "x", "en", TranscriptionSource.MANUAL, null);
        Transcription b = new Transcription("b", "y", "en", TranscriptionSource.MANUAL, null);
        when(repository.findAllById(List.of(1L, 2L, 99L))).thenReturn(List.of(a, b));

        assertThat(service.deleteAll(List.of(1L, 2L, 99L))).isEqualTo(2);
        verify(repository).deleteAllInBatch(List.of(a, b));
    }

    @Test
    void deleteAllWithNothingSelectedDoesNotTouchTheDatabase() {
        assertThat(service.deleteAll(List.of())).isZero();
        assertThat(service.deleteAll(null)).isZero();
        verify(repository, never()).findAllById(any());
        verify(repository, never()).deleteAllInBatch(any());
    }

    @Test
    void statsCombinesCountAndTotalDuration() {
        when(repository.count()).thenReturn(7L);
        when(repository.totalDurationSeconds()).thenReturn(321L);

        assertThat(service.stats().totalTranscriptions()).isEqualTo(7);
        assertThat(service.stats().totalDurationSeconds()).isEqualTo(321);
    }

    @Test
    void listUsesSearchOnlyWhenQueryPresentAndClampsPageSize() {
        Page<Transcription> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(Pageable.class))).thenReturn(empty);
        when(repository.search(eq("standup"), any(Pageable.class))).thenReturn(empty);

        service.list("  ", 0, 1_000);
        service.list(" standup ", -1, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(TranscriptionServiceImpl.MAX_PAGE_SIZE);
        verify(repository).search(eq("standup"), any(Pageable.class));
    }
}
