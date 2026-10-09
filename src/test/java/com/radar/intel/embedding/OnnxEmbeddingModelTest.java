package com.radar.intel.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxEmbeddingModelTest {

    @Test
    void meanPoolAveragesOnlyTheTokensTheMaskKeeps() {
        float[][] tokens = {{1f, 2f}, {3f, 4f}, {100f, 100f}};

        assertThat(OnnxEmbeddingModel.meanPool(tokens, new long[] {1, 1, 0})).containsExactly(2f, 3f);
    }

    @Test
    void cascadeSumAddsEachBlockOf16RowsBeforeTheRunningTotal() {
        // At 1e8 a float's spacing is 8, so a running sum drops every +1 and ends at 1e8. ATen sums
        // rows 16-31 as their own block (16) before adding it, which survives: 1e8 + 16.
        float[][] rows = new float[32][1];
        rows[0][0] = 1e8f;
        for (int i = 1; i < 32; i++) {
            rows[i][0] = 1f;
        }

        assertThat(OnnxEmbeddingModel.cascadeSum(rows)).containsExactly(100_000_016f);
    }

    @Test
    void anAllPaddingRowPoolsToZeroInsteadOfNaN() {
        float[][] tokens = {{5f, 7f}};

        assertThat(OnnxEmbeddingModel.meanPool(tokens, new long[] {0})).containsExactly(0f, 0f);
    }
}
