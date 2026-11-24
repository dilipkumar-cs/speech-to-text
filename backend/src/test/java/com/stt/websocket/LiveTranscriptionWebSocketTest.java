package com.stt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.client.LiveTranscriber;
import com.stt.entity.TranscriptionSource;
import com.stt.repository.TranscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the whole browser ↔ server ↔ engine ↔ database flow with a fake engine that "recognises"
 * one word per audio chunk. Tests can also drive engine events directly through {@link #ENGINE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveTranscriptionWebSocketTest {

    /** The listener of the most recently opened fake engine session. */
    static final AtomicReference<LiveTranscriber.Listener> ENGINE = new AtomicReference<>();
    static final AtomicBoolean AVAILABLE = new AtomicBoolean(true);
    static final AtomicInteger CLOSED_SESSIONS = new AtomicInteger();
    /** When true the fake engine hears nothing when the audio ends. */
    static final AtomicBoolean SILENT = new AtomicBoolean();

    @LocalServerPort
    int port;
    @Autowired
    TranscriptionRepository repository;
    @Autowired
    ObjectMapper mapper;

    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

    @BeforeEach
    void clean() {
        repository.deleteAll();
        AVAILABLE.set(true);
        SILENT.set(false);
        CLOSED_SESSIONS.set(0);
    }

    @AfterEach
    void reset() {
        AVAILABLE.set(true);
    }

    private WebSocketSession connect() throws Exception {
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                received.add(mapper.readTree(message.getPayload()));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        }, "ws://localhost:" + port + "/ws/transcribe").get(5, TimeUnit.SECONDS);
    }

    @Test
    void streamsTranscriptsAndSavesOnStop() throws Exception {
        WebSocketSession ws = connect();
        assertThat(next("ready")).isNotNull();

        ws.sendMessage(new BinaryMessage(new byte[3200]));
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        JsonNode transcript = next("transcript");
        assertThat(transcript.path("interim").asText()).isEqualTo("namaste");

        ws.sendMessage(new TextMessage("{\"type\":\"stop\",\"title\":\"Greeting\"}"));
        JsonNode t = next("saved").path("transcription");

        assertThat(t.path("title").asText()).isEqualTo("Greeting");
        assertThat(t.path("content").asText()).isEqualTo("namaste duniya, hello world");
        assertThat(t.path("languages").asText()).isEqualTo("hi+en");
        assertThat(repository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getSource()).isEqualTo(TranscriptionSource.GEMINI_LIVE));
        assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
        assertThat(CLOSED_SESSIONS.get()).isEqualTo(1);
    }

    @Test
    void cancelDiscardsTheRecording() throws Exception {
        WebSocketSession ws = connect();
        next("ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        next("transcript");

        ws.sendMessage(new TextMessage("{\"type\":\"cancel\"}"));
        closed.get(5, TimeUnit.SECONDS);
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(repository.count()).isZero();
    }

    @Test
    void stopWithoutSpeechSavesNothing() throws Exception {
        SILENT.set(true);
        WebSocketSession ws = connect();
        next("ready");
        ws.sendMessage(new TextMessage("{\"type\":\"stop\"}"));

        JsonNode saved = next("saved");
        assertThat(saved.path("transcription").isNull()).isTrue();
        assertThat(repository.count()).isZero();
    }

    @Test
    void audioAfterStopIsIgnoredAndSecondStopIsHarmless() throws Exception {
        WebSocketSession ws = connect();
        next("ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        next("transcript");
        ws.sendMessage(new TextMessage("{\"type\":\"stop\"}"));
        ws.sendMessage(new BinaryMessage(new byte[3200])); // would add "namaste duniya," if not ignored
        ws.sendMessage(new TextMessage("{\"type\":\"stop\"}"));

        // the final segment replaces the interim hypothesis "namaste"
        assertThat(next("saved").path("transcription").path("content").asText()).isEqualTo("hello world");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void unknownCommandIsReportedAsError() throws Exception {
        WebSocketSession ws = connect();
        next("ready");
        ws.sendMessage(new TextMessage("{\"type\":\"pause\"}"));
        assertThat(next("error").path("message").asText()).isEqualTo("Unknown command: pause");
        ws.sendMessage(new TextMessage("{\"type\":\"cancel\"}"));
    }

    @Test
    void engineErrorsAreRelayedToTheBrowser() throws Exception {
        WebSocketSession ws = connect();
        next("ready");
        ENGINE.get().onError("quota exceeded");
        assertThat(next("error").path("message").asText()).isEqualTo("quota exceeded");
        ws.sendMessage(new TextMessage("{\"type\":\"cancel\"}"));
    }

    @Test
    void engineClosingMidRecordingSavesWhatWasHeardAndEndsTheSession() throws Exception {
        connect();
        next("ready");
        ENGINE.get().onFinal("partial sentence");
        next("transcript");

        ENGINE.get().onClosed();

        assertThat(next("saved").path("transcription").path("content").asText()).startsWith("partial sentence");
        assertThat(closed.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void browserDisconnectingStillSavesTheTranscript() throws Exception {
        WebSocketSession ws = connect();
        next("ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        next("transcript");

        ws.close();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (repository.count() == 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(repository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getContent()).isNotBlank());
    }

    @Test
    void rejectsConnectionWhenNoEngineIsConfigured() throws Exception {
        AVAILABLE.set(false);
        connect();

        assertThat(next("error").path("message").asText()).contains("not configured");
        assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode next(String type) throws InterruptedException {
        return next(received, type);
    }

    static JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JsonNode msg = queue.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null && type.equals(msg.path("type").asText())) {
                return msg;
            }
        }
        throw new AssertionError("No '" + type + "' message received");
    }

    @TestConfiguration
    static class FakeEngineConfig {
        @Bean
        @Primary
        LiveTranscriber fakeTranscriber() {
            return new LiveTranscriber() {
                @Override
                public boolean isAvailable() {
                    return AVAILABLE.get();
                }

                @Override
                public Session open(Listener listener) {
                    ENGINE.set(listener);
                    listener.onReady();
                    AtomicInteger chunks = new AtomicInteger();
                    return new Session() {
                        @Override
                        public void sendAudio(byte[] pcm16) {
                            if (chunks.incrementAndGet() == 1) {
                                listener.onInterim("namaste");
                            } else {
                                listener.onFinal("namaste duniya,");
                            }
                        }

                        @Override
                        public void endAudio() {
                            if (!SILENT.get()) {
                                listener.onFinal("hello world");
                            }
                        }

                        @Override
                        public void close() {
                            CLOSED_SESSIONS.incrementAndGet();
                            listener.onClosed();
                        }
                    };
                }
            };
        }
    }
}
