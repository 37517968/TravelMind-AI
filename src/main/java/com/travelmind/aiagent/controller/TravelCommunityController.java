package com.travelmind.aiagent.controller;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.common.BaseResponse;
import com.travelmind.aiagent.common.ErrorCode;
import com.travelmind.aiagent.common.ResultUtils;
import com.travelmind.aiagent.constant.UserConstant;
import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.model.dto.TravelCommentCreateRequest;
import com.travelmind.aiagent.model.dto.TravelPlanCreateRequest;
import com.travelmind.aiagent.model.entity.TravelComment;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.model.entity.User;
import com.travelmind.aiagent.service.TravelCommentService;
import com.travelmind.aiagent.service.TravelKnowledgeSyncService;
import com.travelmind.aiagent.service.TravelPlanService;
import com.travelmind.aiagent.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅行方案社区控制器
 * 提供旅行方案分享、评论等社区功能
 */
@RestController
@RequestMapping("/travel/community")
@Tag(name = "旅行社区接口")
public class TravelCommunityController {

    @Resource
    private TravelPlanService travelPlanService;

    @Resource
    private TravelCommentService travelCommentService;

    @Resource
    private UserService userService;

    @Autowired(required = false)
    private TravelKnowledgeSyncService travelKnowledgeSyncService;

    // ==================== 旅行方案相关接口 ====================

    /**
     * 创建旅行方案
     */
    @PostMapping("/plan/create")
    @Operation(summary = "创建旅行方案")
    public BaseResponse<TravelPlan> createPlan(@RequestBody TravelPlanCreateRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(httpRequest);
        
        TravelPlan plan = travelPlanService.createPlan(request, loginUser.getId(), loginUser.getUserName());
        return ResultUtils.success(plan);
    }

