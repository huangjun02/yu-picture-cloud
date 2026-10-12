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
 * 一次性维护任务：回填 / 重算存量图的「主色 + 色系」。
 *
 * <p><b>为什么需要它：</b>
 * <ul>
 *   <li>颜色功能上线前传的图 {@code picColor} 是 NULL → 完全不能按颜色筛；</li>
 *   <li>色系（{@code picColorFamily}）是后加的列，已有主色的图需要归类回填；</li>
 *   <li>提取算法修过一个量化位数的 bug（{@code >> 3} 少移了 2 位，桶 key 会撞），
 *       早期算出的主色值偏保守 —— 顺手重算一遍最省心。</li>
 * </ul>
 *
 * <p><b>做法：</b>拉 COS 原图 → {@code extractMainColor} 重算主色 → {@code classifyColorFamily}
 * 归类 → UPDATE 两列。主色是从像素统计出来的，「重算」没有副作用（不像压缩会掉画质）。
 *
 * <p>⚠️ <b>不放在常规测试里跑</b>：它没有 @Transactional（要真落库），
 * 且会打网络（逐张拉 COS）。所以用环境变量开关守住，默认跳过 ——
 * `COLOR_FAMILY_BACKFILL=1` 时才执行。跑一次即可，之后可删。
 */
@SpringBootTest
class ColorFamilyBackfillTest {

    @Resource
    private ImageService imageService;

    @Resource
    private CosManager cosManager;

    @Test
    void backfillMainColorAndFamily() {
        Assumptions.assumeTrue("1".equals(System.getenv("COLOR_FAMILY_BACKFILL")),
                "一次性维护任务：设 COLOR_FAMILY_BACKFILL=1 才执行");

        List<Image> images = imageService.list(new QueryWrapper<Image>().orderByAsc("id"));
        int updated = 0;
        int skipped = 0;
        for (Image image : images) {
            String key;
            try {
                key = cosManager.getKeyFromUrl(image.getUrl());
            } catch (Exception e) {
                System.out.println("[回填] 跳过（URL 不是本项目 COS 格式）: " + image.getId());
                skipped++;
                continue;
            }
            try {
                byte[] bytes = cosManager.download(key);
                String color = ImageUtils.extractMainColor(bytes);
                if (color == null) {
                    System.out.println("[回填] 跳过（解码失败）: id=" + image.getId() + " name=" + image.getName());
                    skipped++;
                    continue;
                }
                Image update = new Image();
                update.setId(image.getId());
                update.setPicColor(color);
                update.setPicColorFamily(ImageUtils.classifyColorFamily(color));
                imageService.updateById(update);
                updated++;
                System.out.printf("[回填] %s → picColor=%s family=%s（%s）%n",
                        image.getId(), color, update.getPicColorFamily(), image.getName());
            } catch (Exception e) {
                System.out.println("[回填] 失败: id=" + image.getId() + " 原因=" + e.getMessage());
                skipped++;
            }
        }
        System.out.printf("[回填] 完成：updated=%d, skipped=%d, total=%d%n", updated, skipped, images.size());
    }
}
