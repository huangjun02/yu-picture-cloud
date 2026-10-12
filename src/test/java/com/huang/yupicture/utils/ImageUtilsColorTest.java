package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#extractMainColor(byte[])} 的单元测试（纯函数，不连 COS / 数据库）。
 *
 * <p>造图方式与压缩模块的测试同源：程序生成纯色 / 双色 BufferedImage，
 * 编码成 byte[] 再喂给被测方法 —— 断言的是「给定已知颜色的图，提取结果符合预期」。
 */
class ImageUtilsColorTest {

    @BeforeAll
    static void setUp() {
        // 与其它 ImageUtils 测试同一套纪律：关掉 ImageIO 的磁盘缓存（临时文件依赖 tmpdir 可写）
        ImageIO.setUseCache(false);
    }

    /** 程序生成一张纯色图并编码为 JPEG byte[] */
    private byte[] solidImage(int rgb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void redImageShouldExtractRedFamily() throws Exception {
        byte[] bytes = solidImage(0xE74C3C, 120, 90);
        String color = ImageUtils.extractMainColor(bytes);
        assertNotNull(color, "正常 JPEG 必须能提取出主色");
        assertTrue(color.startsWith("#"), "颜色格式必须是 #RRGGBB，实际：" + color);
        assertEquals(7, color.length());
        // 纯红图：R 通道必须显著高于 G/B
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        assertTrue(r > g + 30 && r > b + 30, "纯红图提取出 R 应显著占优，实际：" + color);
    }

    @Test
    void blueImageShouldExtractBlueFamily() throws Exception {
        byte[] bytes = solidImage(0x1677FF, 120, 90);
        String color = ImageUtils.extractMainColor(bytes);
        assertNotNull(color);
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        assertTrue(b > r + 30 && b > g + 30, "纯蓝图提取出 B 应显著占优，实际：" + color);
    }

    @Test
    void extractShouldBeStableForSameInput() throws Exception {
        // 同一张图两次提取结果一致 —— 颜色要落库做筛选键，不确定性等于筛选失灵
        byte[] bytes = solidImage(0x2ECC71, 100, 100);
        assertEquals(ImageUtils.extractMainColor(bytes), ImageUtils.extractMainColor(bytes));
    }

    @Test
    void smallImageShouldStillExtract() throws Exception {
        // 缩略图级别的尺寸也能提（上传链路在压缩后提取，图可能很小）
        byte[] bytes = solidImage(0xF39C12, 40, 40);
        assertNotNull(ImageUtils.extractMainColor(bytes));
    }

    @Test
    void nullBytesShouldReturnNull() {
        assertNull(ImageUtils.extractMainColor(null));
    }

    @Test
    void corruptBytesShouldReturnNull() {
        // 不是图片的字节：解码失败返回 null（降级，不炸上传链路），不抛异常
        assertNull(ImageUtils.extractMainColor("not an image".getBytes()));
    }

    // ==================== 色系归类（筛选入口） ====================

    @Test
    void classifyShouldMapBasicColorsToTheirFamily() {
        assertEquals("white", ImageUtils.classifyColorFamily("#FFFFFF"));
        assertEquals("black", ImageUtils.classifyColorFamily("#000000"));
        assertEquals("red", ImageUtils.classifyColorFamily("#E74C3C"));
        assertEquals("orange", ImageUtils.classifyColorFamily("#E67E22"));
        assertEquals("yellow", ImageUtils.classifyColorFamily("#F1C40F"));
        assertEquals("green", ImageUtils.classifyColorFamily("#2ECC71"));
        assertEquals("cyan", ImageUtils.classifyColorFamily("#1ABC9C"));
        assertEquals("blue", ImageUtils.classifyColorFamily("#1677FF"));
        assertEquals("purple", ImageUtils.classifyColorFamily("#9B59B6"));
        assertEquals("brown", ImageUtils.classifyColorFamily("#5C3317"));
    }

    @Test
    void classifyShouldAbsorbNearbyValuesIntoTheSameFamily() {
        // 这是本功能最初崩掉的地方：提取器产出的是连续空间里的具体值，
        // 而筛选入口是离散色板 —— 「近似白」「近似蓝」必须和标准色归到同一色系
        assertEquals("white", ImageUtils.classifyColorFamily("#FDF9FB"), "近似白要归白");
        assertEquals("white", ImageUtils.classifyColorFamily("#FCFFFC"));
        assertEquals("blue", ImageUtils.classifyColorFamily("#338CFD"), "近似蓝要归蓝");
        assertEquals("black", ImageUtils.classifyColorFamily("#1A1A1A"));
    }

    @Test
    void classifyShouldBeCaseInsensitive() {
        assertEquals(ImageUtils.classifyColorFamily("#E74C3C"), ImageUtils.classifyColorFamily("#e74c3c"));
    }

    @Test
    void classifyShouldReturnNullForInvalidInput() {
        assertNull(ImageUtils.classifyColorFamily(null));
        assertNull(ImageUtils.classifyColorFamily(""));
        assertNull(ImageUtils.classifyColorFamily("red"));
        assertNull(ImageUtils.classifyColorFamily("#12345"));
        assertNull(ImageUtils.classifyColorFamily("#GGGGGG"));
    }

    /**
     * 色板不变式：**每个色板色必须归类回它自己代表的那个色系**。
     *
     * <p>色板在前端（{@code constants/colors.ts}），归类算法在后端，两边靠「色值 → 色系」
     * 这条函数关系对齐。踩过的坑：色板里的棕色 {@code #8B4513} 亮度 0.545、色相 25°，
     * 按规则归成了 orange —— 点「棕色」发出这个色值，后端却去查橙色图，功能静默错位。
     * 这条用例把「色板取值本身必须归类正确」钉住，改色板时它会先红。
     */
    @Test
    void everyPaletteColorShouldClassifyBackToItsOwnFamily() {
        String[][] palette = {
                {"#000000", "black"}, {"#FFFFFF", "white"}, {"#E74C3C", "red"},
                {"#E67E22", "orange"}, {"#F1C40F", "yellow"}, {"#2ECC71", "green"},
                {"#1ABC9C", "cyan"}, {"#1677FF", "blue"}, {"#9B59B6", "purple"},
                {"#5C3317", "brown"},
        };
        for (String[] pair : palette) {
            assertEquals(pair[1], ImageUtils.classifyColorFamily(pair[0]),
                    "色板色 " + pair[0] + " 应归类为 " + pair[1] + "（与前端 colors.ts 保持一致）");
        }
    }
}
