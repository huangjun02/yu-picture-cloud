package com.huang.yupicture.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.dto.user.UserAddRequest;
import com.huang.yupicture.model.dto.user.UserQueryRequest;
import com.huang.yupicture.model.dto.user.UserRegisterRequest;
import com.huang.yupicture.model.dto.user.UserUpdateRequest;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.model.vo.UserVO;

/**
 * 用户业务接口。
 *
 * <p>继承 {@link IService} 拿到 MP 的通用业务方法（save / getById / page / removeById ...）。
 *
 * <p>⚠️ 包路径注意：MyBatis-Plus <b>3.5.17 起把 IService 从
 * {@code com.baomidou.mybatisplus.extension.service} 迁到了
 * {@code com.baomidou.mybatisplus.spring.service}</b>。
 * 网上教程（含课程）写的旧路径在本项目上编译不过 —— 手写时用 IDE 补全，别背路径。
 * （同版本的 {@code Page} 类<b>没有</b>跟着挪，仍在 {@code extension.plugins.pagination} 下。）
 *
 * <p><b>管理员方法（userAdd / userUpdate / userDelete / getUserById / listUserByPage）
 * 都在实现里自己调 {@link #checkAdminUser()}</b>，调用方不需要先自己校验权限 ——
 * 权限判断放在 Service 而不是 Controller，是为了将来多一个入口（小程序 API、定时任务）
 * 时不必重写一遍。
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

    /**
     * 用户注册。
     *
     * @param userRegisterRequest 注册请求（账号、密码、确认密码）
     * @return 新用户的 id（字符串形式 —— 19 位雪花 id 直接给 JS 会丢精度）
     */
    String userRegister(UserRegisterRequest userRegisterRequest);

    /**
     * 校验当前登录用户是否为管理员，不是就直接拒绝。
     *
     * <p>刻意做成「调用即校验」而不是返回 {@code boolean}：返回布尔值时，调用方漏写 {@code if}
     * 就是一个<b>静默的越权漏洞</b>（不报错、不记日志、功能还"正常"）；现在没有"忘记处理返回值"这种失败模式。
     *
     * <p>调用方不需要自己先判断登录 —— 内部复用 {@link #getLoginUser()}，未登录时它已经抛了 NOT_LOGIN_ERROR。
     *
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100），或当前用户不是管理员（40101）
     */
    void checkAdminUser();

    /**
     * 【管理员】新增用户。
     *
     * @param userAddRequest 新增请求（账号、密码、昵称/头像/简介、可选角色）
     * @return 新用户的 id（字符串形式）
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）／参数不合法（40000）／账号已存在（40000）
     */
    String userAdd(UserAddRequest userAddRequest);

    /**
     * 【管理员】更新用户资料（昵称 / 头像 / 简介 / 角色）。
     *
     * <p>为 null 的字段表示"不改"（靠 MP 的 NOT_NULL 更新策略实现，不是手写动态 SQL）。
     *
     * @param userUpdateRequest 更新请求
     * @return 是否更新成功
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）／用户不存在（40000）／试图取消自己的管理员身份（40000）
     */
    boolean userUpdate(UserUpdateRequest userUpdateRequest);

    /**
     * 【管理员】删除用户（逻辑删除：只置 isDelete，不真删行）。
     *
     * @param id 用户 id（字符串形式）
     * @return 是否删除成功
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）／用户不存在（40000）／删除自己（40000）
     */
    boolean userDelete(String id);

    /**
     * 【管理员】按 id 查询单个用户（脱敏）。
     *
     * @param id 用户 id（字符串形式）
     * @return 脱敏用户信息
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）／用户不存在（40000）
     */
    UserVO getUserById(String id);

    /**
     * 【管理员】分页查询用户（可按 id / 账号 / 昵称 / 简介 / 角色过滤）。
     *
     * @param userQueryRequest 分页 + 查询条件
     * @return 分页结果（每页条数已被 Service 夹到上限内）
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）
     */
    Page<UserVO> listUserByPage(UserQueryRequest userQueryRequest);

    /**
     * 实体 → 脱敏 VO（管理员场景）。
     *
     * <p>与 {@link #toLoginUserVO(User)} 字段相同但各写一份：两个 VO 代表两个独立的脱敏边界，
     * 让其中一边加字段时不必牵动另一边。
     *
     * @param user 用户实体，可为 null
     * @return 脱敏 VO，入参 null 时返回 null
     */
    UserVO toUserVO(User user);
}
