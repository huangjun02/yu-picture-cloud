package com.huang.yupicture.tmp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.utils.ImageUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 一次性维护任务：回填存量图的感知哈希（以图搜图用）。
 *
 * <p>功能上线前传的图 {@code picHash} 是 NULL → 搜不到。做法：拉 COS 原图重算一次。
 * 哈希是像素的统计量，「重算」无副作用（不像压缩会掉画质）。
 *
 * <p>⚠️ 不放在常规测试里跑：没有 @Transactional（要真落库）且逐张打网络。
 * 用环境变量守住，默认跳过。跑一次即可，之后可删。
 */
@SpringBootTest
class PicHashBackfillTest {

    @Resource
    private ImageService imageService;

    @Resource
    private CosManager cosManager;

    @Test
    void backfillPicHash() {
        Assumptions.assumeTrue("1".equals(System.getenv("PIC_HASH_BACKFILL")),
                "一次性维护任务：设 PIC_HASH_BACKFILL=1 才执行");

        List<Image> images = imageService.list(new QueryWrapper<Image>().orderByAsc("id"));
        int updated = 0;
        int skipped = 0;
        for (Image image : images) {
            if (image.getPicHash() != null) {
                continue;   // 已有哈希的跳过 —— 任务可重复执行，不会白拉一遍 COS
            }
            String key;
            try {
                key = cosManager.getKeyFromUrl(image.getUrl());
            } catch (Exception e) {
                System.out.println("[哈希回填] 跳过（URL 不是本项目 COS 格式）: " + image.getId());
                skipped++;
                continue;
            }
            try {
                byte[] bytes = cosManager.download(key);
                String hash = ImageUtils.perceptualHash(bytes);
                if (hash == null) {
                    System.out.println("[哈希回填] 跳过（解码失败）: id=" + image.getId() + " name=" + image.getName());
                    skipped++;
                    continue;
                }
                Image update = new Image();
                update.setId(image.getId());
                update.setPicHash(hash);
                imageService.updateById(update);
                updated++;
                System.out.printf("[哈希回填] %s → %s（%s）%n", image.getId(), hash, image.getName());
            } catch (Exception e) {
                System.out.println("[哈希回填] 失败: id=" + image.getId() + " 原因=" + e.getMessage());
                skipped++;
            }
        }
        System.out.printf("[哈希回填] 完成：updated=%d, skipped=%d, total=%d%n",
                updated, skipped, images.size());
    }
}
