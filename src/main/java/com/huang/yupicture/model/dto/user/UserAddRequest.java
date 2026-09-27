package com.huang.yupicture.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理员新增用户的请求体。
 *
 * <p>与 {@link UserRegisterRequest} 的差别：注册是"自己给自己开号"——
 * 角色固定普通用户、密码必须二次确认；这里是"管理员替别人开号"——
 * 可以指定角色，也不需要 checkPassword（不是自己在打密码，没有手抖这一说）。
 *
 * <p><b>刻意不含的字段：</b>{@code id} / {@code createTime} / {@code isDelete}。
 * 这些由数据库和框架生成；一旦在 DTO 里出现，前端就能传自定义主键、篡改删除标记。
 * 参数绑定是"DTO 有什么字段，请求体就能填什么"——DTO 的字段清单就是接收白名单。
 *
 * <p>不引 Bean Validation，校验手写在 Service（项目既定选择，见 requirements 5.1 第 5 条）。
 */
@Data
public class UserAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 账号（必填，Service 校验 4-20 位字母/数字/下划线） */
    private String userAccount;

    /** 密码（必填，明文传入，Service 加密后落库） */
    private String userPassword;

    /** 昵称（可空，不填默认取账号） */
    private String userName;

    /** 头像 URL（可空） */
    private String userAvatar;

    /** 简介（可空） */
    private String userProfile;

    /** 角色：user / admin（可空，不填默认 user；传了必须是这两个之一） */
    private String userRole;
}
