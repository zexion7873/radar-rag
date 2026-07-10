package com.radar.intel.eval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 retrieval eval: the golden queries against the fixture corpus, scored with
 * precision@K / recall@K / MRR. Fully keyless (embeddings are local ONNX), so this is
 * the hard CI gate: an embedding-model swap or retrieval-path change that regresses
 * below the floors fails the build. The report also splits the means by query language,
 * because the corpus is zh-TW while all-MiniLM-L6-v2 is English-centric — that split is
 * the before/after evidence for any future multilingual-model swap.
 */
class RetrievalEvalTest extends EvalSupport {

    private static final int K = 5;

    // Floors, not targets: set one-query's-worth below the observed baseline so real
    // regressions fail the gate while ranking jitter does not. Baseline (2026-07-10,
    // all-MiniLM-L6-v2): mean R@5 0.73 / MRR 0.52 — en 0.74/0.59, zh 0.71/0.44, with
    // pure-Chinese queries (no latin tokens) scoring 0. That gap is the standing case
    // for a multilingual embedding model; raise these floors when one lands.
    private static final double MEAN_RECALL_FLOOR = 0.65;
    private static final double MEAN_MRR_FLOOR = 0.40;

    private record Scored(GoldenQuery query, double precision, double recall, double mrr) {
    }

    @Test
    void goldenSetRetrievalStaysAboveFloors() {
        List<Scored> rows = new ArrayList<>();
        for (GoldenQuery gq : golden().retrieval()) {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(gq.q()).topK(K).build());
            List<String> urls = hits.stream()
                    .map(d -> (String) d.getMetadata().get("url"))
                    .toList();
            rows.add(score(gq, urls));
        }
        report(rows);

        double meanRecall = mean(rows, Scored::recall);
        double meanMrr = mean(rows, Scored::mrr);
        assertThat(meanRecall)
                .as("mean recall@%d over the golden set", K)
                .isGreaterThanOrEqualTo(MEAN_RECALL_FLOOR);
        assertThat(meanMrr)
                .as("mean MRR over the golden set")
                .isGreaterThanOrEqualTo(MEAN_MRR_FLOOR);
    }

    private static Scored score(GoldenQuery gq, List<String> retrieved) {
        // Count distinct RELEVANT urls that were retrieved, not raw hits: the corpus
        // intentionally embeds one url under two sources, so filtering the retrieved
        // list would double-count and push recall past 1.0.
        long found = gq.relevant().stream().filter(retrieved::contains).count();
        double precision = (double) found / K;
        double recall = (double) found / gq.relevant().size();
        double mrr = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            if (gq.relevant().contains(retrieved.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        return new Scored(gq, precision, recall, mrr);
    }

    private static void report(List<Scored> rows) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%n## Retrieval eval (K=%d, %d queries)%n%n| lang | query | P@%d | R@%d | MRR |%n|---|---|---|---|---|%n",
                K, rows.size(), K, K));
        for (Scored r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %.2f | %.2f | %.2f |%n",
                    r.query().lang(), r.query().q(), r.precision(), r.recall(), r.mrr()));
        }
        sb.append(String.format(Locale.ROOT, "%nmean(all): P@%d %.2f / R@%d %.2f / MRR %.2f%n",
                K, mean(rows, Scored::precision), K, mean(rows, Scored::recall), mean(rows, Scored::mrr)));
        for (String lang : List.of("en", "zh")) {
            List<Scored> subset = rows.stream().filter(r -> lang.equals(r.query().lang())).toList();
            sb.append(String.format(Locale.ROOT, "mean(%s):  P@%d %.2f / R@%d %.2f / MRR %.2f%n",
                    lang, K, mean(subset, Scored::precision), K, mean(subset, Scored::recall),
                    mean(subset, Scored::mrr)));
        }
        System.out.println(sb);
    }

    private static double mean(List<Scored> rows, java.util.function.ToDoubleFunction<Scored> metric) {
        return rows.stream().mapToDouble(metric).average().orElse(0);
    }
}
