package com.stt.controller;

import com.stt.config.SttProperties;
import com.stt.dto.AppInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which transcription engines are available on this deployment. */
@RestController
public class AppInfoController {

    private final SttProperties properties;

    public AppInfoController(SttProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/info")
    public AppInfoResponse info() {
        SttProperties.Gemini gemini = properties.gemini();
        return new AppInfoResponse(gemini.enabled(), gemini.model(), properties.maxSessionSeconds());
    }
}
