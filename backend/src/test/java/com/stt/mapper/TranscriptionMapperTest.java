package com.stt.mapper;

import com.stt.dto.PageResponse;
import com.stt.dto.TranscriptionResponse;
import com.stt.entity.Transcription;
import com.stt.entity.TranscriptionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionMapperTest {

    private final TranscriptionMapper mapper = new TranscriptionMapper();

    @Test
    void mapsEveryFieldOfTheEntity() {
        Transcription entity = new Transcription("Title", "ನಾಳೆ office ಗೆ", "kn+en", TranscriptionSource.GEMINI_LIVE, 42);

        TranscriptionResponse response = mapper.toResponse(entity);

        assertThat(response.title()).isEqualTo("Title");
        assertThat(response.content()).isEqualTo("ನಾಳೆ office ಗೆ");
        assertThat(response.languages()).isEqualTo("kn+en");
        assertThat(response.source()).isEqualTo(TranscriptionSource.GEMINI_LIVE);
        assertThat(response.durationSeconds()).isEqualTo(42);
        assertThat(response.wordCount()).isEqualTo(3);
    }

    @Test
    void mapsAPageWithItsPaginationData() {
        List<Transcription> items = List.of(
                new Transcription("a", "one", "en", TranscriptionSource.MANUAL, null),
                new Transcription("b", "two words", "en", TranscriptionSource.MANUAL, null));

        PageResponse<TranscriptionResponse> page = mapper.toPageResponse(new PageImpl<>(items, PageRequest.of(1, 2), 5));

        assertThat(page.items()).extracting(TranscriptionResponse::title).containsExactly("a", "b");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "NULL                       | 0",
            "'   '                      | 0",
            "hello                      | 1",
            "'  spaced   out \t words ' | 3",
            "मुझे कल meeting है           | 4",
    })
    void countsWords(String content, int expected) {
        assertThat(TranscriptionMapper.countWords(content)).isEqualTo(expected);
    }
}
