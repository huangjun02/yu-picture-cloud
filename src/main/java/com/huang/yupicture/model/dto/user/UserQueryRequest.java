package com.huang.yupicture.model.dto.user;

import com.huang.yupicture.model.dto.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理员分页查询用户的请求体。
 *
 * <p>所有查询条件都是<b>可选</b>的：字段为 null / 空串就不参与 WHERE（Service 里逐个判空拼接）。
 * 所以前端可以只传分页参数拿"全量分页"，也能叠加账号、昵称、简介、角色做过滤。
 *
 * <p>{@code @EqualsAndHashCode(callSuper = true)} 是继承 {@code @Data} 类的必备注解，
 * 原因见 {@link PageRequest} 的注释。
 *
 * <p>模糊 / 精确的分工：账号、昵称、简介用 like（人只记得一部分）；
 * 角色用 eq（"adm" 不该查到 "admin"）。分界线是"这一列是给人回忆的，还是给程序判断的"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /** 用户 id（精确匹配） */
    private String id;

    /** 账号（模糊匹配） */
    private String userAccount;

    /** 昵称（模糊匹配） */
    private String userName;

    /** 简介（模糊匹配） */
    private String userProfile;

    /** 角色（精确匹配：user / admin） */
    private String userRole;
}
