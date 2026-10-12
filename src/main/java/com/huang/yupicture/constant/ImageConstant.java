package com.huang.yupicture.constant;

/**
 * 图片模块常量。
 *
 * <p>审核状态用 int 常量而不是 enum：MP 存库是 int，前端契约也是 int（0/1/2），
 * 中间再转一道 enum 只多了映射代码没有多出安全性 —— 与 user 模块的
 * {@code DEFAULT_ROLE} 字符串常量同一个取舍。
 */
public final class ImageConstant {

    /** 审核状态：待审核（上传 / URL 传图入库时的默认值） */
    public static final int REVIEW_STATUS_PENDING = 0;

    /** 审核状态：通过 */
    public static final int REVIEW_STATUS_PASS = 1;

    /** 审核状态：拒绝 */
    public static final int REVIEW_STATUS_REJECT = 2;

    private ImageConstant() {
    }
}
