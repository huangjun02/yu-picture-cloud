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
}
