package com.stt.client.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.client.LiveTranscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiLiveSessionTest {

    private static final URI URI_ = URI.create("wss://example/ws");
    private static final String SETUP = "{\"setup\":{}}";
    private static final Duration NO_FALLBACK = Duration.ofMinutes(10);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingListener listener = new RecordingListener();

    private HttpClient httpClient;
    private WebSocket.Builder builder;
    private WebSocket ws;

    @BeforeEach
    void setUpFakeConnection() {
        httpClient = mock(HttpClient.class);
        builder = mock(WebSocket.Builder.class);
        ws = mock(WebSocket.class);
        when(httpClient.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(ws));
        when(ws.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(ws));
        when(ws.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(ws));
    }

    private GeminiLiveSession connected(Duration fallback) {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener, fallback);
        session.connect(httpClient, URI_, SETUP);
        return session;
    }

    // ------------------------------------------------------------------ incoming messages

    @Test
    void parsesInterimAndFinalTranscriptions() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.handleMessage("{\"setupComplete\":{}}");
        session.handleMessage("{\"serverContent\":{\"interimInputTranscription\":{\"text\":\"mujhe kal\"}}}");
        session.handleMessage("{\"serverContent\":{\"inputTranscription\":{\"text\":\"मुझे कल meeting में जाना है\"}}}");
        session.handleMessage("{\"serverContent\":{\"turnComplete\":true}}");

        assertThat(listener.events).containsExactly(
                "ready", "interim:mujhe kal", "final:मुझे कल meeting में जाना है");
    }

    @Test
    void reportsServerErrorsAndIgnoresGarbageAndGoAway() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.handleMessage("not json");
        session.handleMessage("{\"goAway\":{\"timeLeft\":\"5s\"}}");
        session.handleMessage("{\"error\":{\"message\":\"quota exceeded\"}}");
        assertThat(listener.events).containsExactly("error:Gemini error: quota exceeded");
    }

    @Test
    void aTranscriptAlsoMarksTheSessionReadyOnlyOnce() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.handleMessage("{\"serverContent\":{\"inputTranscription\":{\"text\":\"hi\"}}}");
        session.handleMessage("{\"setupComplete\":{}}");
        assertThat(listener.events).containsExactly("ready", "final:hi");
    }

    @Test
    void joinsFragmentedTextFrames() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.onOpen(ws);
        session.onText(ws, "{\"serverContent\":{\"inputTranscription\"", false);
        session.onText(ws, ":{\"text\":\"hello\"}}}", true);

        assertThat(listener.events).containsExactly("ready", "final:hello");
        verify(ws, times(3)).request(1);
    }

    @Test
    void decodesFragmentedBinaryFramesAsUtf8() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        byte[] json = "{\"serverContent\":{\"inputTranscription\":{\"text\":\"ನಮಸ್ಕಾರ\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        int split = json.length / 2;
        session.onBinary(ws, ByteBuffer.wrap(json, 0, split), false);
        session.onBinary(ws, ByteBuffer.wrap(json, split, json.length - split), true);

        assertThat(listener.events).containsExactly("ready", "final:ನಮಸ್ಕಾರ");
    }

    // ------------------------------------------------------------------ outgoing messages

    @Test
    void sendsSetupFirstAndHoldsAudioUntilSetupComplete() {
        GeminiLiveSession session = connected(NO_FALLBACK);
        verify(builder).buildAsync(eq(URI_), eq(session));

        session.sendAudio(new byte[]{1, 2});
        verify(ws, times(1)).sendText(anyString(), anyBoolean()); // only the setup so far

        session.handleMessage("{\"setupComplete\":{}}");

        InOrder order = inOrder(ws);
        order.verify(ws).sendText(SETUP, true);
        order.verify(ws).sendText(
                "{\"realtimeInput\":{\"audio\":{\"data\":\"AQI=\",\"mimeType\":\"audio/pcm;rate=16000\"}}}", true);
        assertThat(listener.events).containsExactly("ready");
    }

    @Test
    void startsStreamingAfterFallbackWhenSetupCompleteNeverArrives() {
        GeminiLiveSession session = connected(Duration.ofMillis(50));
        session.sendAudio(new byte[]{1, 2});

        verify(ws, timeout(2000)).sendText(contains("AQI="), eq(true));
        assertThat(listener.events).contains("ready");
    }

    @Test
    void sendsAudioImmediatelyOnceReadyAndSignalsEndOfAudio() {
        GeminiLiveSession session = connected(NO_FALLBACK);
        session.handleMessage("{\"setupComplete\":{}}");

        session.sendAudio(new byte[]{9});
        session.sendAudio(new byte[0]); // ignored
        session.endAudio();

        InOrder order = inOrder(ws);
        order.verify(ws).sendText(SETUP, true);
        order.verify(ws).sendText(contains("\"data\":\"CQ==\""), eq(true));
        order.verify(ws).sendText("{\"realtimeInput\":{\"audioStreamEnd\":true}}", true);
        verify(ws, times(3)).sendText(anyString(), anyBoolean());
    }

    @Test
    void capsAudioBufferedBeforeSetupAt600Chunks() {
        GeminiLiveSession session = connected(NO_FALLBACK);
        for (int i = 0; i < 700; i++) {
            session.sendAudio(new byte[]{1});
        }
        session.handleMessage("{\"setupComplete\":{}}");

        verify(ws, times(600)).sendText(contains("realtimeInput"), eq(true));
    }

    @Test
    void closeSendsNormalClosureOnceAndIgnoresLaterAudio() {
        GeminiLiveSession session = connected(NO_FALLBACK);
        session.handleMessage("{\"setupComplete\":{}}");

        session.close();
        session.close();
        session.sendAudio(new byte[]{1});
        session.endAudio();

        verify(ws, times(1)).sendClose(WebSocket.NORMAL_CLOSURE, "done");
        verify(ws, times(1)).sendText(anyString(), anyBoolean()); // just the setup
    }

    @Test
    void closeBeforeConnectingIsHarmless() {
        new GeminiLiveSession(mapper, listener).close();
        assertThat(listener.events).isEmpty();
    }

    // ------------------------------------------------------------------ failures

    @Test
    void connectionFailureReportsErrorAndClosedOnce() {
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new IOException("boom")));

        GeminiLiveSession session = connected(NO_FALLBACK);
        session.sendAudio(new byte[]{1});

        assertThat(listener.events).containsExactly("error:Could not connect to Gemini Live API: boom", "closed");
        verify(ws, never()).sendText(anyString(), anyBoolean());
    }

    @Test
    void unexpectedCloseFromServerIsReportedAsError() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.onClose(ws, 1007, "API key not valid");
        session.onClose(ws, 1007, "again");

        assertThat(listener.events).containsExactly(
                "error:Gemini closed the session (1007): API key not valid", "closed");
    }

    @Test
    void normalCloseFromServerIsNotAnError() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.onClose(ws, WebSocket.NORMAL_CLOSURE, "");
        assertThat(listener.events).containsExactly("closed");
    }

    @Test
    void transportErrorIsReportedWithRootCause() {
        GeminiLiveSession session = new GeminiLiveSession(mapper, listener);
        session.onError(ws, new RuntimeException("wrapper", new IOException("connection reset")));
        session.onError(ws, new IOException()); // second failure: no duplicate events

        assertThat(listener.events).containsExactly("error:Gemini connection error: connection reset", "closed");
    }

    @Test
    void closingOurselvesSuppressesTheServerCloseError() {
        GeminiLiveSession session = connected(NO_FALLBACK);
        session.close();
        session.onClose(ws, 1011, "internal");

        assertThat(listener.events).containsExactly("closed");
    }

    private static final class RecordingListener implements LiveTranscriber.Listener {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void onReady() { events.add("ready"); }
        @Override public void onInterim(String text) { events.add("interim:" + text); }
        @Override public void onFinal(String text) { events.add("final:" + text); }
        @Override public void onError(String message) { events.add("error:" + message); }
        @Override public void onClosed() { events.add("closed"); }
    }
}
