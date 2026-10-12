package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量抓图请求。
 *
 * <p>字段用包装类型（Integer）而不是 int：{@code count} 不传时要是 null 才能区分
 * "用默认值" 和 "显式传了 0"，也才挡得住 JSON 里显式 {@code "count": null}。
 */
@Data
public class ImageCrawlRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 检索关键词（必填），同时作为默认分类 */
    private String query;

    /** 抓取数量，1 ~ maxCount，不传默认 10 */
    private Integer count;

    /** 分类，不传则用关键词 */
    private String category;

    /** 标签，逗号分隔（与手写编辑同一个约定，会经 normalizeTags 规范化） */
    private String tags;
}
