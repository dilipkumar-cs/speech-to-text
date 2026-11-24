package com.stt.config;

import com.stt.realtime.LiveTranscriptionWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    private final SttProperties properties;
    private final LiveTranscriptionWebSocketHandler liveHandler;

    public WebConfig(SttProperties properties, LiveTranscriptionWebSocketHandler liveHandler) {
        this.properties = properties;
        this.liveHandler = liveHandler;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin requests are always accepted; extra origins (e.g. Vite dev server) come from config.
        registry.addHandler(liveHandler, "/ws/transcribe").setAllowedOriginPatterns(origins());
    }

    /** Raise the default 8 KB frame limit so ~100 ms PCM chunks and control messages always fit. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(256 * 1024);
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(60_000L);
        return container;
    }

    private String[] origins() {
        return properties.allowedOrigins().toArray(String[]::new);
    }
}
