package com.stt.realtime.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stt.config.SttProperties;
import com.stt.realtime.LiveTranscriber;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Streams audio to the Gemini Live API ({@code BidiGenerateContent} WebSocket) using the dedicated
 * live transcription model. The API key never leaves the server: the browser talks only to our backend.
 */
@Component
public class GeminiLiveTranscriber implements LiveTranscriber {

    private final SttProperties.Gemini config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GeminiLiveTranscriber(SttProperties properties, ObjectMapper mapper) {
        this.config = properties.gemini();
        this.mapper = mapper;
    }

    @Override
    public boolean isAvailable() {
        return config.enabled();
    }

    @Override
    public boolean cumulativeFinals() {
        // SMART mode returns the whole session text in each final message; VERBATIM returns only the new segment.
        return "SMART".equals(config.mode());
    }

    @Override
    public Session open(Listener listener) {
        if (!isAvailable()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }
        URI uri = URI.create(config.endpoint() + "?key=" + URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8));
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.connect(httpClient, uri, setupMessage());
        return session;
    }

    /** Builds the first message of the session, which selects the model and turns on input transcription. */
    String setupMessage() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode setup = root.putObject("setup");
        setup.put("model", config.model());
        setup.putObject("generationConfig").putArray("responseModalities").add("TEXT");

        ObjectNode transcription = setup.putObject("inputAudioTranscription");
        // Empty list = automatic detection, which also handles code-switching (e.g. Hindi + English).
        ArrayNode languages = transcription.putArray("languageCodes");
        config.languageCodes().forEach(languages::add);
        transcription.put("mode", config.mode());
        if (!config.customVocabulary().isEmpty()) {
            ArrayNode vocabulary = transcription.putArray("customVocabulary");
            config.customVocabulary().forEach(vocabulary::add);
        }
        return root.toString();
    }
}
