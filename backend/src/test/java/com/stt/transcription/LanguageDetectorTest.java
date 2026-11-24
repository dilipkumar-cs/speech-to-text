package com.stt.transcription;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Hello, how are you today?                  | en",
            "आज मौसम बहुत अच्छा है                       | hi",
            "मुझे कल meeting attend करनी है               | hi+en",
            "kya aap tomorrow office aa rahe ho         | hi+en",
            "kya haal hai bhai                          | hi",
    })
    void detectsHindiEnglishAndMixedSpeech(String text, String expected) {
        assertThat(LanguageDetector.detect(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void returnsNullWithoutText(String text) {
        assertThat(LanguageDetector.detect(text)).isNull();
        assertThat(LanguageDetector.detect("123 !!")).isNull();
    }
}
