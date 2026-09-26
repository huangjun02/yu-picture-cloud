package com.huang.yupicture.utils;

import cn.dev33.satoken.secure.SaSecureUtil;
import com.huang.yupicture.constant.UserConstant;

/**
 * 密码加盐摘要工具。
 *
 * <p>用 Sa-Token 自带的 {@link SaSecureUtil}，不额外引 BCrypt/Spring Security ——
 * 依赖已经在了，何必再加一个。
 *
 * <p>对外只暴露两个方法：{@link #encrypt} 和 {@link #matches}。
 * 调用方永远拿不到「盐」和「算法」，将来整体换成 BCrypt 也只改这一个类。
 *
 * <p>算法选 sha256 而不是 md5：Sa-Token 1.46 已把 {@code md5BySalt} 标记为 {@code @Deprecated}，
 * 且 md5 的碰撞/爆破成本早就低到不可接受。两者都是「加盐摘要」，概念一样 ——
 * 课程里用 md5 是历史习惯，不必照抄。
 * 真要上生产，选 BCrypt/Argon2：它们每次生成随机盐、并可调"慢"参数，安全模型更强。
 */
public class PasswordUtils {

    /** 工具类不允许实例化 */
    private PasswordUtils() {
    }

    /**
     * 加密（注册、改密码时用）。
     *
     * @param rawPassword 明文密码
     * @return 密文
     */
    public static String encrypt(String rawPassword) {
        return SaSecureUtil.sha256BySalt(rawPassword, UserConstant.PASSWORD_SALT);
    }

    /**
     * 校验（登录时用）—— 把明文按同样规则再摘要一遍，和库里存的密文比对。
     *
     * <p>不需要知道盐和算法的细节，只要能「算出同一个值」即可，
     * 这样算法升级对调用方透明。
     */
    public static boolean matches(String rawPassword, String encryptedPassword) {
        if (rawPassword == null || encryptedPassword == null) {
            return false;
        }
        return encrypt(rawPassword).equals(encryptedPassword);
    }
}
