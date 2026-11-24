package com.stt.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SttPropertiesTest {

    @Test
    void appliesDefaultsWhenNothingIsConfigured() {
        SttProperties props = new SttProperties(null, 0, null);

        assertThat(props.allowedOrigins()).isEmpty();
        assertThat(props.maxSessionSeconds()).isEqualTo(570);
        assertThat(props.gemini().enabled()).isFalse();
        assertThat(props.gemini().mode()).isEqualTo("VERBATIM");
        assertThat(props.gemini().languageCodes()).isEmpty();
        assertThat(props.gemini().customVocabulary()).isEmpty();
    }

    @Test
    void keepsExplicitValuesAndNormalisesThem() {
        SttProperties props = new SttProperties(List.of("http://localhost:5173"), 120,
                new SttProperties.Gemini("key", "models/x", "wss://x", Arrays.asList(" hi-IN", "", "kn-IN "),
                        " smart ", List.of(" Procucev ", " ")));

        assertThat(props.allowedOrigins()).containsExactly("http://localhost:5173");
        assertThat(props.maxSessionSeconds()).isEqualTo(120);
        assertThat(props.gemini().enabled()).isTrue();
        assertThat(props.gemini().mode()).isEqualTo("SMART");
        assertThat(props.gemini().languageCodes()).containsExactly("hi-IN", "kn-IN");
        assertThat(props.gemini().customVocabulary()).containsExactly("Procucev");
    }

    @Test
    void blankApiKeyMeansTheEngineIsDisabled() {
        assertThat(new SttProperties.Gemini("  ", null, null, null, null, null).enabled()).isFalse();
    }
}
