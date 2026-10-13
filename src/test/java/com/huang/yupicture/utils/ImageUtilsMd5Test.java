package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ImageUtils#md5(byte[])} 的单元测试（纯函数，不连 COS / 数据库）。
 *
 * <p>秒传的可用性全部押在「同一份字节必须算出同一个指纹」这一条性质上 ——
 * 指纹要落库当唯一索引键（uk_md5），不确定 = 秒传永远不命中或误命中。
 * 用例重心：确定性、与 JDK MessageDigest 的一致性（算法对不对）、一比特之差的区分度。
 */
class ImageUtilsMd5Test {

    @BeforeAll
    static void setUp() {
        // 与其它 ImageUtils 测试同一套纪律：关掉 ImageIO 的磁盘缓存（临时文件依赖 tmpdir 可写）
        ImageIO.setUseCache(false);
    }

    /** 确定性：同一份字节算两次必须一模一样 */
    @Test
    void sameBytesShouldProduceSameMd5() {
        byte[] bytes = "秒传指纹测试".getBytes(StandardCharsets.UTF_8);
        assertEquals(ImageUtils.md5(bytes), ImageUtils.md5(bytes));
    }

    /** 算法正确性：与 JDK MessageDigest 直接算的结果一致（32 位小写十六进制） */
    @Test
    void shouldMatchJdkMessageDigest() throws Exception {
        byte[] bytes = "hello yu-picture-cloud".getBytes(StandardCharsets.UTF_8);
        byte[] expected = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : expected) {
            sb.append(String.format("%02x", b));
        }
        assertEquals(sb.toString(), ImageUtils.md5(bytes));
    }

    /** 形状：128 位 → 32 个十六进制字符（定长 CHAR(32) 的依据） */
    @Test
    void md5ShouldBe32HexChars() {
        String md5 = ImageUtils.md5(new byte[]{1, 2, 3});
        assertNotNull(md5);
        assertEquals(32, md5.length(), "实际：" + md5);
        assertEquals(md5, md5.toLowerCase(), "必须是小写十六进制");
    }

    /** 区分度：内容差一个字节，指纹必须不同（这是「改一个像素不命中秒传」的底层保证） */
    @Test
    void oneByteDifferenceShouldChangeMd5() {
        byte[] a = {1, 2, 3, 4};
        byte[] b = {1, 2, 3, 5};
        assertNotEquals(ImageUtils.md5(a), ImageUtils.md5(b));
    }

    /** 空输入：有明确返回（MD5 允许对空数组计算），不抛异常 —— 调用方不用先判空 */
    @Test
    void emptyBytesShouldStillWork() {
        assertNotNull(ImageUtils.md5(new byte[0]));
    }

    /** null 输入返回 null：与 perceptualHash 的「算不出就降级」同一纪律 */
    @Test
    void nullBytesShouldReturnNull() {
        assertNull(ImageUtils.md5(null));
    }

    // ==================== 复用同款造图 helper（与 Hash 测试语义一致） ====================

    /** 真实图片字节的指纹也要稳定且互不相同 */
    @Test
    void realImagesShouldHaveStableDistinctMd5() throws Exception {
        byte[] png = toPng(50, 40);
        byte[] other = toPng(50, 41);
        assertEquals(ImageUtils.md5(png), ImageUtils.md5(png));
        assertNotEquals(ImageUtils.md5(png), ImageUtils.md5(other));
    }

    private byte[] toPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
