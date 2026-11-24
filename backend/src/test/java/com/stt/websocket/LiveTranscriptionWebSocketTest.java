package com.stt.websocket;

import com.stt.client.LiveTranscriber;
import com.stt.entity.TranscriptionSource;
import com.stt.repository.TranscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the whole browser ↔ server ↔ engine ↔ database flow with a fake engine that "recognises"
 * one word per audio chunk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveTranscriptionWebSocketTest {

    @LocalServerPort
    int port;
    @Autowired
    TranscriptionRepository repository;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void streamsTranscriptsAndSavesOnStop() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                received.add(mapper.readTree(message.getPayload()));
            }
        }, "ws://localhost:" + port + "/ws/transcribe").get(5, TimeUnit.SECONDS);

        assertThat(next(received, "ready")).isNotNull();

        ws.sendMessage(new BinaryMessage(new byte[3200]));
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        JsonNode transcript = next(received, "transcript");
        assertThat(transcript.path("interim").asText()).isEqualTo("namaste");

        ws.sendMessage(new TextMessage("{\"type\":\"stop\",\"title\":\"Greeting\"}"));
        JsonNode saved = next(received, "saved");

        JsonNode t = saved.path("transcription");
        assertThat(t.path("title").asText()).isEqualTo("Greeting");
        assertThat(t.path("content").asText()).isEqualTo("namaste duniya, hello world");
        assertThat(t.path("languages").asText()).isEqualTo("hi+en");
        assertThat(repository.findAll()).singleElement()
                .satisfies(e -> assertThat(e.getSource()).isEqualTo(TranscriptionSource.GEMINI_LIVE));
    }

    @Test
    void cancelDiscardsTheRecording() throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                received.add(mapper.readTree(message.getPayload()));
            }
        }, "ws://localhost:" + port + "/ws/transcribe").get(5, TimeUnit.SECONDS);
        next(received, "ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        next(received, "transcript");

        ws.sendMessage(new TextMessage("{\"type\":\"cancel\"}"));
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(repository.count()).isZero();
    }

    private static JsonNode next(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
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
                    return true;
                }

                @Override
                public Session open(Listener listener) {
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
                            listener.onFinal("hello world");
                        }

                        @Override
                        public void close() {
                            listener.onClosed();
                        }
                    };
                }
            };
        }
    }
}
