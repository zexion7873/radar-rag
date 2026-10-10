package com.radar.intel.ingest;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestControllerTest {

    @Nested
    @WebMvcTest(IngestController.class)
    class WithoutASecret {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private TrendingIngestService ingest;

        @Test
        void syncIsOpen() throws Exception {
            when(ingest.sync()).thenReturn(190);
            mvc.perform(post("/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.ingested").value(190));
        }

        @Test
        void aNotionErrorBodyStaysOutOfTheResponse() throws Exception {
            when(ingest.sync()).thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                    null, "notion-body-must-not-leak".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            mvc.perform(post("/sync"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("upstream 401"))
                    .andExpect(content().string(not(containsString("notion-body-must-not-leak"))));
        }
    }

    @Nested
    @WebMvcTest(IngestController.class)
    @TestPropertySource(properties = "radar.sync.secret=s3cret")
    class WithASecret {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private TrendingIngestService ingest;

        @Test
        void theRightBearerSyncs() throws Exception {
            when(ingest.sync()).thenReturn(190);
            mvc.perform(post("/sync").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ingested").value(190));
        }

        @Test
        void noHeaderIsRefused() throws Exception {
            mvc.perform(post("/sync")).andExpect(status().isUnauthorized());
            verifyNoInteractions(ingest);
        }

        @Test
        void aWrongBearerIsRefused() throws Exception {
            mvc.perform(post("/sync").header("Authorization", "Bearer s3cre")).andExpect(status().isUnauthorized());
            mvc.perform(post("/sync").header("Authorization", "s3cret")).andExpect(status().isUnauthorized());
            verifyNoInteractions(ingest);
        }
    }

    @Nested
    @WebMvcTest(IngestController.class)
    @TestPropertySource(properties = "radar.sync.secret=")
    class WithABlankSecret {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private TrendingIngestService ingest;

        @Test
        void everyCallIsRefused() throws Exception {
            mvc.perform(post("/sync")).andExpect(status().isUnauthorized());
            mvc.perform(post("/sync").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
            verifyNoInteractions(ingest);
        }
    }
}
