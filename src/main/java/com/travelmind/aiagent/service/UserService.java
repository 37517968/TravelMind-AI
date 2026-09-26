package com.travelmind.aiagent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travelmind.aiagent.common.ErrorCode;
import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.mapper.UserMapper;
import com.travelmind.aiagent.model.entity.User;
import com.travelmind.aiagent.model.enums.UserRoleEnum;
import com.travelmind.aiagent.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import static com.travelmind.aiagent.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService extends ServiceImpl<UserMapper, User> {

    private final PasswordHashService passwordHashService;

    /**
     * 用户注册
     *
     * @param userAccount   用户账号
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @param userName      用户昵称
     * @return 新用户 id
     */
    public long userRegister(String userAccount, String userPassword, String checkPassword, String userName) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userAccount.length() > 32 || !userAccount.matches("[A-Za-z0-9_]+")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号只能包含字母、数字和下划线，且不能超过32位");
        }
        if (userPassword.length() < 6 || checkPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (userPassword.length() > 72 || checkPassword.length() > 72) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码不能超过72位");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        
        synchronized (userAccount.intern()) {
            // 账号不能重复
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userAccount", userAccount);
            long count = this.baseMapper.selectCount(queryWrapper);
            if (count > 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
            }
            
            // 2. 使用带随机盐的强哈希保存密码
            User user = new User();
            user.setUserAccount(userAccount);
            user.setUserPassword(passwordHashService.hash(userPassword));
            user.setUserName(StrUtil.isBlank(userName) ? "用户" + userAccount : userName);
            user.setUserRole(UserRoleEnum.USER.getValue());
            boolean saveResult = this.save(user);
            if (!saveResult) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
            }
            return user.getId();
        }
    }

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @param request      请求
     * @return 脱敏后的用户信息
     */
    public UserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        if (userAccount.length() > 32 || userPassword.length() > 72) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }
        
        // 2. 只按账号查询，再以常量时间比较密码哈希。
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        User user = this.baseMapper.selectOne(queryWrapper);
        
        if (user == null || !passwordHashService.matches(userPassword, user.getUserPassword())) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        // 老版本 MD5 用户在首次成功登录时无感升级，避免长期保留弱哈希。
        if (passwordHashService.needsUpgrade(user.getUserPassword())) {
            String upgradedHash = passwordHashService.hash(userPassword);
            this.lambdaUpdate().eq(User::getId, user.getId())
                    .set(User::getUserPassword, upgradedHash).update();
            user.setUserPassword(upgradedHash);
        }
        
        // 3. Session 只保存鉴权必需字段，不保存密码哈希。
        request.getSession(true).setAttribute(USER_LOGIN_STATE, sessionUser(user));
        
        return this.getUserVO(user);
    }

    /**
     * 获取当前登录用户
     *
     * @param request 请求
     * @return 用户信息
     */
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        HttpSession session = request.getSession(false);
        Object userObj = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接返回上述结果）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取当前登录用户（允许未登录）
     *
     * @param request 请求
     * @return 用户信息
     */
    public User getLoginUserPermitNull(HttpServletRequest request) {
        // 先判断是否已登录
        HttpSession session = request.getSession(false);
        Object userObj = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            return null;
        }
        // 从数据库查询（追求性能的话可以注释，直接返回上述结果）
        long userId = currentUser.getId();
        return this.getById(userId);
    }

    /**
     * 是否为管理员
     *
     * @param request 请求
     * @return 是否为管理员
     */
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        HttpSession session = request.getSession(false);
        Object userObj = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return isAdmin(user);
    }

    /**
     * 是否为管理员
     *
     * @param user 用户
     * @return 是否为管理员
     */
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 用户注销
     *
     * @param request 请求
     */
    public boolean userLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        session.invalidate();
        return true;
    }

    private User sessionUser(User user) {
        User sessionUser = new User();
        sessionUser.setId(user.getId());
        sessionUser.setUserAccount(user.getUserAccount());
        sessionUser.setUserName(user.getUserName());
        sessionUser.setUserAvatar(user.getUserAvatar());
        sessionUser.setUserRole(user.getUserRole());
        return sessionUser;
    }

    /**
     * 获取脱敏的用户信息
     *
     * @param user 用户
     * @return 脱敏用户信息
     */
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }
}

