package com.travelmind.aiagent.rag;

import com.travelmind.aiagent.model.entity.TravelComment;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.service.TravelCommentService;
import com.travelmind.aiagent.service.TravelPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 旅游知识库文档加载器
 * 负责加载旅行方案、评论等内容到知识库
 * 
 * 数据来源：
 * 1. 静态旅游攻略文档（Markdown格式）
 * 2. 用户分享的旅行方案
 * 3. 用户评论中的有价值信息
 */
@Component
@Slf4j
public class TravelDocumentLoader {

    @Autowired
    private TravelPlanService travelPlanService;

    @Autowired
    private TravelCommentService travelCommentService;

    // 文本分割器
    private final TokenTextSplitter textSplitter = new TokenTextSplitter();

    /**
     * 加载初始文档（静态攻略）
     */
    public List<Document> loadInitialDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        
        // 1. 加载静态旅游攻略文档
        try {
            allDocuments.addAll(loadStaticTravelGuides());
        } catch (Exception e) {
            log.warn("加载静态旅游攻略失败: {}", e.getMessage());
        }
        
        // 2. 加载内置的热门目的地信息
        allDocuments.addAll(loadBuiltInDestinationInfo());
        
        log.info("初始化旅游知识库，共加载 {} 个文档", allDocuments.size());
        return allDocuments;
    }

    /**
     * 加载静态旅游攻略文档
     */
    private List<Document> loadStaticTravelGuides() {
        List<Document> documents = new ArrayList<>();
        
        // 尝试加载 resources/travel-guides 目录下的 Markdown 文件
        String[] guideFiles = {
                "travel-guides/shanghai.md",
                "travel-guides/beijing.md",
                "travel-guides/hangzhou.md",
                "travel-guides/chengdu.md",
                "travel-guides/sanya.md"
        };
        
        for (String filePath : guideFiles) {
            try {
                Resource resource = new ClassPathResource(filePath);
                if (resource.exists()) {
                    MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig());
                    List<Document> docs = reader.get();
                    // 分割文档
                    documents.addAll(textSplitter.apply(docs));
                    log.info("加载旅游攻略: {}", filePath);
                }
            } catch (Exception e) {
                log.debug("攻略文件不存在或加载失败: {}", filePath);
            }
        }
        
        return documents;
    }

    /**
     * 加载内置的热门目的地信息
     */
    private List<Document> loadBuiltInDestinationInfo() {
        List<Document> documents = new ArrayList<>();
        
        // 内置一些热门目的地的基础信息
        Map<String, String> destinations = new LinkedHashMap<>();
        
        destinations.put("上海旅游攻略", """
                上海是中国最大的城市，也是国际化大都市。
                
                热门景点：
                - 外滩：欣赏黄浦江两岸的壮丽景色，夜景尤为迷人
                - 东方明珠：上海地标建筑，可俯瞰全城
                - 豫园：江南古典园林，体验老上海风情
                - 南京路步行街：购物天堂，百年商业街
                - 迪士尼乐园：亚洲最大的迪士尼主题乐园
                - 田子坊：文艺小资聚集地，特色小店众多
                
                美食推荐：
                - 小笼包：南翔小笼最为正宗
                - 生煎包：底部金黄酥脆
                - 本帮菜：红烧肉、糖醋小排
                - 蟹粉小笼：秋季限定美味
                
                最佳旅行时间：春季（3-5月）和秋季（9-11月）气候宜人
                预算参考：经济型约300-500元/天，舒适型约500-800元/天
                """);
        
        destinations.put("北京旅游攻略", """
                北京是中国首都，拥有丰富的历史文化遗产。
                
                热门景点：
                - 故宫：世界最大的宫殿建筑群
                - 长城：八达岭、慕田峪等多个段落可选
                - 天安门广场：世界最大的城市广场
                - 颐和园：皇家园林，昆明湖畔风光秀丽
                - 天坛：明清皇帝祭天的场所
                - 798艺术区：当代艺术聚集地
                
                美食推荐：
                - 北京烤鸭：全聚德、大董等名店
                - 炸酱面：老北京传统面食
                - 卤煮火烧：地道北京小吃
                - 豆汁焦圈：独特的北京早餐
                
                最佳旅行时间：秋季（9-10月）天高气爽，是最佳旅游季节
                预算参考：经济型约300-500元/天，舒适型约500-1000元/天
                """);
        
        destinations.put("杭州旅游攻略", """
                杭州是浙江省会，以西湖闻名于世，素有"人间天堂"之称。
                
                热门景点：
                - 西湖：断桥残雪、雷峰塔、三潭印月
                - 灵隐寺：千年古刹，香火鼎盛
                - 西溪湿地：城市湿地公园，自然风光
                - 宋城：大型宋代主题公园
                - 龙井村：品茶赏景的好去处
                - 河坊街：古色古香的步行街
                
                美食推荐：
                - 西湖醋鱼：杭帮菜代表
                - 东坡肉：肥而不腻
                - 龙井虾仁：茶香四溢
                - 知味观小笼：百年老店
                
                最佳旅行时间：春季（3-5月）赏花，秋季（9-11月）赏桂
                预算参考：经济型约250-400元/天，舒适型约400-700元/天
                """);
        
        destinations.put("成都旅游攻略", """
                成都是四川省会，以美食和熊猫闻名，是一座来了就不想走的城市。
                
                热门景点：
                - 大熊猫繁育研究基地：近距离观看国宝
                - 宽窄巷子：成都名片，休闲好去处
                - 锦里：三国文化街区
                - 武侯祠：三国圣地
                - 都江堰：世界文化遗产
                - 青城山：道教名山
                
                美食推荐：
                - 火锅：麻辣鲜香，必吃美食
                - 串串香：小火锅的另一种形式
                - 担担面：麻辣可口
                - 龙抄手：成都特色小吃
                - 兔头：成都人的最爱
                
                最佳旅行时间：春秋两季最佳，夏季可去周边避暑
                预算参考：经济型约200-350元/天，舒适型约350-600元/天
                """);
        
        destinations.put("三亚旅游攻略", """
                三亚位于海南岛最南端，是中国著名的热带海滨旅游城市。
                
                热门景点：
                - 亚龙湾：天下第一湾，水质清澈
                - 天涯海角：浪漫地标
                - 蜈支洲岛：潜水胜地
                - 南山文化旅游区：海上观音
                - 大东海：市区最近的海滩
                - 鹿回头：俯瞰三亚全景
                
                美食推荐：
                - 海鲜：第一市场海鲜加工
                - 文昌鸡：海南四大名菜之一
                - 清补凉：消暑甜品
                - 椰子鸡：椰香浓郁
                
                最佳旅行时间：11月至次年4月，避开台风季
                预算参考：经济型约400-600元/天，舒适型约800-1500元/天
                注意事项：注意防晒，海边活动注意安全
                """);
        
        // 将目的地信息转换为文档
        for (Map.Entry<String, String> entry : destinations.entrySet()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "built-in");
            metadata.put("type", "destination-guide");
            metadata.put("title", entry.getKey());
            
            Document doc = new Document(entry.getValue(), metadata);
            documents.add(doc);
        }
        
        return documents;
    }

    /**
     * 将旅行方案转换为文档
     */
    public Document convertPlanToDocument(TravelPlan plan) {
        StringBuilder content = new StringBuilder();
        content.append("【旅行方案】").append(plan.getTitle()).append("\n\n");
        content.append("目的地：").append(plan.getDestination()).append("\n");
        content.append("行程天数：").append(plan.getDays()).append("天\n");
        content.append("预算：").append(plan.getBudget()).append("元\n");
        content.append("出行人数：").append(plan.getTravelers()).append("人\n");
        content.append("旅行类型：").append(plan.getTravelType()).append("\n\n");
        content.append("方案详情：\n").append(plan.getContent()).append("\n");
        
        if (plan.getTags() != null && !plan.getTags().isEmpty()) {
            content.append("\n标签：").append(plan.getTags());
        }
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "user-plan");
        metadata.put("type", "travel-plan");
        metadata.put("planId", plan.getId());
        metadata.put("destination", plan.getDestination());
        metadata.put("days", plan.getDays());
        metadata.put("budget", plan.getBudget());
        metadata.put("travelType", plan.getTravelType());
        metadata.put("likeCount", plan.getLikeCount());
        
        return new Document(content.toString(), metadata);
    }

    /**
     * 将评论转换为文档
     */
    public Document convertCommentToDocument(TravelComment comment, TravelPlan plan) {
        StringBuilder content = new StringBuilder();
        content.append("【用户评价】关于「").append(plan.getTitle()).append("」的评论\n\n");
        content.append("目的地：").append(plan.getDestination()).append("\n");
        content.append("评论内容：").append(comment.getContent()).append("\n");
        content.append("评论者：").append(comment.getUserName()).append("\n");
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "user-comment");
        metadata.put("type", "travel-comment");
        metadata.put("commentId", comment.getId());
        metadata.put("planId", plan.getId());
        metadata.put("destination", plan.getDestination());
        
        return new Document(content.toString(), metadata);
    }

    /**
     * 加载用户分享的旅行方案到知识库
     */
    public List<Document> loadUserPlans() {
        List<Document> documents = new ArrayList<>();
        
        // 获取未加入知识库的方案
        List<TravelPlan> plans = travelPlanService.getPlansNotInKnowledgeBase();
        
        for (TravelPlan plan : plans) {
            // 转换方案为文档
            documents.add(convertPlanToDocument(plan));
            
            // 加载该方案的评论
            List<TravelComment> comments = travelCommentService.getCommentsByPlanIdForKnowledge(plan.getId());
            for (TravelComment comment : comments) {
                // 只加载有价值的评论（内容长度大于20）
                if (comment.getContent() != null && comment.getContent().length() > 20) {
                    documents.add(convertCommentToDocument(comment, plan));
                }
            }
            
            // 标记方案已加入知识库
            travelPlanService.markAsInKnowledgeBase(plan.getId());
        }
        
        log.info("加载用户方案到知识库，共 {} 个文档", documents.size());
        return documents;
    }

    /**
     * 获取所有可用于知识库的文档
     */
    public List<Document> getAllDocuments() {
        List<Document> allDocuments = new ArrayList<>();
        
        // 加载初始文档
        allDocuments.addAll(loadInitialDocuments());
        
        // 加载用户方案
        allDocuments.addAll(loadUserPlans());
        
        return allDocuments;
    }
}

