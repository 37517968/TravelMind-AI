package com.travelmind.aiagent.rag;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/**
 * 旅行专属的上下文查询增强器工厂
 * 
 * 功能：
 * 1. 当知识库检索到相关文档时，将文档内容与用户查询结合，生成增强的提示词
 * 2. 当知识库未检索到相关文档时，返回友好的提示信息
 * 
 * 与普通 RAG 的区别：
 * - 针对旅行场景优化提示词模板
 * - 空上下文时引导用户提供更多旅行信息
 */
public class TravelContextualQueryAugmenterFactory {

    /**
     * 创建旅行专属的上下文查询增强器
     * 
     * @return ContextualQueryAugmenter 实例
     */
    public static ContextualQueryAugmenter createInstance() {
        // 当知识库中没有找到相关内容时的提示模板
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                抱歉，我在知识库中没有找到与您查询直接相关的旅行方案。
                
                不过作为您的旅行管家，我仍然可以帮助您：
                1. 根据我的旅行知识为您提供建议
                2. 帮您查询目的地的天气、景点、酒店等信息
                3. 为您制定个性化的旅行计划
                
                请告诉我更多关于您旅行的信息，比如：
                - 您想去哪里旅行？
                - 计划什么时候出发？
                - 预算大概是多少？
                - 有几个人同行？
                
                这样我可以为您提供更精准的旅行建议！
                """);

        // 当知识库检索到内容时的增强提示模板
        PromptTemplate contextPromptTemplate = new PromptTemplate("""
                以下是从旅行社区知识库中检索到的相关旅行方案和经验分享：
                
                ---------------------
                {context}
                ---------------------
                
                请参考以上社区用户分享的真实旅行经验，结合您的专业知识，回答用户的问题。
                
                注意事项：
                1. 优先参考知识库中的真实经验，但可以补充您的专业建议
                2. 如果知识库内容与用户需求不完全匹配，请说明并提供额外建议
                3. 引用社区方案时，可以提及"根据其他旅行者的经验..."
                4. 保持回答的实用性和可操作性
                
                用户问题：{query}
                """);

        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false)  // 不允许空上下文直接回答
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .promptTemplate(contextPromptTemplate)
                .build();
    }

    /**
     * 创建允许空上下文的增强器（即使知识库没有内容也继续回答）
     * 适用于希望 AI 始终给出回答的场景
     * 
     * @return ContextualQueryAugmenter 实例
     */
    public static ContextualQueryAugmenter createPermissiveInstance() {
        PromptTemplate contextPromptTemplate = new PromptTemplate("""
                以下是从旅行社区知识库中检索到的参考信息（如果有的话）：
                
                ---------------------
                {context}
                ---------------------
                
                请结合以上参考信息（如有）和您的旅行专业知识，为用户提供帮助。
                
                用户问题：{query}
                """);

        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)  // 允许空上下文
                .promptTemplate(contextPromptTemplate)
                .build();
    }
}

