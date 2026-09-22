package com.travelmind.aiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travelmind.aiagent.mapper.TravelPlanMapper;
import com.travelmind.aiagent.knowledge.service.KnowledgeEventService;
import com.travelmind.aiagent.knowledge.service.KnowledgeHybridSearchService;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import com.travelmind.aiagent.mapper.UserFavoriteMapper;
import com.travelmind.aiagent.mapper.UserLikeMapper;
import com.travelmind.aiagent.model.dto.TravelPlanCreateRequest;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.model.entity.UserFavorite;
import com.travelmind.aiagent.model.entity.UserLike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 旅行方案服务
 * 管理用户分享的旅行方案，支持CRUD操作
 * 使用 MySQL 持久化 + Redis 缓存
 */
@Service
@Slf4j
public class TravelPlanService extends ServiceImpl<TravelPlanMapper, TravelPlan> {

    @Autowired
    private TravelPlanMapper travelPlanMapper;

    @Autowired
    private UserLikeMapper userLikeMapper;

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private KnowledgeEventService knowledgeEventService;

    @Autowired
    private KnowledgeHybridSearchService knowledgeHybridSearchService;

    // Redis Key 前缀
    private static final String CACHE_PLAN_PREFIX = "travel:plan:";
    private static final String CACHE_HOT_PLANS = "travel:plan:hot";
    private static final String CACHE_VIEW_COUNT_PREFIX = "travel:plan:view:";

    // 缓存过期时间
    private static final long CACHE_PLAN_EXPIRE = 30; // 30分钟
    private static final long CACHE_HOT_EXPIRE = 5;   // 5分钟

    /**
     * 创建旅行方案
     */
    @Transactional(rollbackFor = Exception.class)
    public TravelPlan createPlan(TravelPlanCreateRequest request, Long userId, String userName) {
        TravelPlan plan = new TravelPlan();
        plan.setUserId(userId);
        plan.setTitle(request.getTitle());
        plan.setDestination(request.getDestination());
        plan.setDays(request.getDays());
        plan.setBudget(request.getBudget());
        plan.setTravelers(request.getTravelers());
        plan.setTravelType(request.getTravelType());
        plan.setContent(request.getContent());
        plan.setSummary(request.getSummary());
        plan.setCoverImage(request.getCoverImage());
        plan.setTags(request.getTags());
        plan.setLikeCount(0);
        plan.setFavoriteCount(0);
        plan.setCommentCount(0);
        plan.setViewCount(0);
        plan.setStatus(TravelPlan.STATUS_PUBLISHED);
        plan.setInKnowledgeBase(0);
        plan.setKnowledgeVersion(1L);
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(LocalDateTime.now());
        plan.setIsDelete(0);

        save(plan);
        knowledgeEventService.planUpsert(plan.getId(), plan.getKnowledgeVersion());
        
        // 清除热门方案缓存
        clearHotPlansCache();
        
        log.info("创建旅行方案成功: id={}, title={}", plan.getId(), plan.getTitle());
        return plan;
    }

    /**
     * 根据ID获取方案（带缓存）
     */
    public TravelPlan getPlanById(Long id) {
        // 1. 先从缓存获取
        TravelPlan plan = getPlanFromCache(id);
        if (plan != null) {
            incrementViewCount(id);
            return plan;
        }

        // 2. 缓存未命中，从数据库查询
        plan = getById(id);
        if (plan != null && plan.getIsDelete() == 0) {
            // 增加浏览量
            incrementViewCount(id);
            // 写入缓存
            cachePlan(plan);
            return plan;
        }
        return null;
    }

    /**
     * 增加浏览量（使用Redis计数，定期同步到数据库）
     */
    private void incrementViewCount(Long planId) {
        if (redisTemplate != null) {
            String key = CACHE_VIEW_COUNT_PREFIX + planId;
            redisTemplate.opsForValue().increment(key);
        } else {
            // 无Redis时直接更新数据库
            travelPlanMapper.incrementViewCount(planId);
        }
    }

    /**
     * 获取方案列表（分页）
     */
    public List<TravelPlan> listPlans(int page, int size, String destination, String travelType) {
        LambdaQueryWrapper<TravelPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TravelPlan::getIsDelete, 0)
               .eq(TravelPlan::getStatus, TravelPlan.STATUS_PUBLISHED);
        
        if (destination != null && !destination.isEmpty()) {
            wrapper.like(TravelPlan::getDestination, destination);
        }
        if (travelType != null && !travelType.isEmpty()) {
            wrapper.eq(TravelPlan::getTravelType, travelType);
        }
        
