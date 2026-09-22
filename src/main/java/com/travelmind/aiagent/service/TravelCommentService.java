package com.travelmind.aiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travelmind.aiagent.mapper.TravelCommentMapper;
import com.travelmind.aiagent.mapper.TravelPlanMapper;
import com.travelmind.aiagent.knowledge.service.KnowledgeEventService;
import com.travelmind.aiagent.mapper.UserLikeMapper;
import com.travelmind.aiagent.model.dto.TravelCommentCreateRequest;
import com.travelmind.aiagent.model.entity.TravelComment;
import com.travelmind.aiagent.model.entity.UserLike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行方案评论服务
 * 管理用户对旅行方案的评论
 * 使用 MySQL 持久化
 */
@Service
@Slf4j
public class TravelCommentService extends ServiceImpl<TravelCommentMapper, TravelComment> {

    @Autowired
    private TravelCommentMapper travelCommentMapper;

    @Autowired
    private UserLikeMapper userLikeMapper;

    @Autowired
    private TravelPlanService travelPlanService;

    @Autowired
    private TravelPlanMapper travelPlanMapper;

    @Autowired
    private KnowledgeEventService knowledgeEventService;

    /**
     * 创建评论
     */
    @Transactional(rollbackFor = Exception.class)
    public TravelComment createComment(TravelCommentCreateRequest request, Long userId, String userName, String userAvatar) {
        TravelComment comment = new TravelComment();
        comment.setPlanId(request.getPlanId());
        comment.setUserId(userId);
        comment.setContent(request.getContent());
        comment.setParentId(request.getParentId());
        comment.setReplyUserId(request.getReplyUserId());
        comment.setLikeCount(0);
        comment.setStatus(0);
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        comment.setIsDelete(0);

        save(comment);
        
        // 设置非数据库字段（用于返回）
        comment.setUserName(userName);
        comment.setUserAvatar(userAvatar);
        
        // 增加方案的评论数
        travelPlanService.incrementCommentCount(request.getPlanId());
        publishPlanRefresh(request.getPlanId());
        
        log.info("创建评论成功: id={}, planId={}", comment.getId(), comment.getPlanId());
        return comment;
    }

    /**
     * 获取方案的评论列表（分页，只获取一级评论）
     */
    public List<TravelComment> getCommentsByPlanId(Long planId, int page, int size) {
        int offset = (page - 1) * size;
        return travelCommentMapper.selectTopLevelComments(planId, offset, size);
    }

    /**
     * 获取评论的回复列表
     */
    public List<TravelComment> getRepliesByCommentId(Long commentId) {
        return travelCommentMapper.selectReplies(commentId);
    }

    /**
     * 获取评论详情
     */
    public TravelComment getCommentById(Long id) {
        TravelComment comment = getById(id);
        if (comment != null && comment.getIsDelete() == 0) {
            return comment;
        }
        return null;
    }

    /**
     * 删除评论（软删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteComment(Long id) {
        TravelComment comment = getById(id);
        if (comment != null) {
            comment.setIsDelete(1);
            comment.setUpdateTime(LocalDateTime.now());
            updateById(comment);
            
            // 减少方案的评论数
            travelPlanService.decrementCommentCount(comment.getPlanId());
            publishPlanRefresh(comment.getPlanId());
            
            log.info("删除评论成功: id={}", id);
            return true;
        }
        return false;
    }

    /**
     * 点赞评论
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean likeComment(Long commentId, Long userId) {
        // 检查是否已点赞
        int count = userLikeMapper.checkUserLiked(userId, commentId, UserLike.TARGET_TYPE_COMMENT);
        if (count > 0) {
            log.info("用户已点赞过该评论: userId={}, commentId={}", userId, commentId);
            return false;
        }

        // 记录点赞
        UserLike userLike = new UserLike();
        userLike.setUserId(userId);
        userLike.setTargetId(commentId);
        userLike.setTargetType(UserLike.TARGET_TYPE_COMMENT);
        userLike.setCreateTime(LocalDateTime.now());
        userLikeMapper.insert(userLike);

        // 增加点赞数
        travelCommentMapper.incrementLikeCount(commentId);
        
        return true;
    }

    /**
     * 取消点赞评论
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean unlikeComment(Long commentId, Long userId) {
        LambdaQueryWrapper<UserLike> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserLike::getUserId, userId)
               .eq(UserLike::getTargetId, commentId)
               .eq(UserLike::getTargetType, UserLike.TARGET_TYPE_COMMENT);
        
        int deleted = userLikeMapper.delete(wrapper);
        if (deleted > 0) {
            travelCommentMapper.decrementLikeCount(commentId);
            return true;
        }
        return false;
    }

    /**
     * 获取方案的评论总数
     */
    public long getCommentCountByPlanId(Long planId) {
        return travelCommentMapper.countByPlanId(planId);
    }

    /**
     * 获取所有评论（用于知识库）
     */
    public List<TravelComment> getAllComments() {
        LambdaQueryWrapper<TravelComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TravelComment::getIsDelete, 0);
        return list(wrapper);
    }

    /**
     * 根据方案ID获取所有评论（用于知识库）
     */
    public List<TravelComment> getCommentsByPlanIdForKnowledge(Long planId) {
        return travelCommentMapper.selectAllByPlanId(planId);
    }

    private void publishPlanRefresh(Long planId) {
        travelPlanMapper.incrementKnowledgeVersion(planId);
        knowledgeEventService.planUpsert(planId, travelPlanMapper.selectKnowledgeVersion(planId));
    }
}
