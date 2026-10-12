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
}
