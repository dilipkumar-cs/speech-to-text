package com.stt.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.client.LiveTranscriber;
import com.stt.config.SttProperties;
import com.stt.entity.TranscriptionSource;
import com.stt.service.TranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A server restart must not lose recordings that are still in progress. */
class LiveTranscriptionShutdownTest {

    private final TranscriptionService service = mock(TranscriptionService.class);
    private final AtomicReference<LiveTranscriber.Listener> engine = new AtomicReference<>();
    private final LiveTranscriber.Session engineSession = mock(LiveTranscriber.Session.class);
    private LiveTranscriptionWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        LiveTranscriber transcriber = new LiveTranscriber() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Session open(Listener listener) {
                engine.set(listener);
                return engineSession;
            }
        };
        handler = new LiveTranscriptionWebSocketHandler(transcriber, service, new ObjectMapper(),
                new SttProperties(List.of(), 570, null));
    }

    private static WebSocketSession browser(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void savesRecordingsInProgressAndClosesThemOnShutdown() throws Exception {
        WebSocketSession browser = browser("b1");
        handler.afterConnectionEstablished(browser);
        engine.get().onFinal("माझं नाव आहे सर दिलीप.");

        handler.shutdown();

        verify(service).save(isNull(), eq("माझं नाव आहे सर दिलीप."), eq(TranscriptionSource.GEMINI_LIVE), any());
        verify(engineSession).close();
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(browser, atLeastOnce()).sendMessage(sent.capture());
        assertThat(sent.getAllValues()).anyMatch(m -> m.getPayload().contains("server is restarting"));
        verify(browser).close(CloseStatus.NORMAL);
    }

    @Test
    void doesNotSaveEmptyOrAlreadyFinishedRecordingsOnShutdown() throws Exception {
        WebSocketSession silent = browser("silent");
        handler.afterConnectionEstablished(silent);

        WebSocketSession finished = browser("finished");
        handler.afterConnectionEstablished(finished);
        engine.get().onFinal("already saved");
        handler.afterConnectionClosed(finished, CloseStatus.NORMAL); // saved once here

        handler.shutdown();

        verify(service).save(any(), eq("already saved"), any(), any());
        verify(service, never()).save(any(), eq(""), any(), anyInt());
        verify(silent).close(CloseStatus.NORMAL);
    }
}
