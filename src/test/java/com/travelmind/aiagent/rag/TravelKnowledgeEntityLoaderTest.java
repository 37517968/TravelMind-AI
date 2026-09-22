package com.travelmind.aiagent.rag;

import com.travelmind.aiagent.model.entity.TravelComment;
import com.travelmind.aiagent.model.entity.TravelKnowledgeEntity;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import com.travelmind.aiagent.service.TravelCommentService;
import com.travelmind.aiagent.service.TravelPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelKnowledgeEntityLoaderTest {

    private final TravelPlanService planService = mock(TravelPlanService.class);
    private final TravelCommentService commentService = mock(TravelCommentService.class);
    private final TravelKnowledgeEntityLoader loader =
            new TravelKnowledgeEntityLoader(planService, commentService);

    @Test
    void shouldBuildStableCityAndDistrictHierarchy() {
        List<TravelKnowledgeEntity> entities = loader.loadSeedEntities();

        assertThat(entities).hasSize(20);
        assertThat(entities)
                .filteredOn(entity -> entity.getLevel() == TravelKnowledgeLevel.CITY)
                .extracting(TravelKnowledgeEntity::getCity)
                .containsExactlyInAnyOrder("上海", "北京", "杭州", "成都", "三亚");
        assertThat(entities)
                .anySatisfy(entity -> {
                    assertThat(entity.getId()).isEqualTo("district:上海:浦东新区");
                    assertThat(entity.getParentId()).isEqualTo("city:上海");
                });
    }

    @Test
    void shouldConvertPlanAndCommentsToTraceableKnowledgeEntity() {
        TravelPlan plan = new TravelPlan();
        plan.setId(42L);
        plan.setTitle("上海浦东两日亲子游");
        plan.setDestination("上海市浦东新区");
        plan.setDays(2);
        plan.setBudget(3000);
        plan.setTravelers(3);
        plan.setTravelType("亲子游");
        plan.setSummary("科技馆与城市漫步");
        plan.setContent("第一天参观科技馆");
        plan.setTags("亲子,博物馆");

        TravelComment comment = new TravelComment();
        comment.setUserName("游客A");
        comment.setContent("建议工作日预约");
        when(commentService.getCommentsByPlanIdForKnowledge(42L)).thenReturn(List.of(comment));

        TravelKnowledgeEntity entity = loader.loadPlanEntity(plan, true);

        assertThat(entity.getId()).isEqualTo("plan:42");
        assertThat(entity.getLevel()).isEqualTo(TravelKnowledgeLevel.PLAN);
        assertThat(entity.getCity()).isEqualTo("上海");
        assertThat(entity.getDistrict()).isEqualTo("浦东新区");
        assertThat(entity.getParentId()).isEqualTo("district:上海:浦东新区");
        assertThat(entity.getSource()).isEqualTo("travel_plan");
        assertThat(entity.getContent())
                .contains("上海浦东两日亲子游", "游客A：建议工作日预约");
    }
}
