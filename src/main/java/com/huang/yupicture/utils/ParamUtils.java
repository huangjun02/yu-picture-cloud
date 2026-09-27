package com.huang.yupicture.utils;

import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;

/**
 * 参数处理工具。
 *
 * <p><b>抽出来的时机值得记一下：</b>这两个方法是 user 模块写完时写的（当时是 {@code UserServiceImpl}
 * 里的 private 方法），图片模块出现了<b>第二个</b>要用它们的类，才提到这里。
 * 一个场景就抽是猜需求；第二个场景出现时才抽是消除真实重复。
 *
 * <p>为什么不用 {@code StringUtils.isBlank}（commons-lang3）：为一个方法引一个依赖不划算，
 * 项目当前也没有这个依赖。
 */
public final class ParamUtils {

    /** 工具类不需要实例 —— {@code final class} + 私有构造把这个意图写进代码，也挡住继承 */
    private ParamUtils() {
    }

    /** null 或纯空白都算"没填" */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 把前端传来的字符串 id 解析成 long。
     *
     * <p>DTO 里的 id 是 String（雪花 id 必须字符串化，见 {@code LoginUserVO#id}），
     * 而数据库列是 bigint —— 这个转换点必须显式存在，且失败要变成 40000 参数错误。
     * 否则 {@code Long.parseLong} 抛的 {@code NumberFormatException} 会一路冒到
     * {@code GlobalExceptionHandler} 的 {@code Exception} 分支，被报成"系统内部异常"(50000)：
     * 前端以为服务器挂了，实际只是传了个 "abc"。
     */
    public static long parseId(String id) {
        if (isBlank(id)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不能为空");
        }
        long value;
        try {
            value = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 格式不正确");
        }
        if (value <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不合法");
        }
        return value;
    }
}
