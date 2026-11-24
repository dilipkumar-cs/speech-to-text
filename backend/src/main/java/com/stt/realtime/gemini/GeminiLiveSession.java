package com.stt.realtime.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stt.realtime.LiveTranscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One live transcription session with Gemini.
 * <p>
 * {@link WebSocket} allows only one outstanding send at a time, so every outgoing message is chained onto
 * {@link #sendChain}. Audio that arrives before the server acknowledges the setup message is held back and
 * flushed in order once it does.
 */
final class GeminiLiveSession implements LiveTranscriber.Session, WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveSession.class);
    private static final String AUDIO_MIME = "audio/pcm;rate=16000";
    private static final String AUDIO_END = "{\"realtimeInput\":{\"audioStreamEnd\":true}}";
    /** If the server never sends setupComplete, start streaming anyway after this delay. */
    private static final Duration SETUP_FALLBACK = Duration.ofSeconds(3);
    /** ~60 s of 100 ms chunks; protects memory if the upstream connection stalls. */
    private static final int MAX_PENDING = 600;

    private final ObjectMapper mapper;
    private final LiveTranscriber.Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closedNotified = new AtomicBoolean();

    private final StringBuilder textFrame = new StringBuilder();
    private final ByteArrayOutputStream binaryFrame = new ByteArrayOutputStream();

    // guarded by this
    private final List<String> pending = new ArrayList<>();
    private CompletableFuture<WebSocket> sendChain;

    GeminiLiveSession(ObjectMapper mapper, LiveTranscriber.Listener listener) {
        this.mapper = mapper;
        this.listener = listener;
    }

    void connect(HttpClient client, URI uri, String setupMessage) {
        CompletableFuture<WebSocket> connected = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .thenCompose(ws -> ws.sendText(setupMessage, true));
        synchronized (this) {
            sendChain = connected;
        }
        connected.whenComplete((ws, ex) -> {
            if (ex != null) {
                fail("Could not connect to Gemini Live API: " + rootMessage(ex));
            }
        });
        CompletableFuture.delayedExecutor(SETUP_FALLBACK.toMillis(), TimeUnit.MILLISECONDS)
                .execute(this::markReady);
    }

    @Override
    public void sendAudio(byte[] pcm16) {
        if (pcm16.length == 0) {
            return;
        }
        String message = "{\"realtimeInput\":{\"audio\":{\"data\":\"" + Base64.getEncoder().encodeToString(pcm16)
                + "\",\"mimeType\":\"" + AUDIO_MIME + "\"}}}";
        sendOrBuffer(message);
    }

    @Override
    public void endAudio() {
        sendOrBuffer(AUDIO_END);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<WebSocket> chain;
        synchronized (this) {
            pending.clear();
            chain = sendChain;
        }
        if (chain != null) {
            chain.thenCompose(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete((ws, ex) -> {
                        if (ex != null) {
                            chain.thenAccept(WebSocket::abort);
                        }
                    });
        }
    }

    private void sendOrBuffer(String message) {
        if (closed.get()) {
            return;
        }
        synchronized (this) {
            if (!ready.get()) {
                if (pending.size() < MAX_PENDING) {
                    pending.add(message);
                }
                return;
            }
            enqueue(message);
        }
    }

    /** Must be called while holding the monitor. */
    private void enqueue(String message) {
        sendChain = sendChain.thenCompose(ws -> ws.sendText(message, true));
    }

    private void markReady() {
        if (closed.get() || !ready.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            pending.forEach(this::enqueue);
            pending.clear();
        }
        listener.onReady();
    }

    // ---------------------------------------------------------------- WebSocket.Listener (incoming)

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textFrame.append(data);
        if (last) {
            String json = textFrame.toString();
            textFrame.setLength(0);
            handleMessage(json);
        }
        webSocket.request(1);
        return null;
    }

    /** The Live API usually delivers its JSON messages as binary frames. */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binaryFrame.writeBytes(bytes);
        if (last) {
            String json = binaryFrame.toString(StandardCharsets.UTF_8);
            binaryFrame.reset();
            handleMessage(json);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!closed.get() && statusCode != WebSocket.NORMAL_CLOSURE) {
            listener.onError("Gemini closed the session (" + statusCode + "): " + reason);
        }
        closed.set(true);
        notifyClosed();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        fail("Gemini connection error: " + rootMessage(error));
    }

    void handleMessage(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            log.debug("Ignoring non-JSON message from Gemini: {}", json);
            return;
        }
        if (root.has("setupComplete")) {
            markReady();
        }
        JsonNode content = root.path("serverContent");
        if (!content.isMissingNode()) {
            // Any transcription also proves the session is live.
            String interim = content.path("interimInputTranscription").path("text").asText(null);
            if (interim != null) {
                markReady();
                listener.onInterim(interim);
            }
            String fin = content.path("inputTranscription").path("text").asText(null);
            if (fin != null) {
                markReady();
                listener.onFinal(fin);
            }
        }
        if (root.has("goAway")) {
            log.info("Gemini sent goAway: {}", root.get("goAway"));
        }
        if (root.has("error")) {
            listener.onError("Gemini error: " + root.get("error").path("message").asText(root.get("error").toString()));
        }
    }

    private void fail(String message) {
        log.warn(message);
        if (closed.compareAndSet(false, true)) {
            listener.onError(message);
        }
        notifyClosed();
    }

    private void notifyClosed() {
        if (closedNotified.compareAndSet(false, true)) {
            listener.onClosed();
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
