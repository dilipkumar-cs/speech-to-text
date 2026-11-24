package com.stt.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.client.LiveTranscriber;
import com.stt.repository.TranscriptionRepository;
import com.stt.service.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Recording time limit and database failures, with a 1-second maximum recording length. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stt.max-session-seconds=1")
@ActiveProfiles("test")
class LiveTranscriptionLimitsTest {

    @LocalServerPort
    int port;
    @Autowired
    TranscriptionRepository repository;
    @Autowired
    ObjectMapper mapper;
    @MockitoSpyBean
    TranscriptionService transcriptionService;

    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void clean() {
        reset(transcriptionService);
        repository.deleteAll();
    }

    private WebSocketSession connect() throws Exception {
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                received.add(mapper.readTree(message.getPayload()));
            }
        }, "ws://localhost:" + port + "/ws/transcribe").get(5, TimeUnit.SECONDS);
    }

    @Test
    void recordingIsStoppedAndSavedWhenTheTimeLimitIsReached() throws Exception {
        WebSocketSession ws = connect();
        LiveTranscriptionWebSocketTest.next(received, "ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));

        // no stop command: the server ends the recording by itself after 1 s (+ finalise grace)
        JsonNode saved = LiveTranscriptionWebSocketTest.next(received, "saved");

        assertThat(saved.path("transcription").path("content").asText()).isEqualTo("limit reached");
        assertThat(saved.path("transcription").path("durationSeconds").asInt()).isBetween(1, 4);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void databaseFailureIsReportedToTheBrowser() throws Exception {
        doThrow(new IllegalStateException("database is down"))
                .when(transcriptionService).save(any(), any(), any(), any());

        WebSocketSession ws = connect();
        LiveTranscriptionWebSocketTest.next(received, "ready");
        ws.sendMessage(new BinaryMessage(new byte[3200]));
        ws.sendMessage(new TextMessage("{\"type\":\"stop\"}"));

        assertThat(LiveTranscriptionWebSocketTest.next(received, "error").path("message").asText())
                .isEqualTo("Transcription could not be saved: database is down");
        assertThat(LiveTranscriptionWebSocketTest.next(received, "saved").path("transcription").isNull()).isTrue();
        assertThat(repository.count()).isZero();
    }

    @TestConfiguration
    static class OneWordEngineConfig {
        @Bean
        @Primary
        LiveTranscriber oneWordTranscriber() {
            return new LiveTranscriber() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public Session open(Listener listener) {
                    listener.onReady();
                    return new Session() {
                        @Override
                        public void sendAudio(byte[] pcm16) {
                            listener.onFinal("limit reached");
                        }

                        @Override
                        public void endAudio() {
                        }

                        @Override
                        public void close() {
                        }
                    };
                }
            };
        }
    }
}
