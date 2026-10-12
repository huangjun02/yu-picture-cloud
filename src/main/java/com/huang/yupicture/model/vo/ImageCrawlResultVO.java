package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量抓图结果。
 *
 * <p>刻意返回"逐条状态"而不是只给一个成功数：抓取会有一部分失败（源站链接失效、
 * 格式不支持、重复），只报总数的话用户无法判断"少了的那几张到底怎么了"。
 * 失败原因逐条回传，前端能直接列出来。
 */
@Data
public class ImageCrawlResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词 */
    private String query;

    /** 源站返回的候选数 */
    private int candidateCount;

    /** 成功入库数 */
    private int successCount;

    /** 因重名/重复地址跳过数（幂等：同一个关键词再抓一次不会翻倍） */
    private int skipCount;

    /** 失败数 */
    private int failCount;

    /** 成功入库的图片 id（前端可用来跳转查看） */
    private List<String> successIds = new ArrayList<>();

    /** 逐条明细 */
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 入库后的图片名（成功时） */
        private String name;

        /** 源站图片地址 */
        private String sourceUrl;

        /** SUCCESS / SKIP / FAIL */
        private String status;

        /** 跳过或失败的原因 */
        private String reason;
    }
}
