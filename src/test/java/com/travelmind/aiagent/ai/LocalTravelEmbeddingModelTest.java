package com.travelmind.aiagent.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LocalTravelEmbeddingModelTest {

    private final LocalTravelEmbeddingModel model = new LocalTravelEmbeddingModel();

    @Test
    void shouldProduceDeterministicNormalizedEmbedding() {
        float[] first = model.embed("上海亲子旅行与博物馆");
        float[] second = model.embed("上海亲子旅行与博物馆");

        assertThat(first).hasSize(128).containsExactly(second);

        double norm = 0.0;
        for (float value : first) {
            norm += value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void shouldReturnZeroVectorForBlankText() {
        assertThat(model.embed("   ")).hasSize(128).containsOnly(0.0f);
    }
}
