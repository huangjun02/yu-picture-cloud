package com.huang.yupicture.constant;

/**
 * 用户相关常量。
 *
 * <p>常量集中放这里，避免"密码盐值"这种关键值散落在 Service 里 ——
 * 改一处就能全局生效，也方便将来换成从配置中心读取。
 */
public interface UserConstant {

    /**
     * 密码加盐值。
     *
     * <p>直接 md5(密码) 会被彩虹表秒破（网上有现成的 md5 反查库），
     * 加一段只有服务端知道的盐再摘要，才需要"拿到盐 + 重算"才能反推。
     *
     * <p>⚠️ 学习项目放常量里够用；真实生产应放环境变量/配置中心，
     * 且算法优先选 BCrypt/Argon2（自带随机盐 + 慢哈希）。
     */
    String PASSWORD_SALT = "huang_yupicture";

    /** 默认角色 */
    String DEFAULT_ROLE = "user";

    /** 管理员角色 */
    String ADMIN_ROLE = "admin";
}
