package com.stt.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptAccumulatorTest {

    @Test
    void appendsVerbatimSegmentsAndShowsInterimAfterThem() {
        TranscriptAccumulator acc = new TranscriptAccumulator(false);

        acc.setInterim("namaste");
        assertThat(acc.fullText()).isEqualTo("namaste");

        acc.addFinal("Namaste, ");
        acc.setInterim("how are");
        assertThat(acc.finalText()).isEqualTo("Namaste,");
        assertThat(acc.fullText()).isEqualTo("Namaste, how are");

        acc.addFinal("how are you?");
        assertThat(acc.interim()).isEmpty();
        assertThat(acc.fullText()).isEqualTo("Namaste, how are you?");
    }

    @Test
    void insertsSpaceBetweenSegmentsWithoutWhitespace() {
        TranscriptAccumulator acc = new TranscriptAccumulator(false);
        acc.addFinal("मैं ठीक हूँ");
        acc.addFinal("thank you");
        assertThat(acc.finalText()).isEqualTo("मैं ठीक हूँ thank you");
    }

    @Test
    void cumulativeModeReplacesInsteadOfAppending() {
        TranscriptAccumulator acc = new TranscriptAccumulator(true);
        acc.addFinal("Hello");
        acc.addFinal("Hello, aap kaise ho?");
        assertThat(acc.finalText()).isEqualTo("Hello, aap kaise ho?");
    }

    @Test
    void ignoresBlankFinals() {
        TranscriptAccumulator acc = new TranscriptAccumulator(false);
        acc.addFinal("Hi");
        acc.addFinal("   ");
        acc.addFinal(null);
        assertThat(acc.fullText()).isEqualTo("Hi");
    }
}
