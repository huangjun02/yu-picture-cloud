package com.huang.yupicture.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户登录请求体（对应前端 POST /api/user/login 的 body）。
 *
 * <p>⚠️ 这里<b>故意不加</b> {@code @NotBlank} / {@code @Size} 之类注解：
 * 本项目不引 Bean Validation，校验一律手写在 Service 里（见 {@code UserServiceImpl#userLogin}）。
 * 好处是校验逻辑和错误码集中在一处，坏处是每个接口都得自己写 —— 项目大了可以再重新权衡。
 *
 * <p>DTO 与实体分开的意义：请求参数是「外部输入」，随时可能变（加验证码、加记住我），
 * 而 User 实体对应数据库表结构，不该被外部输入牵着动。
 */
@Data
public class UserLoginRequest implements Serializable {

    /** 账号 */
    private String userAccount;

    /** 密码（明文传输，由 HTTPS 保证传输安全，服务端不存明文） */
    private String userPassword;

    private static final long serialVersionUID = 1L;
}
