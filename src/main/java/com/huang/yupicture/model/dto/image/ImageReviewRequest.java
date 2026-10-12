package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片审核请求（管理员）。
 *
 * <p>三个字段同属一次提交（id + 结论 + 意见），所以走请求体而不是 URL 参数 ——
 * 与 update 接口的多字段语义一致。
 */
@Data
public class ImageReviewRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 图片 id（字符串形式的雪花 id） */
    private String id;

    /** 审核结论：1-通过 / 2-拒绝（其它值后端报参数错误） */
    private Integer reviewStatus;

    /** 审核意见（拒绝时必填；通过时可不填） */
    private String reviewMessage;

    /**
     * 是否重置全部审核参数（审核人 / 审核时间 / 审核意见）。
     *
     * <p>重复审核才用得上这个开关：
     * <ul>
     *   <li>{@code true}（默认，兼容不传的老客户端）：本次审核人 / 时间 / 意见全部覆盖</li>
     *   <li>{@code false}：只改审核状态，上一次的审核人 / 时间 / 意见保留下来当历史参考</li>
     * </ul>
     * 用 Boolean 而不是 boolean：null 和 false 要区分开 —— null 按「重置」处理，
     * 否则老客户端（不传这个字段）会静默变成「不重置」，行为随版本漂移。
     */
    private Boolean resetReviewParams;
}