        wrapper.orderByDesc(TravelPlan::getCreateTime);
        
        Page<TravelPlan> pageResult = page(new Page<>(page, size), wrapper);
        return pageResult.getRecords();
    }

    /**
     * 获取热门方案（带缓存）
     */
    @SuppressWarnings("unchecked")
    public List<TravelPlan> getHotPlans(int limit) {
        // 1. 先从缓存获取
        if (redisTemplate != null) {
            List<TravelPlan> cached = (List<TravelPlan>) redisTemplate.opsForValue().get(CACHE_HOT_PLANS);
            if (cached != null) {
                return cached.size() > limit ? cached.subList(0, limit) : cached;
            }
        }

        // 2. 从数据库查询
        List<TravelPlan> hotPlans = travelPlanMapper.selectHotPlans(limit);
        
        // 3. 写入缓存
        if (redisTemplate != null && !hotPlans.isEmpty()) {
            redisTemplate.opsForValue().set(CACHE_HOT_PLANS, hotPlans, CACHE_HOT_EXPIRE, TimeUnit.MINUTES);
        }
        
        return hotPlans;
    }

    /**
     * 搜索方案（全文搜索）
     */
    public List<TravelPlan> searchPlans(String keyword, int page, int size) {
        int requested = Math.max(1, page) * Math.max(1, size);
        java.util.List<Long> rankedIds = knowledgeHybridSearchService.search(KnowledgeSearchRequest.builder()
                        .query(keyword).topK(requested).build()).hits().stream()
                .filter(hit -> "TRAVEL_PLAN".equals(hit.getSourceType()) && hit.getSourceId() != null)
                .map(com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit::getSourceId).distinct().toList();
        int from = Math.min((Math.max(1, page) - 1) * Math.max(1, size), rankedIds.size());
        int to = Math.min(from + Math.max(1, size), rankedIds.size());
        if (from >= to) return java.util.List.of();
        java.util.Map<Long, TravelPlan> plans = listByIds(rankedIds.subList(from, to)).stream()
                .collect(java.util.stream.Collectors.toMap(TravelPlan::getId, value -> value));
        return rankedIds.subList(from, to).stream().map(plans::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 更新方案
     */
    @Transactional(rollbackFor = Exception.class)
    public TravelPlan updatePlan(Long id, TravelPlanCreateRequest request) {
        TravelPlan plan = getById(id);
        if (plan == null || plan.getIsDelete() == 1) {
            return null;
        }
        
        if (request.getTitle() != null) plan.setTitle(request.getTitle());
        if (request.getDestination() != null) plan.setDestination(request.getDestination());
        if (request.getDays() != null) plan.setDays(request.getDays());
        if (request.getBudget() != null) plan.setBudget(request.getBudget());
        if (request.getTravelers() != null) plan.setTravelers(request.getTravelers());
        if (request.getTravelType() != null) plan.setTravelType(request.getTravelType());
        if (request.getContent() != null) plan.setContent(request.getContent());
        if (request.getSummary() != null) plan.setSummary(request.getSummary());
        if (request.getCoverImage() != null) plan.setCoverImage(request.getCoverImage());
        if (request.getTags() != null) plan.setTags(request.getTags());
        plan.setKnowledgeVersion((plan.getKnowledgeVersion() == null ? 1L : plan.getKnowledgeVersion()) + 1);
        plan.setUpdateTime(LocalDateTime.now());
        
        updateById(plan);
        knowledgeEventService.planUpsert(plan.getId(), plan.getKnowledgeVersion());
        
        // 清除缓存
        clearPlanCache(id);
        clearHotPlansCache();
        
        log.info("更新旅行方案成功: id={}", id);
        return plan;
    }

    /**
     * 删除方案（软删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deletePlan(Long id) {
        TravelPlan plan = getById(id);
        if (plan != null) {
            plan.setIsDelete(1);
            plan.setKnowledgeVersion((plan.getKnowledgeVersion() == null ? 1L : plan.getKnowledgeVersion()) + 1);
            plan.setUpdateTime(LocalDateTime.now());
            updateById(plan);
            knowledgeEventService.planDelete(plan.getId(), plan.getKnowledgeVersion());
            
            // 清除缓存
            clearPlanCache(id);
            clearHotPlansCache();
            
            log.info("删除旅行方案成功: id={}", id);
            return true;
        }
        return false;
    }

    /**
     * 点赞方案
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean likePlan(Long planId, Long userId) {
        // 检查是否已点赞
        int count = userLikeMapper.checkUserLiked(userId, planId, UserLike.TARGET_TYPE_PLAN);
        if (count > 0) {
            log.info("用户已点赞过该方案: userId={}, planId={}", userId, planId);
            return false;
        }

        // 记录点赞
        UserLike userLike = new UserLike();
        userLike.setUserId(userId);
        userLike.setTargetId(planId);
        userLike.setTargetType(UserLike.TARGET_TYPE_PLAN);
        userLike.setCreateTime(LocalDateTime.now());
        userLikeMapper.insert(userLike);

        // 增加点赞数
        travelPlanMapper.incrementLikeCount(planId);
        
        // 清除缓存
        clearPlanCache(planId);
        clearHotPlansCache();
        
        return true;
    }

    /**
     * 取消点赞
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean unlikePlan(Long planId, Long userId) {
        LambdaQueryWrapper<UserLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserLike::getUserId, userId)
               .eq(UserLike::getTargetId, planId)
               .eq(UserLike::getTargetType, UserLike.TARGET_TYPE_PLAN);
        
        int deleted = userLikeMapper.delete(wrapper);
        if (deleted > 0) {
            travelPlanMapper.decrementLikeCount(planId);
            clearPlanCache(planId);
            clearHotPlansCache();
            return true;
        }
        return false;
    }

    /**
     * 收藏方案
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean favoritePlan(Long planId, Long userId) {
        // 检查是否已收藏
        int count = userFavoriteMapper.checkUserFavorited(userId, planId);
        if (count > 0) {
            log.info("用户已收藏过该方案: userId={}, planId={}", userId, planId);
            return false;
        }

        // 记录收藏
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setPlanId(planId);
        favorite.setCreateTime(LocalDateTime.now());
        userFavoriteMapper.insert(favorite);

        // 增加收藏数
        travelPlanMapper.incrementFavoriteCount(planId);
        
        // 清除缓存
        clearPlanCache(planId);
        
        return true;
    }

    /**
     * 取消收藏
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean unfavoritePlan(Long planId, Long userId) {
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFavorite::getUserId, userId)
               .eq(UserFavorite::getPlanId, planId);
        
        int deleted = userFavoriteMapper.delete(wrapper);
        if (deleted > 0) {
            travelPlanMapper.decrementFavoriteCount(planId);
            clearPlanCache(planId);
            return true;
        }
        return false;
    }

    /**
     * 获取所有已发布的方案（用于知识库）
     */
    public List<TravelPlan> getAllPublishedPlans() {
        LambdaQueryWrapper<TravelPlan> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TravelPlan::getIsDelete, 0)
               .eq(TravelPlan::getStatus, TravelPlan.STATUS_PUBLISHED);
        return list(wrapper);
    }

    /**
     * 标记方案已加入知识库
     */
    public void markAsInKnowledgeBase(Long id) {
        travelPlanMapper.markAsInKnowledgeBase(id);
    }

    /**
     * 获取未加入知识库的方案
     */
    public List<TravelPlan> getPlansNotInKnowledgeBase() {
        return travelPlanMapper.selectPlansNotInKnowledgeBase();
    }

    /**
     * 增加评论数
     */
    public void incrementCommentCount(Long planId) {
        travelPlanMapper.incrementCommentCount(planId);
        clearPlanCache(planId);
    }

    /**
     * 减少评论数
     */
    public void decrementCommentCount(Long planId) {
        travelPlanMapper.decrementCommentCount(planId);
        clearPlanCache(planId);
    }

    // ==================== 缓存操作 ====================

    private TravelPlan getPlanFromCache(Long id) {
        if (redisTemplate == null) return null;
        return (TravelPlan) redisTemplate.opsForValue().get(CACHE_PLAN_PREFIX + id);
    }

    private void cachePlan(TravelPlan plan) {
        if (redisTemplate == null) return;
        redisTemplate.opsForValue().set(CACHE_PLAN_PREFIX + plan.getId(), plan, CACHE_PLAN_EXPIRE, TimeUnit.MINUTES);
    }

    private void clearPlanCache(Long id) {
        if (redisTemplate == null) return;
        redisTemplate.delete(CACHE_PLAN_PREFIX + id);
    }

    private void clearHotPlansCache() {
        if (redisTemplate == null) return;
        redisTemplate.delete(CACHE_HOT_PLANS);
    }

    /**
     * 定时同步浏览量到数据库（由定时任务调用）
     */
    public void syncViewCountToDatabase() {
        if (redisTemplate == null) return;
        
        // 获取所有浏览量缓存
        // 实际实现中应该使用 SCAN 命令遍历
        log.info("开始同步浏览量到数据库...");
        // TODO: 实现浏览量同步逻辑
    }
}
