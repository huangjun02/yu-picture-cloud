package com.huang.yupicture.model.dto.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页请求基类。
 *
 * <p>抽成基类的理由：分页参数（页码、每页条数）跟具体业务无关，
 * 图片模块、空间模块的查询请求都要用。每个 DTO 各写一遍，将来改口径要改五处。
 *
 * <p>⚠️ 继承它的子类记得加 {@code @EqualsAndHashCode(callSuper = true)}：
 * Lombok 的 {@code @Data} 会生成 equals/hashCode 但不带父类字段，IDE 会黄着提示。
 * 不修不影响运行，但属于"看着像 bug"的坏味道。
 *
 * <p>字段用 {@code Integer} 而不是 {@code int}：前端显式传 {@code null} 时会覆盖掉字段初始值，
 * 所以 Service 侧必须再兜一次 null（不能指望这里的默认值）。
 */
@Data
public class PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 每页条数上限 —— requirements 6.2：列表接口强制分页，默认每页 ≤ 20 条 */
    public static final int MAX_PAGE_SIZE = 20;

    /** 前端没传 pageSize 时的默认条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数（上限由 {@link #resolvePageSize()} 夹取） */
    private Integer pageSize = 10;

    /**
     * 取出"能安全交给数据库"的页码。
     *
     * <p>null（前端没传，或显式传了 null）与 &lt; 1（0 / 负数）都归到第一页。
     * 页码是"展示偏好"不是业务数据，越界无害 —— 纠正比抛错体验好；但也不能不管：
     * 0 / 负数传进 MySQL 会变成 {@code LIMIT -10, 10}，直接语法报错。
     *
     * <p>⚠️ 已知缺口：页码<b>没有上限</b>。{@code pageNum=1000000} 会生成巨大的 OFFSET，
     * MySQL 要先扫过这一千万行才返回数据（"深翻页"慢查询）。当前数据量下无所谓，先记着。
     */
    public long resolvePageNum() {
        return (pageNum == null || pageNum < 1) ? 1 : pageNum;
    }

    /**
     * 取出"能安全交给数据库"的每页条数。
     *
     * <p>两级保护：null / 0 / 负数 → 默认 10；超过上限 → 夹到 20。
     * 上限是硬需求：{@code pageSize=100000} 意味着一次读十万行出来，
     * 数据库、网络、JVM 内存全都要扛（requirements 6.2）。
     *
     * <p><b>⚠️ 别把这段简化成一行 {@code Math.min(值, MAX_PAGE_SIZE)}：</b>
     * 那样 {@code pageSize=0} 会算出 0 而不是默认 10，生成 {@code LIMIT 0} 返回空列表 ——
     * <b>能写短不等于等价</b>。
     *
     * <p><b>为什么放在基类而不是各 Service 里：</b>这段逻辑原本写在 {@code UserServiceImpl} 里，
     * 图片模块出现时成了第二处重复，这才抽上来。第一个场景就抽是猜需求，第二个场景出现时才抽
     * 是消除真实重复 —— 判断依据是"已经有第二处了"，不是"看起来以后会有"。
     */
    public long resolvePageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
