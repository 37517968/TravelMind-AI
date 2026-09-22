package com.travelmind.aiagent.rag;

import com.travelmind.aiagent.model.entity.TravelComment;
import com.travelmind.aiagent.model.entity.TravelKnowledgeEntity;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import com.travelmind.aiagent.service.TravelCommentService;
import com.travelmind.aiagent.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 旅行知识实体加载器
 */
@Component
@RequiredArgsConstructor
public class TravelKnowledgeEntityLoader {

    private final TravelPlanService travelPlanService;
    private final TravelCommentService travelCommentService;

    private static final Map<String, List<String>> CITY_DISTRICTS = new LinkedHashMap<>();

    static {
        CITY_DISTRICTS.put("上海", List.of("黄浦区", "静安区", "浦东新区"));
        CITY_DISTRICTS.put("北京", List.of("东城区", "西城区", "朝阳区"));
        CITY_DISTRICTS.put("杭州", List.of("西湖区", "上城区", "拱墅区"));
        CITY_DISTRICTS.put("成都", List.of("锦江区", "武侯区", "青羊区"));
        CITY_DISTRICTS.put("三亚", List.of("吉阳区", "天涯区", "海棠区"));
    }

    /**
     * 加载城市和区县层的种子知识
     */
    public List<TravelKnowledgeEntity> loadSeedEntities() {
        List<TravelKnowledgeEntity> entities = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CITY_DISTRICTS.entrySet()) {
            String city = entry.getKey();
            String cityId = cityId(city);
            entities.add(TravelKnowledgeEntity.builder()
                    .id(cityId)
                    .title(city + "城市知识")
                    .content(buildCityContent(city))
                    .city(city)
                    .level(TravelKnowledgeLevel.CITY)
                    .source("builtin")
                    .build());

            for (String district : entry.getValue()) {
                entities.add(TravelKnowledgeEntity.builder()
                        .id(districtId(city, district))
                        .title(city + district + "旅行知识")
                        .content(buildDistrictContent(city, district))
                        .city(city)
                        .district(district)
                        .level(TravelKnowledgeLevel.DISTRICT)
                        .parentId(cityId)
                        .source("builtin")
                        .build());
            }
        }
        return entities;
    }

    /**
     * 把方案转成方案层知识实体
     */
    public List<TravelKnowledgeEntity> loadPlanEntities(List<TravelPlan> plans, boolean includeComments) {
        List<TravelKnowledgeEntity> entities = new ArrayList<>();
        for (TravelPlan plan : plans) {
            entities.add(loadPlanEntity(plan, includeComments));
        }
        return entities;
    }

    public TravelKnowledgeEntity loadPlanEntity(TravelPlan plan, boolean includeComments) {
        if (plan == null) {
            return null;
        }
        Location location = resolveLocation(plan.getDestination());
        String planContent = buildPlanContent(plan, includeComments);
        String parentId = location.district != null
                ? districtId(location.city, location.district)
                : cityId(location.city);

        return TravelKnowledgeEntity.builder()
                .id(planId(plan.getId()))
                .title(plan.getTitle())
                .content(planContent)
                .city(location.city)
                .district(location.district)
                .level(TravelKnowledgeLevel.PLAN)
                .parentId(parentId)
                .planId(plan.getId())
                .travelType(plan.getTravelType())
                .tags(plan.getTags())
                .source("travel_plan")
                .build();
    }

    /**
     * 加载当前待入库方案
     */
    public List<TravelKnowledgeEntity> loadPendingPlanEntities(boolean includeComments) {
        return loadPlanEntities(travelPlanService.getPlansNotInKnowledgeBase(), includeComments);
    }

    private String buildCityContent(String city) {
        return city + "是一个适合旅行的城市知识节点，适合用于自顶向下的旅行规划检索。";
    }

    private String buildDistrictContent(String city, String district) {
        return city + district + "是" + city + "下的重要区域节点，可用于进一步细化旅行路线和区域检索。";
    }

    private String buildPlanContent(TravelPlan plan, boolean includeComments) {
        StringBuilder content = new StringBuilder();
        content.append("【旅行方案】").append(plan.getTitle()).append("\n");
        content.append("目的地：").append(plan.getDestination()).append("\n");
        content.append("天数：").append(plan.getDays()).append("\n");
        content.append("预算：").append(plan.getBudget()).append("\n");
        content.append("人数：").append(plan.getTravelers()).append("\n");
        content.append("类型：").append(plan.getTravelType()).append("\n");
        if (plan.getSummary() != null) {
            content.append("摘要：").append(plan.getSummary()).append("\n");
        }
        if (plan.getContent() != null) {
            content.append("详情：").append(plan.getContent()).append("\n");
        }
        if (plan.getTags() != null) {
            content.append("标签：").append(plan.getTags()).append("\n");
        }

        if (includeComments) {
            List<TravelComment> comments = travelCommentService.getCommentsByPlanIdForKnowledge(plan.getId());
            if (!comments.isEmpty()) {
                content.append("评论：");
                for (TravelComment comment : comments) {
                    if (comment.getContent() != null && !comment.getContent().isBlank()) {
                        content.append(comment.getUserName() == null ? "匿名" : comment.getUserName())
                                .append("：")
                                .append(comment.getContent())
                                .append("；");
                    }
                }
            }
        }
        return content.toString();
    }

    private Location resolveLocation(String destination) {
        String source = destination == null ? "" : destination.trim();
        String city = findCity(source);
        String district = findDistrict(source, city);
        if (city == null || city.isBlank()) {
            city = source.isBlank() ? "未知城市" : source;
        }
        return new Location(city, district);
    }

    private String findCity(String text) {
        if (text == null) {
            return null;
        }
        for (String city : CITY_DISTRICTS.keySet()) {
            if (text.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private String findDistrict(String text, String city) {
        if (text == null || city == null) {
            return null;
        }
        for (String district : CITY_DISTRICTS.getOrDefault(city, List.of())) {
            if (text.contains(district)) {
                return district;
            }
        }
        return null;
    }

    private String cityId(String city) {
        return "city:" + normalize(city);
    }

    private String districtId(String city, String district) {
        return "district:" + normalize(city) + ":" + normalize(district);
    }

    private String planId(Long id) {
        return "plan:" + id;
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    private record Location(String city, String district) {
    }
}
