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

    /**
     * 管理员自动过审时写入的审核意见。
     *
     * <p>写成固定文案而不是留空：留空会让「管理员传的图」和「老数据没填意见」
     * 在库里长得一样，事后分不清这张图到底是自动过的还是人工过的。
     */
    public static final String AUTO_REVIEW_MESSAGE = "管理员自动过审";

    /** 分享短链码长度（随机 Base62 字符数）：62^8 ≈ 2.2e14，碰撞概率可忽略 */
    public static final int SHARE_CODE_LENGTH = 8;

    /**
     * 以图搜图：汉明距离阈值（64 位哈希里最多差这么多位仍算「相似」）。
     *
     * <p>实测标定：同一张图无论重新编码（PNG / JPEG q95 / q60 / 缩放一半 / 加噪点）
     * 距离都是 0，同场景去掉一个元素是 8；而不同场景最低 13（照片 vs 棋盘 23、
     * 照片 vs 噪声 32、纯色 vs 照片 47）。取 10 = 不漏「同图的不同版本」，也不误召无关图。
     */
    public static final int SIMILAR_MAX_DISTANCE = 10;

    /** 以图搜图：一次最多返回多少张（按相似度升序取前 N，最像的在最前） */
    public static final int SIMILAR_MAX_RESULT = 20;

    /**
     * 以图搜图：候选集扫描上限。
     *
     * <p>汉明距离是逐位比较，SQL 表达不了也没法走索引 —— 只能把候选捞回内存算。
     * 加上限是给自己一个兜底：图库要是涨到几十万张，这个查询会先撞上限而不是把内存吃光。
     * 到那个量级就该换向量库 / LSH 了。
     */
    public static final int SIMILAR_SCAN_LIMIT = 5000;


    private ImageConstant() {
    }
}
