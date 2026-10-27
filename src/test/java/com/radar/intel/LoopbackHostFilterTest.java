package com.radar.intel;

import com.radar.intel.ingest.IngestController;
import com.radar.intel.ingest.IngestService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoopbackHostFilterTest {

    private static final IngestService.SyncResult SYNCED =
            new IngestService.SyncResult(1, Map.of("trending", 1), Map.of());

    @Nested
    @WebMvcTest(IngestController.class)
    class OutsideProd {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private IngestService ingest;

        @ParameterizedTest
        @ValueSource(strings = {"localhost:8080", "LOCALHOST", "127.0.0.1:8080", "[::1]:8080", "[::1]"})
        void aLoopbackHostPasses(String host) throws Exception {
            when(ingest.sync()).thenReturn(SYNCED);
            mvc.perform(post("/sync").header("Host", host)).andExpect(status().isOk());
        }

        // A DNS-rebinding page reaches 127.0.0.1 under its own name, and Spring's CORS check reads an
        // Origin equal to that Host as same-origin.
        @ParameterizedTest
        @ValueSource(strings = {"rebind.attacker.test:8080", "localhost.attacker.test", "127.0.0.1.nip.io:8080",
                "[::2]:8080", ""})
        void anyOtherHostIsRefused(String host) throws Exception {
            mvc.perform(post("/sync").header("Host", host)).andExpect(status().isForbidden());
            verifyNoInteractions(ingest);
        }
    }

    @Nested
    @WebMvcTest(IngestController.class)
    @ActiveProfiles("prod")
    @TestPropertySource(properties = "SYNC_SECRET=s3cret")
    class InProd {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private IngestService ingest;

        @Test
        void thePublicHostPasses() throws Exception {
            when(ingest.sync()).thenReturn(SYNCED);
            mvc.perform(post("/sync").header("Host", "radar-rag-50472171523.asia-east1.run.app")
                            .header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk());
        }
    }
}
