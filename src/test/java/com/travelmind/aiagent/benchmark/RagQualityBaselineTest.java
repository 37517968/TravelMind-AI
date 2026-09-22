package com.travelmind.aiagent.benchmark;

import com.travelmind.aiagent.ai.LocalTravelEmbeddingModel;
import com.travelmind.aiagent.model.entity.TravelKnowledgeEntity;
import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import com.travelmind.aiagent.rag.TravelKnowledgeEntityLoader;
import com.travelmind.aiagent.rag.TravelKnowledgeIndexService;
import com.travelmind.aiagent.service.TravelPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 0 的可重复离线基线。
 *
 * 该测试只评价当前 JVM 内检索器和本地 hash embedding，不能代表生产 PGVector/ES 的效果。
 * 后续引入真实混合检索后应保留同一批样例做前后对比。
 */
class RagQualityBaselineTest {

    @Test
    void shouldMeetInitialRecallAtThreeBaseline() {
        TravelKnowledgeEntityLoader loader = mock(TravelKnowledgeEntityLoader.class);
        TravelPlanService planService = mock(TravelPlanService.class);
        TravelKnowledgeIndexService index = new TravelKnowledgeIndexService(
                new LocalTravelEmbeddingModel(), loader, planService);
        index.upsertEntities(corpus());

        List<EvaluationCase> cases = List.of(
                new EvaluationCase("上海浦东亲子科技馆怎么玩", "plan:shanghai-family"),
                new EvaluationCase("北京故宫历史文化路线", "plan:beijing-history"),
                new EvaluationCase("杭州西湖轻松漫步", "plan:hangzhou-walk"),
                new EvaluationCase("成都熊猫基地亲子行程", "plan:chengdu-panda"),
                new EvaluationCase("三亚海棠湾海边度假", "plan:sanya-beach")
        );

        long startedAt = System.nanoTime();
        long hits = cases.stream().filter(testCase -> {
            List<Document> documents = index.search(testCase.query(), 3);
            return documents.stream()
                    .map(document -> String.valueOf(document.getMetadata().get("id")))
                    .anyMatch(testCase.expectedDocumentId()::equals);
        }).count();
        long elapsedNanos = System.nanoTime() - startedAt;

        double recallAtThree = (double) hits / cases.size();
        double averageLatencyMillis = elapsedNanos / 1_000_000.0 / cases.size();
        System.out.printf(
                "RAG_BASELINE recall_at_3=%.3f average_local_latency_ms=%.3f cases=%d%n",
                recallAtThree, averageLatencyMillis, cases.size());

        assertThat(recallAtThree).isGreaterThanOrEqualTo(0.80);
    }

    private List<TravelKnowledgeEntity> corpus() {
        return List.of(
                plan("plan:shanghai-family", "上海", "浦东新区", "上海浦东亲子科技馆自然博物馆两日游"),
                plan("plan:beijing-history", "北京", "东城区", "北京故宫天坛历史文化路线"),
                plan("plan:hangzhou-walk", "杭州", "西湖区", "杭州西湖轻松漫步与断桥游览"),
                plan("plan:chengdu-panda", "成都", "成华区", "成都熊猫基地亲子一日行程"),
                plan("plan:sanya-beach", "三亚", "海棠区", "三亚海棠湾海边度假与免税店攻略"),
                plan("plan:shanghai-food", "上海", "黄浦区", "上海黄浦本帮菜美食路线"),
                plan("plan:beijing-modern", "北京", "朝阳区", "北京朝阳现代艺术与夜景路线")
        );
    }

    private TravelKnowledgeEntity plan(String id, String city, String district, String content) {
        return TravelKnowledgeEntity.builder()
                .id(id)
                .title(content)
                .content(content)
                .city(city)
                .district(district)
                .level(TravelKnowledgeLevel.PLAN)
                .parentId("district:" + city + ":" + district)
                .source("phase0_baseline")
                .metadata(Map.of("baseline", true))
                .build();
    }

    private record EvaluationCase(String query, String expectedDocumentId) {
    }
}
