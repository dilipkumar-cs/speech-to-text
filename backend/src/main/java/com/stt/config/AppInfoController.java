package com.stt.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which transcription engines are available on this deployment. */
@RestController
public class AppInfoController {

    private final SttProperties properties;

    public AppInfoController(SttProperties properties) {
        this.properties = properties;
    }

    public record AppInfo(boolean geminiEnabled, String geminiModel, int maxSessionSeconds) {
    }

    @GetMapping("/api/info")
    public AppInfo info() {
        SttProperties.Gemini gemini = properties.gemini();
        return new AppInfo(gemini.enabled(), gemini.model(), properties.maxSessionSeconds());
    }
}
