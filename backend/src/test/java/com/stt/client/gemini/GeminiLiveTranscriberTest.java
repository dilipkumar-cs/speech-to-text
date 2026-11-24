package com.stt.client.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.client.LiveTranscriber;
import com.stt.config.SttProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiLiveTranscriberTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SttProperties props(String apiKey, String mode, List<String> languages, List<String> vocabulary) {
        return new SttProperties(List.of(), 570, new SttProperties.Gemini(apiKey, "models/gemini-3.5-transcribe-live",
                "wss://example/ws", languages, mode, vocabulary));
    }

    @Test
    void availableOnlyWithAnApiKey() {
        assertThat(new GeminiLiveTranscriber(props("key", null, null, null), mapper).isAvailable()).isTrue();
        assertThat(new GeminiLiveTranscriber(props(" ", null, null, null), mapper).isAvailable()).isFalse();
        assertThat(new GeminiLiveTranscriber(props(null, null, null, null), mapper).isAvailable()).isFalse();
    }

    @Test
    void openingWithoutAnApiKeyFails() {
        GeminiLiveTranscriber transcriber = new GeminiLiveTranscriber(props("", null, null, null), mapper);
        assertThatThrownBy(() -> transcriber.open(mock(LiveTranscriber.Listener.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void onlySmartModeReturnsCumulativeFinals() {
        assertThat(new GeminiLiveTranscriber(props("k", "smart", null, null), mapper).cumulativeFinals()).isTrue();
        assertThat(new GeminiLiveTranscriber(props("k", "VERBATIM", null, null), mapper).cumulativeFinals()).isFalse();
        assertThat(new GeminiLiveTranscriber(props("k", null, null, null), mapper).cumulativeFinals()).isFalse();
    }

    @Test
    void setupMessageEnablesAutoDetectTranscription() throws Exception {
        GeminiLiveTranscriber transcriber = new GeminiLiveTranscriber(
                props("key", "verbatim", List.of(), List.of("Procucev")), mapper);
        JsonNode setup = mapper.readTree(transcriber.setupMessage()).path("setup");

        assertThat(setup.path("model").asText()).isEqualTo("models/gemini-3.5-transcribe-live");
        assertThat(setup.path("generationConfig").path("responseModalities").get(0).asText()).isEqualTo("TEXT");
        JsonNode transcription = setup.path("inputAudioTranscription");
        assertThat(transcription.path("languageCodes").isArray()).isTrue();
        assertThat(transcription.path("languageCodes")).isEmpty();
        assertThat(transcription.path("mode").asText()).isEqualTo("VERBATIM");
        assertThat(transcription.path("customVocabulary").get(0).asText()).isEqualTo("Procucev");
    }

    @Test
    void setupMessageIncludesConfiguredLanguagesAndOmitsEmptyVocabulary() throws Exception {
        GeminiLiveTranscriber transcriber = new GeminiLiveTranscriber(
                props("key", null, List.of(" hi-IN ", "kn-IN", ""), List.of()), mapper);
        JsonNode transcription = mapper.readTree(transcriber.setupMessage()).path("setup").path("inputAudioTranscription");

        assertThat(transcription.path("languageCodes").toString()).isEqualTo("[\"hi-IN\",\"kn-IN\"]");
        assertThat(transcription.has("customVocabulary")).isFalse();
    }

    @Test
    void openConnectsWithUrlEncodedKeyAndSendsSetup() {
        HttpClient httpClient = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        WebSocket ws = mock(WebSocket.class);
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(ws));
        when(ws.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(ws));

        GeminiLiveTranscriber transcriber = new GeminiLiveTranscriber(props("a+b/c", null, null, null), mapper, httpClient);
        LiveTranscriber.Session session = transcriber.open(mock(LiveTranscriber.Listener.class));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(builder).buildAsync(uri.capture(), any());
        assertThat(uri.getValue().toString()).isEqualTo("wss://example/ws?key=a%2Bb%2Fc");
        verify(ws).sendText(transcriber.setupMessage(), true);
        assertThat(session).isInstanceOf(GeminiLiveSession.class);
        session.close();
    }
}
