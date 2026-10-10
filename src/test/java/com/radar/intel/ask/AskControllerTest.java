package com.radar.intel.ask;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AskControllerTest {

    private static final Document TRENDING = new Document("00000000-0000-4000-8000-000000000001", "t",
            Map.of("source", "trending", "repo", "o/repo", "url", "https://github.com/o/repo", "week", "2026-09-21"));
    private static final Document BLOG = new Document("00000000-0000-4000-8000-000000000002", "b",
            Map.of("source", "blog", "title", "A post", "url", "https://x.test/p", "week", "2026-09-30"));

    @Test
    void aCitationDocumentIsTitledByItsRepoOrElseItsPostTitle() {
        assertThat(AskController.citationDocument(TRENDING).toDocumentBlockParam().title())
                .contains("o/repo 2026-09-21");
        assertThat(AskController.citationDocument(BLOG).toDocumentBlockParam().title())
                .contains("A post 2026-09-30");
    }

    @Test
    void aCitationDocumentTellsTheModelHowLongItsRepoCharted() {
        Document charted = new Document(TRENDING.getId(), "t", Map.of("source", "trending", "repo", "o/repo",
                "weeks_on_chart", 3, "first_week", "2026-06-18", "last_week", "2026-07-06"));

        assertThat(AskController.citationDocument(charted).toDocumentBlockParam().source().asText().data())
                .isEqualTo("t\n\nCharted in 3 week(s): first 2026-06-18, last 2026-07-06.");
        assertThat(AskController.citationDocument(BLOG).toDocumentBlockParam().source().asText().data())
                .isEqualTo("b");
    }

    @Test
    void aSourceNamesItsOriginAndTitle() {
        AskController.Source blog = AskController.source(BLOG);

        assertThat(blog.source()).isEqualTo("blog");
        assertThat(blog.title()).isEqualTo("A post");
        assertThat(blog.repo()).isNull();
        assertThat(AskController.source(TRENDING).repo()).isEqualTo("o/repo");
    }
}
