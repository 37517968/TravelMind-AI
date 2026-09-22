package com.travelmind.aiagent.knowledge;

import com.travelmind.aiagent.knowledge.pipeline.TravelSemanticChunker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TravelSemanticChunkerTest {
    @Test
    void shouldPreferDayAndHeadingBoundaries() {
        TravelSemanticChunker chunker = new TravelSemanticChunker(200, 20);
        String content = "# 杭州三日游\n" + "总体说明。".repeat(30)
                + "\n\n第一天 西湖\n" + "游览断桥和苏堤。".repeat(30)
                + "\n\n第二天 灵隐寺\n" + "上午错峰参观。".repeat(30);

        var chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> !chunk.isBlank());
        assertThat(String.join("", chunks)).contains("第一天 西湖", "第二天 灵隐寺");
    }
}
