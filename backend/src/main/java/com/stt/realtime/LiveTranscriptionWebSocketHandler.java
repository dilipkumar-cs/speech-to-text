package com.stt.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stt.config.SttProperties;
import com.stt.transcription.TranscriptionService;
import com.stt.transcription.TranscriptionSource;
import com.stt.transcription.dto.TranscriptionResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browser-facing WebSocket at {@code /ws/transcribe}.
 *
 * <pre>
 * client → server  binary frames : 16 kHz mono 16-bit PCM audio
 *                  text frames   : {"type":"stop","title":"optional"} | {"type":"cancel"}
 * server → client  {"type":"ready"}
 *                  {"type":"transcript","final":"…","interim":"…"}
 *                  {"type":"saved","transcription":{…}|null}
 *                  {"type":"error","message":"…"}
 * </pre>
 * When the user stops, the final transcript is stored in PostgreSQL and returned to the client.
 * If the browser disconnects unexpectedly, whatever was transcribed is still saved.
 */
@Component
public class LiveTranscriptionWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveTranscriptionWebSocketHandler.class);
    private static final String CONTEXT = "liveContext";
    /** Time to wait after audioStreamEnd for the engine to deliver its last final segment. */
    static final Duration FINALISE_GRACE = Duration.ofMillis(1500);

    private final LiveTranscriber transcriber;
    private final TranscriptionService transcriptionService;
    private final ObjectMapper mapper;
    private final Duration maxSession;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-transcription-timer");
        t.setDaemon(true);
        return t;
    });

    public LiveTranscriptionWebSocketHandler(LiveTranscriber transcriber, TranscriptionService transcriptionService,
                                             ObjectMapper mapper, SttProperties properties) {
        this.transcriber = transcriber;
        this.transcriptionService = transcriptionService;
        this.mapper = mapper;
        this.maxSession = Duration.ofSeconds(properties.maxSessionSeconds());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 5_000, 512 * 1024);
        if (!transcriber.isAvailable()) {
            send(session, error("Live transcription is not configured on the server (missing GEMINI_API_KEY)."));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        LiveContext ctx = new LiveContext(session, new TranscriptAccumulator(transcriber.cumulativeFinals()));
        rawSession.getAttributes().put(CONTEXT, ctx);
        ctx.upstream = transcriber.open(new UpstreamListener(ctx));
        ctx.timeout = scheduler.schedule(() -> finish(ctx, null), maxSession.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Live session {} started", rawSession.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        LiveContext ctx = context(session);
        if (ctx == null || ctx.upstream == null || ctx.finishing.get()) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] audio = new byte[payload.remaining()];
        payload.get(audio);
        ctx.upstream.sendAudio(audio);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        LiveContext ctx = context(session);
        if (ctx == null) {
            return;
        }
        JsonNode command = mapper.readTree(message.getPayload());
        switch (command.path("type").asText()) {
            case "stop" -> finish(ctx, command.path("title").asText(null));
            case "cancel" -> {
                ctx.finishing.set(true);
                ctx.saved.set(true); // discard: nothing will be stored
                session.close(CloseStatus.NORMAL);
            }
            default -> send(ctx.client, error("Unknown command: " + command.path("type").asText()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LiveContext ctx = context(session);
        if (ctx == null) {
            return;
        }
        if (ctx.timeout != null) {
            ctx.timeout.cancel(false);
        }
        if (ctx.upstream != null) {
            ctx.upstream.close();
        }
        persist(ctx); // no-op if already saved or cancelled
        log.info("Live session {} closed ({})", session.getId(), status);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Tells the engine the audio is over, waits briefly for the last final segment, then saves and closes. */
    private void finish(LiveContext ctx, String title) {
        if (!ctx.finishing.compareAndSet(false, true)) {
            return;
        }
        ctx.title = title;
        if (ctx.upstream != null) {
            ctx.upstream.endAudio();
        }
        scheduler.schedule(() -> {
            TranscriptionResponse saved = persist(ctx);
            ObjectNode msg = mapper.createObjectNode().put("type", "saved");
            msg.set("transcription", saved == null ? null : mapper.valueToTree(saved));
            send(ctx.client, msg);
            closeQuietly(ctx.client);
        }, FINALISE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private TranscriptionResponse persist(LiveContext ctx) {
        if (!ctx.saved.compareAndSet(false, true)) {
            return null;
        }
        String text = ctx.transcript.fullText();
        if (text.isBlank()) {
            return null;
        }
        int seconds = (int) Duration.between(ctx.startedAt, Instant.now()).toSeconds();
        try {
            return transcriptionService.save(ctx.title, text, TranscriptionSource.GEMINI_LIVE, seconds);
        } catch (RuntimeException e) {
            log.error("Failed to save live transcription", e);
            send(ctx.client, error("Transcription could not be saved: " + e.getMessage()));
            return null;
        }
    }

    private void sendTranscript(LiveContext ctx) {
        send(ctx.client, mapper.createObjectNode()
                .put("type", "transcript")
                .put("final", ctx.transcript.finalText())
                .put("interim", ctx.transcript.interim()));
    }

    private ObjectNode error(String message) {
        return mapper.createObjectNode().put("type", "error").put("message", message);
    }

    private void send(WebSocketSession session, JsonNode payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(payload.toString()));
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not send to client {}: {}", session.getId(), e.getMessage());
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static LiveContext context(WebSocketSession session) {
        return (LiveContext) session.getAttributes().get(CONTEXT);
    }

    /** Per-connection state. */
    private static final class LiveContext {
        final WebSocketSession client;
        final TranscriptAccumulator transcript;
        final Instant startedAt = Instant.now();
        final AtomicBoolean finishing = new AtomicBoolean();
        final AtomicBoolean saved = new AtomicBoolean();
        volatile LiveTranscriber.Session upstream;
        volatile ScheduledFuture<?> timeout;
        volatile String title;

        LiveContext(WebSocketSession client, TranscriptAccumulator transcript) {
            this.client = client;
            this.transcript = transcript;
        }
    }

    /** Relays engine events to the browser. */
    private final class UpstreamListener implements LiveTranscriber.Listener {
        private final LiveContext ctx;

        UpstreamListener(LiveContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onReady() {
            send(ctx.client, mapper.createObjectNode().put("type", "ready"));
        }

        @Override
        public void onInterim(String text) {
            ctx.transcript.setInterim(text);
            sendTranscript(ctx);
        }

        @Override
        public void onFinal(String text) {
            ctx.transcript.addFinal(text);
            sendTranscript(ctx);
        }

        @Override
        public void onError(String message) {
            send(ctx.client, error(message));
        }

        @Override
        public void onClosed() {
            // Upstream went away mid-recording: keep what we have and end the browser session cleanly.
            if (!ctx.finishing.get()) {
                finish(ctx, null);
            }
        }
    }
}
