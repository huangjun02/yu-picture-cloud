package com.huang.yupicture.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理员更新用户的请求体。
 *
 * <p><b>为什么字段比"新增"还少，也不直接拿 {@code User} 实体当请求体：</b>
 * 更新能力必须比新增更窄。这里刻意<b>不含</b>：
 * <ul>
 *   <li>{@code userPassword} —— 改密码是独立动作（重置密码接口）。混进来会让
 *       "顺手改个昵称"变成"顺带把密码也改了"，日志里还看不出是谁干的</li>
 *   <li>{@code userAccount} —— 账号是登录凭据，改掉等于让本人登录不上；
 *       真要改名得单开接口，并且要通知用户</li>
 *   <li>{@code createTime} / {@code isDelete} —— 系统维护字段，暴露出去等于允许篡改</li>
 * </ul>
 * 直接接实体的后果是「过度绑定」：前端想传什么就能写什么，而这层白名单是
 * 手写校验之外的<b>第二道防线</b>（手写校验管"值合不合法"，DTO 管"这个字段压根不该被改"）。
 */
@Data
public class UserUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 要更新的用户 id（字符串形式的雪花 id） */
    private String id;

    /** 昵称（可空；为 null 表示不改这个字段） */
    private String userName;

    /** 头像 URL（可空；为 null 表示不改） */
    private String userAvatar;

    /** 简介（可空；为 null 表示不改） */
    private String userProfile;

    /** 角色：user / admin（可空；为 null 表示不改） */
    private String userRole;
}
