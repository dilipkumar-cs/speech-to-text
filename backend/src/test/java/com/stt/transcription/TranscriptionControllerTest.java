package com.stt.transcription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranscriptionControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    TranscriptionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void fullCrudLifecycle() throws Exception {
        // create
        String body = mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Standup\",\"content\":\"Aaj ka standup 10 baje hai\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("Standup"))
                .andExpect(jsonPath("$.languages").value("hi+en"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.wordCount").value(6))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("id").asLong();

        // read
        mvc.perform(get("/api/transcriptions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Aaj ka standup 10 baje hai"));

        // update
        mvc.perform(put("/api/transcriptions/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Daily standup\",\"content\":\"The standup is at 10 am\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Daily standup"))
                .andExpect(jsonPath("$.languages").value("en"));

        // list + search
        mvc.perform(get("/api/transcriptions").param("q", "STANDUP"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/transcriptions").param("q", "nothing-matches"))
                .andExpect(jsonPath("$.totalItems").value(0));

        // delete
        mvc.perform(delete("/api/transcriptions/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/transcriptions/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void listIsNewestFirstAndPaginated() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"t" + i + "\",\"content\":\"text " + i + "\"}"));
        }
        String json = mvc.perform(get("/api/transcriptions").param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode items = mapper.readTree(json).path("items");
        org.assertj.core.api.Assertions.assertThat(items.get(0).path("title").asText()).isEqualTo("t3");

        mvc.perform(get("/api/transcriptions/stats"))
                .andExpect(jsonPath("$.totalTranscriptions").value(3));
    }

    @Test
    void rejectsBlankContent() throws Exception {
        mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.content").exists());
    }

    @Test
    void generatesTitleWhenMissing() throws Exception {
        mvc.perform(post("/api/transcriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("Recording")));
    }
}
