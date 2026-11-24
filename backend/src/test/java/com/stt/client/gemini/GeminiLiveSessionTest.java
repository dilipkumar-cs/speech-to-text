package com.stt.client.gemini;

import com.stt.client.LiveTranscriber;
import com.stt.config.SttProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiLiveSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingListener listener = new RecordingListener();
    private final GeminiLiveSession session = new GeminiLiveSession(mapper, listener);

    @Test
    void parsesInterimAndFinalTranscriptions() {
        session.handleMessage("{\"setupComplete\":{}}");
        session.handleMessage("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"mujhe kal\"}}}");
        session.handleMessage("{\"serverContent\":{\"inputTranscription\":{\"text\":\"मुझे कल meeting में जाना है\"}}}");
        session.handleMessage("{\"serverContent\":{\"turnComplete\":true}}");

        assertThat(listener.events).containsExactly(
                "ready", "interim:mujhe kal", "final:मुझे कल meeting में जाना है");
    }

    @Test
    void reportsServerErrorsAndIgnoresGarbage() {
        session.handleMessage("not json");
        session.handleMessage("{\"error\":{\"message\":\"quota exceeded\"}}");
        assertThat(listener.events).containsExactly("error:Gemini error: quota exceeded");
    }

    @Test
    void setupMessageEnablesAutoDetectTranscription() throws Exception {
        SttProperties props = new SttProperties(List.of(), 570, new SttProperties.Gemini(
                "key", "models/gemini-3.5-transcribe-live", "wss://example", List.of(), "verbatim", List.of("Procucev")));
        JsonNode setup = mapper.readTree(new GeminiLiveTranscriber(props, mapper).setupMessage()).path("setup");

        assertThat(setup.path("model").asText()).isEqualTo("models/gemini-3.5-transcribe-live");
        assertThat(setup.path("generationConfig").path("responseModalities").get(0).asText()).isEqualTo("TEXT");
        JsonNode transcription = setup.path("inputAudioTranscription");
        assertThat(transcription.path("languageCodes").isArray()).isTrue();
        assertThat(transcription.path("languageCodes")).isEmpty();
        assertThat(transcription.path("mode").asText()).isEqualTo("VERBATIM");
        assertThat(transcription.path("customVocabulary").get(0).asText()).isEqualTo("Procucev");
    }

    private static final class RecordingListener implements LiveTranscriber.Listener {
        final List<String> events = new ArrayList<>();

        @Override public void onReady() { events.add("ready"); }
        @Override public void onInterim(String text) { events.add("interim:" + text); }
        @Override public void onFinal(String text) { events.add("final:" + text); }
        @Override public void onError(String message) { events.add("error:" + message); }
        @Override public void onClosed() { events.add("closed"); }
    }
}
