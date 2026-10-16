package com.radar.intel.search;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordsTest {

    @Test
    void chineseIsPairedOnceItsFillerIsOutAndEnglishLosesItsPlural() {
        assertThat(Keywords.terms("有沒有AI關於醫療領域的相關文章?")).containsExactly("ai", "醫療", "療領", "領域");
        assertThat(Keywords.terms("Are there chips made for AI companies?"))
                .containsExactly("chip", "made", "ai", "company");
        assertThat(Keywords.terms("GLM-5.3 status")).containsExactly("glm-5.3", "status");
    }

    @Test
    void aRareWordOutweighsOneEveryRowShares() {
        Keywords k = new Keywords(Map.of(
                "chip", "OpenAI 發表 AI 推論晶片",
                "agent", "AI agent 框架",
                "eval", "AI eval 方法"));
        Map<String, Double> scores = k.scores("現在有針對AI做的晶片嗎？");
        assertThat(scores.get("chip")).isGreaterThan(scores.get("agent"));
        assertThat(k.scores("量子電腦")).isEmpty();
    }
}
