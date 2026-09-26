package com.huang.yupicture.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.mapper.UserMapper;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户业务实现。
 *
 * <p>{@code ServiceImpl<Mapper, Entity>} 把 Mapper 的 CRUD 包装成业务方法，
 * 并通过构造函数注入 {@link UserMapper}（泛型里的 Mapper 类型就是注入线索）。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword) {
        // 1. 参数校验 —— 手写，不用 Bean Validation（本项目的既定选择）
        //    注意这里只校验"格式像不像"，不校验"长度够不够安全"，那属于注册时的密码强度规则
        if (userAccount == null || userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 4 位");
        }
        if (userPassword == null || userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 8 位");
        }

        // 2. 按账号查用户
        User user = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserAccount, userAccount));

        // 3. 比对密码
        //    ⚠️ "账号不存在" 和 "密码错误" 返回同一句文案 ——
        //    若分开提示，攻击者可以拿它当"账号是否存在"的探测器（用户枚举）。
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }
        if (!PasswordUtils.matches(userPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }

        // 4. 记录登录态
        //    StpUtil.login(id) 会生成 token 并写入 cookie（token 名见 application.yml 的 sa-token.token-name）
        //    同时把登录信息存进 Redis（引入了 sa-token-redis-jackson）→ 重启后端登录态不丢
        StpUtil.login(user.getId());
        log.info("用户登录成功：id={}, account={}", user.getId(), user.getUserAccount());

        // 5. 返回脱敏信息（绝不把 userPassword 带出去）
        return toLoginUserVO(user);
    }

    @Override
    public User getLoginUser() {
        // isLogin() 内部会从 cookie/header 里解析 token；没有或已过期都是 false
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        long userId = StpUtil.getLoginIdAsLong();
        User user = this.getById(userId);
        if (user == null) {
            // 极端情况：登录态还在，但用户已被删（逻辑删除也算）
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public LoginUserVO toLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        // 雪花 id（19 位 long）必须字符串化，否则 JS 端丢精度 —— 详见 LoginUserVO#id 的注释
        loginUserVO.setId(String.valueOf(user.getId()));
        loginUserVO.setUserAccount(user.getUserAccount());
        loginUserVO.setUserName(user.getUserName());
        loginUserVO.setUserAvatar(user.getUserAvatar());
        loginUserVO.setUserProfile(user.getUserProfile());
        loginUserVO.setUserRole(user.getUserRole());
        loginUserVO.setCreateTime(user.getCreateTime());
        return loginUserVO;
    }
}
