package com.travelmind.aiagent.harness;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class WorkflowState {
    private final Long taskId;
    private final Map<String, Object> request;
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final Map<String, Object> metrics = new LinkedHashMap<>();

    public WorkflowState(Long taskId, Map<String, Object> request) {
        this.taskId = taskId;
        this.request = new LinkedHashMap<>(request);
    }

    public synchronized void merge(Map<String, Object> values) {
        if (values != null) data.putAll(values);
    }

    /** 只保留指定键，其余中间结果全部丢弃；用于开启新一轮旅行规划。 */
    public synchronized void retainOnly(Map<String, Object> kept) {
        data.clear();
        if (kept != null) data.putAll(kept);
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("request", new LinkedHashMap<>(request));
        snapshot.put("data", new LinkedHashMap<>(data));
        snapshot.put("metrics", new LinkedHashMap<>(metrics));
        return snapshot;
    }
}
