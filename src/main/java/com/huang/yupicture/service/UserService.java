package com.huang.yupicture.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;

/**
 * 用户业务接口。
 *
 * <p>继承 {@link IService} 拿到 MP 的通用业务方法（save / getById / page / removeById ...）。
 *
 * <p>⚠️ 包路径注意：MyBatis-Plus <b>3.5.17 起把 IService 从
 * {@code com.baomidou.mybatisplus.extension.service} 迁到了
 * {@code com.baomidou.mybatisplus.spring.service}</b>。
 * 网上教程（含课程）写的旧路径在本项目上编译不过 —— 手写时用 IDE 补全，别背路径。
 */
public interface UserService extends IService<User> {

    /**
     * 用户登录。
     *
     * @param userAccount  账号
     * @param userPassword 明文密码
     * @return 脱敏后的登录用户信息（不含密码）
     */
    LoginUserVO userLogin(String userAccount, String userPassword);

    /**
     * 获取当前登录用户（从 Sa-Token 会话里取 id 再查库）。
     *
     * @return 用户实体
     * @throws com.huang.yupicture.common.BusinessException 未登录时抛 NOT_LOGIN_ERROR
     */
    User getLoginUser();

    /**
     * 退出登录：注销服务端会话。
     */
    void userLogout();

    /**
     * 实体 → 脱敏 VO。
     *
     * <p>单独抽出来的原因：转化规则散在 Controller 里迟早会漏，
     * 将来加字段（如"是否 VIP"）也只改这一处。
     *
     * @param user 用户实体，可为 null
     * @return 脱敏 VO，入参 null 时返回 null
     */
    LoginUserVO toLoginUserVO(User user);
}
