package com.radar.intel.ingest;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestControllerTest {

    private static final IngestService.SyncResult SYNCED =
            new IngestService.SyncResult(859, Map.of("trending", 190, "blog", 669), Map.of());

    @Nested
    @WebMvcTest(IngestController.class)
    class WithoutASecret {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private IngestService ingest;

        @Test
        void syncIsOpenAndReportsEachSource() throws Exception {
            when(ingest.sync()).thenReturn(SYNCED);
            mvc.perform(post("/sync"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ingested").value(859))
                    .andExpect(jsonPath("$.sources.trending").value(190))
                    .andExpect(jsonPath("$.sources.blog").value(669))
                    .andExpect(jsonPath("$.failed").isEmpty());
        }

        @Test
        void aFailedSourceAnswers502WithTheSourcesThatSynced() throws Exception {
            when(ingest.sync()).thenReturn(
                    new IngestService.SyncResult(190, Map.of("trending", 190), Map.of("blog", "upstream 404")));
            mvc.perform(post("/sync"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.sources.trending").value(190))
                    .andExpect(jsonPath("$.failed.blog").value("upstream 404"));
        }
    }

    @Nested
    @WebMvcTest(IngestController.class)
    @TestPropertySource(properties = "radar.sync.secret=s3cret")
    class WithASecret {

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private IngestService ingest;

        @Test
        void theRightBearerSyncs() throws Exception {
            when(ingest.sync()).thenReturn(SYNCED);
            mvc.perform(post("/sync").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ingested").value(859));
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
        private IngestService ingest;

        @Test
        void everyCallIsRefused() throws Exception {
            mvc.perform(post("/sync")).andExpect(status().isUnauthorized());
            mvc.perform(post("/sync").header("Authorization", "Bearer ")).andExpect(status().isUnauthorized());
            verifyNoInteractions(ingest);
        }
    }
}
