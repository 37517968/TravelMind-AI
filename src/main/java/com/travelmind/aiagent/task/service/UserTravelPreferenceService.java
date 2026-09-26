package com.travelmind.aiagent.task.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.mapper.UserTravelPreferenceMapper;
import com.travelmind.aiagent.task.model.UserTravelPreference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只保存用户显式确认的白名单偏好，不从对话中猜测敏感或不稳定信息。 */
@Service
@RequiredArgsConstructor
public class UserTravelPreferenceService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> ALLOWED = Set.of(
            "travelStyle", "pace", "transport", "dietary", "hotelLevel", "accessibility", "notes");
    private final UserTravelPreferenceMapper mapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> get(Long userId) {
        UserTravelPreference entity = mapper.selectById(userId);
        if (entity == null || entity.getPreferencesJson() == null) return Map.of();
        try { return objectMapper.readValue(entity.getPreferencesJson(), MAP_TYPE); }
        catch (Exception ignored) { return Map.of(); }
    }

    @Transactional
    public Map<String, Object> save(Long userId, Map<String, Object> input) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (input != null) {
            for (String key : ALLOWED) {
                Object value = input.get(key);
                if (value instanceof String text && !text.isBlank()) {
                    sanitized.put(key, text.substring(0, Math.min(text.length(), 500)));
                } else if (value instanceof List<?> list) {
                    sanitized.put(key, list.stream().limit(20).map(Object::toString)
                            .map(v -> v.substring(0, Math.min(v.length(), 80))).toList());
                }
            }
        }
        try {
            UserTravelPreference existing = mapper.selectById(userId);
            UserTravelPreference entity = existing == null ? new UserTravelPreference() : existing;
            entity.setUserId(userId);
            entity.setPreferencesJson(objectMapper.writeValueAsString(sanitized));
            entity.setVersion(existing == null || existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            if (existing == null) mapper.insert(entity); else mapper.updateById(entity);
            return sanitized;
        } catch (Exception error) {
            throw new IllegalStateException("用户旅行偏好保存失败", error);
        }
    }
}
