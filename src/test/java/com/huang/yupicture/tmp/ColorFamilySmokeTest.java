package com.huang.yupicture.tmp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.service.ImageService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性核验：真实数据的色系分布（只读，不写库）。
 *
 * <p>回答两个问题：① 回填是否覆盖了全部图（有没有漏网的 NULL 色系）；
 * ② 点各个色块分别能筛出几张（色系分布）。
 *
 * <p>为什么走 Service 而不是直接写 SQL：Service 才是线上跑的那条路
 * （含 applyUserScope 可见性过滤），SQL 查出来的数字不等于用户看到的数字。
 *
 * <p>与回填任务同一个开关，默认跳过：{@code COLOR_FAMILY_BACKFILL=1}。
 */
@SpringBootTest
class ColorFamilySmokeTest {

    @Resource
    private ImageService imageService;

    @Test
    void reportColorFamilyDistribution() {
        Assumptions.assumeTrue("1".equals(System.getenv("COLOR_FAMILY_BACKFILL")),
                "一次性核验任务：设 COLOR_FAMILY_BACKFILL=1 才执行");

        List<Image> all = imageService.list(new QueryWrapper<Image>().orderByAsc("id"));
        long nullColor = all.stream().filter(i -> i.getPicColor() == null).count();
        long nullFamily = all.stream().filter(i -> i.getPicColorFamily() == null).count();

        Map<String, Integer> byFamily = new LinkedHashMap<>();
        for (Image image : all) {
            if (image.getPicColorFamily() != null) {
                byFamily.merge(image.getPicColorFamily(), 1, Integer::sum);
            }
        }
        System.out.println("[核验] 总图数=" + all.size() + "，无主色=" + nullColor + "，无色系=" + nullFamily);
        byFamily.forEach((family, count) -> System.out.println("[核验] 色系 " + family + " = " + count + " 张"));

        // 主页浏览视角只显示「已通过」的图，用户点色块看到的数字来自这一层 ——
        // 报「点白色能筛出几张」必须用这个口径，否则会把可见性过滤当成筛选 bug
        Map<String, Integer> byFamilyPassed = new LinkedHashMap<>();
        for (Image image : all) {
            if (image.getReviewStatus() != null && image.getReviewStatus() == ImageConstant.REVIEW_STATUS_PASS
                    && image.getPicColorFamily() != null) {
                byFamilyPassed.merge(image.getPicColorFamily(), 1, Integer::sum);
            }
        }
        System.out.println("[核验] 已通过图按色系（=主页点色块应看到的数量）：");
        byFamilyPassed.forEach((family, count) -> System.out.println("[核验]   已通过 " + family + " = " + count + " 张"));

        // 不变式：回填跑完不该再有「有主色却没有色系」的图（那类图在筛选里会永远隐身）
        Assertions.assertEquals(0, nullFamily,
                "回填后仍有图缺色系 —— 这些图在任何颜色筛选下都不会出现（静默隐身）");
        System.out.println("[核验] 通过：所有图片都有色系，可按颜色筛出");
    }
}
