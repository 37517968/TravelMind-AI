package com.travelmind.aiagent.planning.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 领域模型接收外部 JSON（大模型、求解服务、MCP 工具）时的不可变安全拷贝。
 *
 * JDK 的 List.copyOf/Map.copyOf 遇到 null 元素或 null 值会抛出不带消息的 NullPointerException，
 * 排查时只能看到节点失败而拿不到原因，因此在进入白名单 Schema 前统一丢弃 null 条目。
 */
final class ImmutableValues {

    private ImmutableValues() {
    }

    static <T> List<T> list(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).toList();
    }

    static Map<String, Object> map(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }
}