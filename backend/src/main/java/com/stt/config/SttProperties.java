package com.stt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "stt")
public record SttProperties(
        List<String> allowedOrigins,
        int maxSessionSeconds,
        Gemini gemini
) {

    public SttProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (maxSessionSeconds <= 0) {
            maxSessionSeconds = 570;
        }
        if (gemini == null) {
            gemini = new Gemini(null, null, null, null, null, null);
        }
    }

    public record Gemini(
            String apiKey,
            String model,
            String endpoint,
            List<String> languageCodes,
            String mode,
            List<String> customVocabulary
    ) {
        public Gemini {
            languageCodes = clean(languageCodes);
            customVocabulary = clean(customVocabulary);
            mode = (mode == null || mode.isBlank()) ? "VERBATIM" : mode.trim().toUpperCase();
        }

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }

        private static List<String> clean(List<String> values) {
            if (values == null) {
                return List.of();
            }
            return values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
        }
    }
}
