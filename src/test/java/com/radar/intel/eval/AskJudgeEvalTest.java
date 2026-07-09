package com.radar.intel.eval;

import com.radar.intel.ask.AskController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 LLM-as-judge eval: run the real /ask flow (controller bean, production advisor
 * chain) over the fixture corpus and have Claude judge whether each answer is relevant
 * to the question and the retrieved context (Spring AI's RelevancyEvaluator).
 *
 * <p>Needs ANTHROPIC_API_KEY — the same boundary as /ask itself. Without the key the
 * test is skipped, so the keyless retrieval gate still runs everywhere; with the key
 * (local shell or repo secret) this becomes a second CI gate.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AskJudgeEvalTest extends EvalSupport {

    /** 3 of 4 must pass — one flaky judge verdict must not block the build. */
    private static final double PASS_RATE_FLOOR = 0.75;

    @Autowired
    private AskController askController;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void askAnswersJudgedRelevant() {
        RelevancyEvaluator judge = new RelevancyEvaluator(chatClientBuilder);
        Map<String, Document> byUrl = corpusByUrl();
        List<JudgeCase> cases = golden().judge();

        int passed = 0;
        StringBuilder sb = new StringBuilder(String.format("%n## LLM-as-judge eval (%d cases)%n", cases.size()));
        for (JudgeCase c : cases) {
            AskController.AskResponse resp = askController.ask(new AskController.AskRequest(c.q()));
            // The citations ARE the retrieved set; map them back to fixture texts for the judge.
            List<Document> context = resp.citations().stream()
                    .map(cit -> byUrl.get(cit.url()))
                    .filter(Objects::nonNull)
                    .toList();
            EvaluationResponse verdict = judge.evaluate(
                    new EvaluationRequest(c.q(), context, resp.answer()));
            if (verdict.isPass()) {
                passed++;
            }
            sb.append(String.format("%n[%s] %s%n  answer:   %s%n  feedback: %s%n",
                    verdict.isPass() ? "PASS" : "FAIL", c.q(), resp.answer(), verdict.getFeedback()));
        }
        System.out.println(sb);

        double passRate = (double) passed / cases.size();
        assertThat(passRate)
                .as(String.format(Locale.ROOT, "judge pass rate (%d/%d)", passed, cases.size()))
                .isGreaterThanOrEqualTo(PASS_RATE_FLOOR);
    }
}
