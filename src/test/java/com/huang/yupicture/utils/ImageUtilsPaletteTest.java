package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#extractPalette(byte[])} 的单元测试（纯函数，不连 COS / 数据库）。
 *
 * <p><b>为什么造图用 PNG 而不是 JPEG：</b>JPEG 有损，纯色块的边界会被压出渐变，
 * 条带的「占比」和「色值」都不再精确；而本类断言的是比例与色值本身，
 * 必须用无损格式才谈得上确定性（{@code ImageUtilsColorTest} 断言的是通道大小关系，
 * 容得下 JPEG 的偏差，所以那边继续用 jpg）。
 */
class ImageUtilsPaletteTest {

    @BeforeAll
    static void setUp() {
        ImageIO.setUseCache(false);
    }

    /**
     * 造一张「按比例横向分条带」的图：每条带一个纯色，条带高度 = 比例 × 图高。
     *
     * @param colors 颜色（RGB 整数，如 {@code 0xE74C3C}），自上而下
     * @param ratios 各颜色占比，和为 1.0
     */
    private byte[] stripedImage(int w, int h, int[] colors, double[] ratios) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int row = 0;
        for (int i = 0; i < colors.length; i++) {
            // 最后一条用「剩下的全部高度」，避免四舍五入把末条挤成 0 行
            int rows = (i == colors.length - 1) ? h - row : (int) Math.round(h * ratios[i]);
            for (int y = row; y < Math.min(row + rows, h); y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, colors[i]);
                }
            }
            row += rows;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void singleColorImageShouldYieldOneEntryAtFullRatio() throws Exception {
        byte[] bytes = stripedImage(80, 80, new int[]{0xE74C3C}, new double[]{1.0});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(1, palette.size(), "纯色图只该有一条主色，实际：" + palette);
        // PNG 无损 + 单色 → 均值就是原值本身，可以断言精确值
        assertEquals("#E74C3C", palette.get(0).color());
        assertEquals(100, palette.get(0).ratio());
        assertEquals("red", palette.get(0).family(), "色系要顺手归类好（筛选靠它）");
    }

    /**
     * 本功能存在的理由：**白底 + 彩色主体**的图（动漫壁纸的典型形态）。
     *
     * <p>单主色方案下这种图只认「白」—— 用户点「蓝色」永远搜不到它，
     * 而这恰恰是最该搜到的一类图。调色板方案必须让主体色也进榜。
     */
    @Test
    void whiteBackgroundWithColorfulSubjectShouldListBothColors() throws Exception {
        // 75% 近白（模拟壁纸留白）+ 25% 亮蓝（主体）
        byte[] bytes = stripedImage(100, 100,
                new int[]{0xFDF9FB, 0x338CFD}, new double[]{0.75, 0.25});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(2, palette.size(), "背景色和主体色都该进榜，实际：" + palette);
        assertEquals("white", palette.get(0).family(), "占比大的白该排第一（主色语义不变）");
        assertEquals("blue", palette.get(1).family(),
                "主体色必须进调色板 —— 否则点「蓝色」搜不到白底蓝主体的图（本功能要解决的就是它）");
        // 占比按「采样到的像素」统计，每 4 个像素采 1 个，条带边界上会有几个百分点的偏差 —— 断言量级即可
        assertTrue(Math.abs(palette.get(0).ratio() - 75) <= 5,
                "背景占比应接近 75%，实际：" + palette.get(0).ratio());
        assertTrue(Math.abs(palette.get(1).ratio() - 25) <= 5,
                "主体占比应接近 25%，实际：" + palette.get(1).ratio());
    }

    @Test
    void threeComparableColorsShouldAllAppear() throws Exception {
        byte[] bytes = stripedImage(90, 90,
                new int[]{0xE74C3C, 0x2ECC71, 0x1677FF}, new double[]{0.34, 0.33, 0.33});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(3, palette.size(), "三块面积相当的颜色都该进榜，实际：" + palette);
        List<String> families = List.of(palette.get(0).family(), palette.get(1).family(), palette.get(2).family());
        assertTrue(families.containsAll(List.of("red", "green", "blue")), "实际：" + palette);
    }

    @Test
    void tinyAccentColorShouldBeDropped() throws Exception {
        // 95% 白 + 5% 红（一点点点缀）：5% 低于门槛，不该在色板上给出一个「红色」的假选项
        byte[] bytes = stripedImage(100, 100,
                new int[]{0xFFFFFF, 0xE74C3C}, new double[]{0.95, 0.05});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(1, palette.size(), "零星像素不构成「这张图的颜色」，实际：" + palette);
        assertEquals("white", palette.get(0).family());
    }

    @Test
    void similarColorsShouldMergeIntoOneEntry() throws Exception {
        // 两个几乎一样的灰（曼哈顿距离 5）：是「同一种颜色的不同深浅」，不该占两个名额
        byte[] bytes = stripedImage(100, 100,
                new int[]{0x808080, 0x858585}, new double[]{0.5, 0.5});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(1, palette.size(), "相近色必须合并，否则调色板会被同一颜色的深浅填满，实际：" + palette);
    }

    @Test
    void distinctColorsShouldNotMerge() throws Exception {
        // 红与绿相距很远，绝不能被并成一个
        byte[] bytes = stripedImage(100, 100,
                new int[]{0xE74C3C, 0x2ECC71}, new double[]{0.5, 0.5});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(2, palette.size(), "差异明显的颜色不该被合并，实际：" + palette);
    }

    @Test
    void paletteSizeShouldBeCapped() throws Exception {
        // 五块等分：受 PALETTE_SIZE 限制只出前三条（大色块优先）
        byte[] bytes = stripedImage(100, 100,
                new int[]{0xE74C3C, 0x2ECC71, 0x1677FF, 0xF1C40F, 0x9B59B6},
                new double[]{0.4, 0.2, 0.2, 0.1, 0.1});
        List<ImageUtils.PaletteColor> palette = ImageUtils.extractPalette(bytes);

        assertEquals(ImageUtils.PALETTE_SIZE, palette.size(), "调色板要有容量上限，实际：" + palette);
    }

    @Test
    void extractionShouldBeDeterministic() throws Exception {
        // 颜色要落库当检索键，同一张图两次跑出不同结果 = 筛选失灵
        byte[] bytes = stripedImage(100, 100,
                new int[]{0xE74C3C, 0x2ECC71}, new double[]{0.5, 0.5});
        assertEquals(ImageUtils.extractPalette(bytes), ImageUtils.extractPalette(bytes));
    }

    @Test
    void mainColorShouldBeTheTopPaletteEntry() throws Exception {
        // 两个 API 的关系：主色 = 调色板第一名（单主色是调色板的特例，不是另一套算法）
        byte[] bytes = stripedImage(100, 100,
                new int[]{0x338CFD, 0xFDF9FB}, new double[]{0.7, 0.3});
        assertEquals(ImageUtils.extractPalette(bytes).get(0).color(), ImageUtils.extractMainColor(bytes));
    }

    @Test
    void corruptBytesShouldYieldEmptyPalette() {
        assertTrue(ImageUtils.extractPalette("not an image".getBytes()).isEmpty());
        assertTrue(ImageUtils.extractPalette(null).isEmpty());
        assertTrue(ImageUtils.extractPalette(new byte[0]).isEmpty());
    }

    @Test
    void paletteJsonShouldBeStableAndCarryFamily() throws Exception {
        // 落库格式是「写侧与读侧」的契约：字段名/顺序固定，改这里等于改库里的数据格式
        byte[] bytes = stripedImage(80, 80, new int[]{0xE74C3C}, new double[]{1.0});
        String json = ImageUtils.paletteToJson(ImageUtils.extractPalette(bytes));
        assertEquals("[{\"color\":\"#E74C3C\",\"ratio\":100,\"family\":\"red\"}]", json);
    }

    @Test
    void emptyPaletteShouldSerializeToEmptyArray() {
        // 空数组而不是 null/空串：读取侧（前端、SQL 的 JSON_CONTAINS）行为一致
        assertEquals("[]", ImageUtils.paletteToJson(List.of()));
    }
}
