package com.travelmind.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 多查询扩展器
 * 将用户的原始查询扩展为多个相关查询，提升知识检索的召回率
 * 
 * 原理：
 * 1. 接收用户原始查询
 * 2. 使用 AI 生成多个语义相关但表述不同的查询
 * 3. 每个扩展查询都会独立检索知识库
 * 4. 合并所有检索结果，去重后返回
 * 
 * 优势：
 * - 解决用户表述不准确导致的检索遗漏
 * - 从多个角度检索相关文档
 * - 提高召回率，确保不遗漏重要信息
 */
@Component
@Slf4j
public class MultiQueryExpander implements QueryExpander {

    private final ChatClient chatClient;

    private static final String EXPAND_PROMPT = """
            你是一个查询扩展专家。你的任务是将用户的旅行相关查询扩展为多个不同角度的查询，以便更全面地检索知识库。
            
            原始查询：{query}
            
            请生成3个与原始查询语义相关但表述不同的查询。这些查询应该：
            1. 覆盖原始查询的核心意图
            2. 从不同角度或使用不同关键词表达相同需求
            3. 可能包含原始查询中隐含但未明确表达的需求
            
            旅行查询扩展示例：
            - 原始："上海有什么好玩的" 
            - 扩展1："上海热门旅游景点推荐"
            - 扩展2："上海必去的打卡地有哪些"
            - 扩展3："上海旅游攻略景点排名"
            
            请直接输出3个扩展查询，每行一个，不要编号，不要其他解释。
            """;

    public MultiQueryExpander(ChatModel dashscopeChatModel) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    @Override
    public List<Query> expand(Query query) {
        List<Query> expandedQueries = new ArrayList<>();
        // 保留原始查询
        expandedQueries.add(query);

        try {
            String response = chatClient.prompt()
                    .user(EXPAND_PROMPT.replace("{query}", query.text()))
                    .call()
                    .content();

            if (response != null && !response.isBlank()) {
                List<Query> generatedQueries = Arrays.stream(response.split("\n"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .limit(3)
                        .map(Query::new)
                        .collect(Collectors.toList());
                
                expandedQueries.addAll(generatedQueries);
                log.info("查询扩展完成: 原始查询='{}', 扩展数量={}", query.text(), generatedQueries.size());
                generatedQueries.forEach(q -> log.debug("扩展查询: {}", q.text()));
            }
        } catch (Exception e) {
            log.warn("查询扩展失败，将使用原始查询: {}", e.getMessage());
        }

        return expandedQueries;
    }

    /**
     * 简化版扩展方法，直接返回扩展后的查询文本列表
     */
    public List<String> expandToStrings(String originalQuery) {
        Query query = new Query(originalQuery);
        return expand(query).stream()
                .map(Query::text)
                .collect(Collectors.toList());
    }
}

