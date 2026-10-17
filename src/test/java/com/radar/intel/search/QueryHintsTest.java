package com.radar.intel.search;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class QueryHintsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            最近 apps 類有哪些值得看的 repo？        | apps    | true
            有關於agent類型的套件嗎？              | agents  | false
            infra 類別最近有什麼？                 | infra   | true
            Anything new in the infra category?   | infra   | false
            分類是 models 的有哪些                 | models  | false
            工具類的 repo 有推薦嗎                 | tooling | false
            最近上榜的有哪些？                     | -       | true
            最近有什麼紅的tools套件嗎？             | -       | true
            What are the latest repos?            | -       | true
            """)
    void aCategoryCountsOnlyWhenTheQuestionNamesItAsOne(String q, String category, boolean recent) {
        assertThat(QueryHints.of(q)).isEqualTo(new QueryHints(category, recent));
    }

    // A single-category filter would return the rows the question excludes, or one of the two it compares.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            不要 agents 類，其他都可以
            除了 apps 類以外還有什麼？
            Which repos are not in the agents category?
            Anything except the tools category?
            比較 apps 類和 models 類的 repo
            Compare the apps category with the models category.
            """)
    void anExcludedOrSecondCategoryIsNoFilter(String q) {
        assertThat(QueryHints.of(q).category()).isNull();
    }

    // golden_v1 asks about topics with these words; reading them as categories would filter its answers away.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            有啥能把文件轉成給agent看的套件？
            有讓agent跑沙盒環境的套件嗎？
            有能統一管理Agent的工具嗎？
            Any tools for weather forecasting?
            Any packages for running models locally? My computer isn't very powerful though.
            Any packages that give an agent long-term or shared memory?
            還有其他類似 agent 類似的工具嗎？
            Are there articles about AI and smart home appliances?
            """)
    void aTopicWordIsNotACategory(String q) {
        assertThat(QueryHints.of(q)).isEqualTo(new QueryHints(null, false));
    }

    // Only trending holds repos; golden_v2's blog questions ask for 文章 / articles and must keep their lead.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            有剪片的套件嗎？                                  | true
            Any packages for editing videos?                 | true
            最近 apps 類有哪些值得看的 repo？                  | true
            Is there a library for parsing PDFs?             | true
            有能統一管理Agent的工具嗎？                        | false
            Any tools for weather forecasting?               | false
            有沒有 AI 晶片的相關文章？                          | false
            Are there articles about open-source models?     | false
            Which projects use Rust?                         | false
            """)
    void aQuestionAsksForReposOnlyByAWordForOne(String q, boolean repos) {
        assertThat(QueryHints.asksForRepos(q)).isEqualTo(repos);
    }
}
