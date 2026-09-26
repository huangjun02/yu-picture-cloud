package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 脱敏后的登录用户信息 —— 专给前端看的视图对象。
 *
 * <p>为什么不能直接返回 {@code User} 实体？实体里有 userPassword。
 * 一旦直接返回，密文就会随 JSON 流到浏览器（打开 F12 就能看到），
 * 等于把「离线爆破的原料」白送出去。VO 是「只暴露该暴露的字段」的边界。
 */
@Data
public class LoginUserVO implements Serializable {

    /**
     * 用户 id —— <b>故意用 String，不是 Long</b>。
     *
     * <p>雪花算法产出 19 位 long（如 2103879065756463105），
     * 而 JS 的 Number 只能精确表示到 2^53-1（9007199254740991，16 位）。
     * 超出后 JS 会静默丢精度：2103879065756463105 → 2103879065756463000。
     *
     * <p>而这种误差不会报错 —— 前端拿着截断的 id 去请求，后端查不到数据，
     * 表现为「莫名其妙查不到」，极难排查。所以雪花 id 对外一律字符串化。
     */
    private String id;

    /** 账号 */
    private String userAccount;

    /** 昵称 */
    private String userName;

    /** 头像 URL */
    private String userAvatar;

    /** 简介 */
    private String userProfile;

    /** 角色：user / admin */
    private String userRole;

    /** 注册时间 */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
