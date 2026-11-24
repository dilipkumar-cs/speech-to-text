package com.stt.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppInfoAndSpaControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void infoReportsEngineAvailabilityModelAndLimit() throws Exception {
        mvc.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geminiEnabled").value(true))
                .andExpect(jsonPath("$.geminiModel").value("models/gemini-3.5-transcribe-live"))
                .andExpect(jsonPath("$.maxSessionSeconds").value(570));
    }

    @Test
    void clientSideRoutesAreForwardedToTheReactApp() throws Exception {
        mvc.perform(get("/history")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/live")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void healthEndpointIsExposedForTheCloudHealthCheck() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