    /**
     * 获取方案详情
     */
    @GetMapping("/plan/{id}")
    @Operation(summary = "获取方案详情")
    public BaseResponse<TravelPlan> getPlanById(@PathVariable Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        TravelPlan plan = travelPlanService.getPlanById(id);
        if (plan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "方案不存在");
        }
        return ResultUtils.success(plan);
    }

    /**
     * 获取方案列表
     */
    @GetMapping("/plan/list")
    @Operation(summary = "获取方案列表")
    public BaseResponse<List<TravelPlan>> listPlans(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String travelType) {
        List<TravelPlan> plans = travelPlanService.listPlans(page, size, destination, travelType);
        return ResultUtils.success(plans);
    }

    /**
     * 获取热门方案
     */
    @GetMapping("/plan/hot")
    @Operation(summary = "获取热门方案")
    public BaseResponse<List<TravelPlan>> getHotPlans(@RequestParam(defaultValue = "10") int limit) {
        List<TravelPlan> plans = travelPlanService.getHotPlans(limit);
        return ResultUtils.success(plans);
    }

    /**
     * 搜索方案
     */
    @GetMapping("/plan/search")
    @Operation(summary = "搜索方案")
    public BaseResponse<List<TravelPlan>> searchPlans(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<TravelPlan> plans = travelPlanService.searchPlans(keyword, page, size);
        return ResultUtils.success(plans);
    }

    /**
     * 更新方案（仅作者本人或管理员可操作）
     */
    @PutMapping("/plan/{id}")
    @Operation(summary = "更新方案")
    public BaseResponse<TravelPlan> updatePlan(@PathVariable Long id, 
                                                @RequestBody TravelPlanCreateRequest request,
                                                HttpServletRequest httpRequest) {
        if (id == null || id <= 0 || request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(httpRequest);
        
        // 获取方案信息
        TravelPlan existingPlan = travelPlanService.getPlanById(id);
        if (existingPlan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "方案不存在");
        }
        
        // 权限校验：只有作者本人或管理员可以修改
        if (!existingPlan.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限修改此方案");
        }
        
        TravelPlan plan = travelPlanService.updatePlan(id, request);
        return ResultUtils.success(plan);
    }

    /**
     * 删除方案（仅作者本人或管理员可操作）
     */
    @DeleteMapping("/plan/{id}")
    @Operation(summary = "删除方案")
    public BaseResponse<Boolean> deletePlan(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(httpRequest);
        
        // 获取方案信息
        TravelPlan existingPlan = travelPlanService.getPlanById(id);
        if (existingPlan == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "方案不存在");
        }
        
        // 权限校验：只有作者本人或管理员可以删除
        if (!existingPlan.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除此方案");
        }
        
        boolean success = travelPlanService.deletePlan(id);
        return ResultUtils.success(success);
    }

    /**
     * 点赞方案
     */
    @PostMapping("/plan/{id}/like")
    @Operation(summary = "点赞方案")
    public BaseResponse<Boolean> likePlan(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 需要登录
        User loginUser = userService.getLoginUser(httpRequest);
        
        boolean success = travelPlanService.likePlan(id, loginUser.getId());
        return ResultUtils.success(success);
    }

    /**
     * 收藏方案
     */
    @PostMapping("/plan/{id}/favorite")
    @Operation(summary = "收藏方案")
    public BaseResponse<Boolean> favoritePlan(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 需要登录
        User loginUser = userService.getLoginUser(httpRequest);
        
        boolean success = travelPlanService.favoritePlan(id, loginUser.getId());
        return ResultUtils.success(success);
    }

    // ==================== 评论相关接口 ====================

    /**
     * 创建评论
     */
    @PostMapping("/comment/create")
    @Operation(summary = "创建评论")
    public BaseResponse<TravelComment> createComment(@RequestBody TravelCommentCreateRequest request, 
                                                      HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(httpRequest);
        
        TravelComment comment = travelCommentService.createComment(
                request, 
                loginUser.getId(), 
                loginUser.getUserName(), 
                loginUser.getUserAvatar()
        );
        return ResultUtils.success(comment);
    }

    /**
     * 获取方案的评论列表
     */
    @GetMapping("/comment/list/{planId}")
    @Operation(summary = "获取方案的评论列表")
    public BaseResponse<List<TravelComment>> getCommentsByPlanId(
            @PathVariable Long planId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (planId == null || planId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        List<TravelComment> comments = travelCommentService.getCommentsByPlanId(planId, page, size);
        return ResultUtils.success(comments);
    }

    /**
     * 获取评论的回复列表
     */
    @GetMapping("/comment/{commentId}/replies")
    @Operation(summary = "获取评论的回复列表")
    public BaseResponse<List<TravelComment>> getReplies(@PathVariable Long commentId) {
        if (commentId == null || commentId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        List<TravelComment> replies = travelCommentService.getRepliesByCommentId(commentId);
        return ResultUtils.success(replies);
    }

    /**
     * 删除评论（仅作者本人或管理员可操作）
     */
    @DeleteMapping("/comment/{id}")
    @Operation(summary = "删除评论")
    public BaseResponse<Boolean> deleteComment(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(httpRequest);
        
        // 获取评论信息
        TravelComment existingComment = travelCommentService.getCommentById(id);
        if (existingComment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "评论不存在");
        }
        
        // 权限校验：只有作者本人或管理员可以删除
        if (!existingComment.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除此评论");
        }
        
        boolean success = travelCommentService.deleteComment(id);
        return ResultUtils.success(success);
    }

    /**
     * 点赞评论
     */
    @PostMapping("/comment/{id}/like")
    @Operation(summary = "点赞评论")
    public BaseResponse<Boolean> likeComment(@PathVariable Long id, HttpServletRequest httpRequest) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 需要登录
        User loginUser = userService.getLoginUser(httpRequest);
        
        boolean success = travelCommentService.likeComment(id, loginUser.getId());
        return ResultUtils.success(success);
    }

    // ==================== 知识库同步相关接口 ====================

    /**
     * 手动触发知识库同步（仅管理员）
     * 将社区中的旅行方案和评论同步到向量知识库
     */
    @PostMapping("/knowledge/sync")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "手动触发知识库同步")
    public BaseResponse<Map<String, Object>> syncToKnowledgeBase() {
        if (travelKnowledgeSyncService == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "知识库同步服务未配置");
        }
        Map<String, Object> result = travelKnowledgeSyncService.manualSync();
        return ResultUtils.success(result);
    }

    /**
     * 同步单个方案到知识库（仅管理员）
     */
    @PostMapping("/knowledge/sync/{planId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "同步单个方案到知识库")
    public BaseResponse<Boolean> syncPlanToKnowledgeBase(@PathVariable Long planId) {
        if (planId == null || planId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (travelKnowledgeSyncService == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "知识库同步服务未配置");
        }
        boolean success = travelKnowledgeSyncService.syncSinglePlan(planId);
        return ResultUtils.success(success);
    }
}
