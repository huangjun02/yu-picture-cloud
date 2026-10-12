package com.huang.yupicture.service;

import com.huang.yupicture.model.dto.image.ImageCrawlRequest;
import com.huang.yupicture.model.vo.ImageCrawlResultVO;

/**
 * 批量抓图（管理员）。
 *
 * <p>单独一个 Service 而不是塞进 ImageService：抓取是"调用外部源 + 编排"，
 * 与图片本身的增删改查不是一类职责；塞进去会让本来就很长的 ImageServiceImpl 更难读。
 */
public interface ImageCrawlService {

    /**
     * 按关键词抓取一批图片入库。
     *
     * @param request 关键词 / 数量 / 分类 / 标签
     * @return 逐条状态与统计
     */
    ImageCrawlResultVO crawl(ImageCrawlRequest request);
}
