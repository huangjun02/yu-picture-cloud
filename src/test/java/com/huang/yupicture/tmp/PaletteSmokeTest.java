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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性核验：真实数据的色系分布（只读，不写库）。
 *
 * <p>回答两个问题：① 回填是否覆盖了全部图（有没有漏网的 NULL 色系）；
 * ② 点各个色块分别能筛出几张（色系分布）。
 *
 * <p>为什么走 Service 而不是直接写 SQL：Service 才是线上跑的那条路
 * （含 applyUserScope 可见性过滤），SQL 查出来的数字不等于用户看到的数字。
 *
 * <p>与回填任务同一个开关，默认跳过：{@code PALETTE_BACKFILL=1}。
 */
@SpringBootTest
class PaletteSmokeTest {

    @Resource
    private ImageService imageService;

    @Test
    void reportColorFamilyDistribution() {
        Assumptions.assumeTrue("1".equals(System.getenv("PALETTE_BACKFILL")),
                "一次性核验任务：设 PALETTE_BACKFILL=1 才执行");

        List<Image> all = imageService.list(new QueryWrapper<Image>().orderByAsc("id"));
        long nullColor = all.stream().filter(i -> i.getPicColor() == null).count();
        long nullPalette = all.stream().filter(i -> i.getPicPalette() == null).count();

        // 调色板里可能有多条色系（白底蓝主体的图同时属于 white 和 blue）→ 一张图计多票，
        // 所以下面的数字回答的是「点各色块能搜出几张」，不是「各色系有几张图」
        Pattern familyPattern = Pattern.compile("\"family\":\"([a-z]+)\"");
        Map<String, Integer> byFamily = new LinkedHashMap<>();
        Map<String, Integer> byFamilyPassed = new LinkedHashMap<>();
        for (Image image : all) {
            String palette = image.getPicPalette();
            if (palette == null) {
                continue;
            }
            boolean passed = image.getReviewStatus() != null
                    && image.getReviewStatus() == ImageConstant.REVIEW_STATUS_PASS;
            Matcher matcher = familyPattern.matcher(palette);
            while (matcher.find()) {
                String family = matcher.group(1);
                byFamily.merge(family, 1, Integer::sum);
                if (passed) {
                    byFamilyPassed.merge(family, 1, Integer::sum);
                }
            }
        }
        System.out.println("[核验] 总图数=" + all.size() + "，无主色=" + nullColor + "，无调色板=" + nullPalette);
        System.out.println("[核验] 各色系出现次数（一张图可计多票 = 点色块能搜出的张数）：");
        byFamily.forEach((family, count) -> System.out.println("[核验]   " + family + " = " + count));
        System.out.println("[核验] 已通过图（=主页点色块应看到的数量）：");
        byFamilyPassed.forEach((family, count) -> System.out.println("[核验]   已通过 " + family + " = " + count));

        // 不变式：回填跑完不该再有「没调色板的图」（那类图在筛选里会永远隐身）
        Assertions.assertEquals(0, nullPalette,
                "回填后仍有图缺调色板 —— 这些图在任何颜色筛选下都不会出现（静默隐身）");
        System.out.println("[核验] 通过：所有图片都有调色板，可按颜色筛出");
    }
}
