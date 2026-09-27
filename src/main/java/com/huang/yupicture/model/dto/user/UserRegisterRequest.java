package com.huang.yupicture.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户注册请求体（对应前端 POST /api/user/register 的 body）。
 *
 * <p>⚠️ 与 {@link UserLoginRequest} 一样<b>故意不加</b> {@code @NotBlank} / {@code @Size} 之类注解：
 * 本项目不引 Bean Validation，校验一律手写在 Service 里（见 {@code UserServiceImpl#userRegister}）。
 *
 * <p><b>为什么单独要一个 {@code checkPassword}：</b>"两次密码一致"只能由服务端判（前端判了也能绕过），
 * 所以它得跟着请求体一起传上来。但它是<b>纯校验字段</b>：既不进 {@code User} 实体、也不落库，用完即弃 ——
 * 这也是它不带 {@code user} 前缀的原因，一眼能看出与数据库字段不同源。
 */
@Data
public class UserRegisterRequest implements Serializable {

    /** 账号（4~20 位，仅字母数字下划线；唯一性由数据库唯一索引兜底） */
    private String userAccount;

    /** 密码（明文传输，由 HTTPS 保证传输安全，服务端只存摘要） */
    private String userPassword;

    /** 确认密码（仅用于服务端比对，不入库） */
    private String checkPassword;

    private static final long serialVersionUID = 1L;
}
