package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量审核请求（管理员）。
 *
 * <p>与单条 {@link ImageReviewRequest} 的字段语义完全一致（结论 / 意见 / 重置开关），
 * 只是对象从「一个 id」变成「一批 id」。校验规则也一致：
 * 拒绝必填原因（会写入意见时）——这条在<b>进循环之前</b>整批校验，
 * 原因缺失报参数错误，而不是审到一半才发现白跑一趟。
 */
@Data
public class ImageBatchReviewRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 要审核的图片 id 列表（字符串形式的雪花 id），非空、数量 ≤ 100 */
    private List<String> imageIds;

    /** 审核结论：1-通过 / 2-拒绝（其它值报参数错误） */
    private Integer reviewStatus;

    /** 审核意见（拒绝时必填，整批共用同一条原因） */
    private String reviewMessage;

    /** 是否重置全部审核参数；null 按「重置」处理（与单条审核同一约定） */
    private Boolean resetReviewParams;
}
