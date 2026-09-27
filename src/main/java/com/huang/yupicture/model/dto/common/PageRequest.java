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

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数（上限由 Service 夹取，防止前端传 100000 把全表拉出来） */
    private Integer pageSize = 10;
}
