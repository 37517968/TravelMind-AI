package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.planning.model.TravelRouteOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用一次受治理的模型调用为整批候选路线生成与实际景点相符的短标题。 */
@Slf4j
@Component
public class TravelRouteTitleService {
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final SentinelGovernanceService sentinel;

    public TravelRouteTitleService(ChatModel chatModel, ObjectMapper objectMapper,
                                   SentinelGovernanceService sentinel) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.sentinel = sentinel;
    }

    public GenerationResult generate(List<TravelRouteOption> options) {
        if (options == null || options.isEmpty()) return new GenerationResult(List.of(), false, 0);
        try {
            List<Map<String, Object>> routes = options.stream().map(option -> {
                Map<String, Object> route = new LinkedHashMap<>();
                route.put("id", option.id());
                route.put("destination", option.destination());
                route.put("attractions", option.attractions().stream()
                        .map(TravelRouteOption.RouteAttraction::name).toList());
                return route;
            }).toList();
            String material = objectMapper.writeValueAsString(routes);
            String prompt = """
                    你是旅行产品的路线命名编辑。请理解每条路线实际包含的目的地和景点，为每条路线生成一个有辨识度的中文标题。
                    要求：标题必须体现该路线的景点组合或共同主题，不能使用“经典必游路线、轻松漫游路线、城市深度路线”等固定模板；
                    每个标题 6～16 个汉字，可使用“·”，不要 Emoji，不要增加输入中不存在的景点。
                    只返回严格 JSON，格式为 {"titles":[{"id":"原id","title":"标题"}]}，不要输出解释。
                    路线数据：
                    """ + material;
            String raw = sentinel.executeModel(() -> ChatClient.builder(chatModel).build()
                    .prompt().user(prompt).call().content());
            Map<String, String> titles = parseTitles(raw);
            List<TravelRouteOption> generated = options.stream().map(option -> withTitle(option,
                    titles.getOrDefault(option.id(), option.title()))).toList();
            return new GenerationResult(generated, true, estimateTokens(prompt, raw));
        } catch (Exception failure) {
            // 路线卡片不能因命名模型暂时不可用而整体失败；候选服务已提供由真实 POI 拼出的动态兜底标题。
            log.warn("Route title generation failed; use POI-derived titles", failure);
            return new GenerationResult(List.copyOf(options), false, 0);
        }
    }

    private Map<String, String> parseTitles(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode item : root.path("titles")) {
            String id = item.path("id").asText("").trim();
            String title = sanitize(item.path("title").asText(""));
            if (!id.isBlank() && !title.isBlank()) result.put(id, title);
        }
        return result;
    }

    private TravelRouteOption withTitle(TravelRouteOption option, String title) {
        return new TravelRouteOption(option.id(), option.destination(), sanitize(title), option.emoji(),
                option.summary(), option.attractions());
    }

    private String sanitize(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\n\\t\"'“”]", "").trim();
        return clean.length() <= 20 ? clean : clean.substring(0, 20);
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{'), end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private int estimateTokens(String input, String output) {
        return Math.max(1, ((input == null ? 0 : input.length()) + (output == null ? 0 : output.length())) / 4);
    }

    public record GenerationResult(List<TravelRouteOption> options, boolean modelCalled, int estimatedTokens) {
        public GenerationResult {
            options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        }
    }
}
