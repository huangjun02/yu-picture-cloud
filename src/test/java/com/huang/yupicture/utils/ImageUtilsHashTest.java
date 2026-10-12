package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#perceptualHash(byte[])} / {@link ImageUtils#hammingDistance(String, String)}
 * 的单元测试（纯函数，不连 COS / 数据库）。
 *
 * <p>本功能的可用性全部押在「同一张图的不同版本必须算出几乎相同的哈希」这一条性质上，
 * 所以用例的重心是<b>编码不变性</b>与<b>不同图的区分度</b>，而不是「方法能不能跑」。
 */
class ImageUtilsHashTest {

    @BeforeAll
    static void setUp() {
        // 与其它 ImageUtils 测试同一套纪律：关掉 ImageIO 的磁盘缓存（临时文件依赖 tmpdir 可写）
        ImageIO.setUseCache(false);
    }

    // ==================== 确定性 / 形状 ====================

    /** 确定性：同一份字节算两次必须一模一样 —— 哈希要落库当检索键，不确定等于检索失灵 */
    @Test
    void sameImageShouldProduceSameHash() throws Exception {
        byte[] bytes = gradientImage();
        assertEquals(ImageUtils.perceptualHash(bytes), ImageUtils.perceptualHash(bytes));
    }

    /** 形状：64 位 → 16 个十六进制字符（定长 CHAR(16) 的依据） */
    @Test
    void hashShouldBe16HexChars() throws Exception {
        String hash = ImageUtils.perceptualHash(gradientImage());
        assertNotNull(hash);
        assertEquals(16, hash.length(), "实际：" + hash);
        assertTrue(hash.matches("[0-9a-f]{16}"), "必须是 16 位小写十六进制，实际：" + hash);
    }

    // ==================== 区分度 / 不变性 ====================

    /** 同一张图的不同编码 / 尺寸，哈希必须几乎不变 —— 这是「以图搜图」能用的前提 */
    @Test
    void sameImageInDifferentEncodingsShouldStayClose() throws Exception {
        BufferedImage img = photoishImage();
        String png = ImageUtils.perceptualHash(toBytes(img, "png"));
        String jpg = ImageUtils.perceptualHash(toBytes(img, "jpg"));

        assertNotNull(png);
        assertNotNull(jpg);
        int distance = ImageUtils.hammingDistance(png, jpg);
        assertTrue(distance <= 5, "PNG 与 JPEG 编码的同一张图必须几乎同哈希，实际距离=" + distance);
    }

    /** 完全不同的两张图必须离得远（否则搜出来的全是无关图） */
    @Test
    void differentImagesShouldBeFarApart() throws Exception {
        String gradient = ImageUtils.perceptualHash(gradientImage());
        String noise = ImageUtils.perceptualHash(noiseImage());
        int distance = ImageUtils.hammingDistance(gradient, noise);
        assertTrue(distance > 10, "实测不同场景的距离应 >10，实际=" + distance);
    }

    // ==================== 降级 / 边界 ====================

    @Test
    void nullOrCorruptBytesShouldReturnNull() {
        assertNull(ImageUtils.perceptualHash(null));
        assertNull(ImageUtils.perceptualHash(new byte[0]));
        // 解码失败返回 null（降级，不炸上传链路），不抛异常
        assertNull(ImageUtils.perceptualHash("not an image".getBytes()));
    }

    /** 4×4 的极小图也要能出哈希（上传链路在压缩后算，图可能很小） */
    @Test
    void tinyImageShouldStillHash() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x0AC823);
        assertNotNull(ImageUtils.perceptualHash(toBytes(img, "png")));
    }

    /**
     * 亮度不同的两张纯色图在 pHash 眼里是「一样」的。
     *
     * <p>这不是 bug 是设计特性（pHash 比结构不比颜色），但要把它钉成用例 ——
     * 将来有人发现「两张纯色图互为相似图」时，这里能告诉他这是已知行为，
     * 而不是让他去改算法。
     */
    @Test
    void solidImagesOfDifferentBrightnessAreTreatedAsSame() throws Exception {
        String white = ImageUtils.perceptualHash(solidImage(0xFFFFFF));
        String gray = ImageUtils.perceptualHash(solidImage(0x808080));
        int distance = ImageUtils.hammingDistance(white, gray);
        assertTrue(distance <= 10, "pHash 对亮度不敏感是已知特性（见计划风险节），实际距离=" + distance);
    }

    // ==================== 汉明距离自身的边界 ====================

    @Test
    void hammingDistanceShouldHandleInvalidInput() {
        // 非法输入返回 MAX_VALUE：让调用方的 distance <= 阈值 自然为 false，不用每处再判空
        assertEquals(Integer.MAX_VALUE, ImageUtils.hammingDistance(null, "0000000000000000"));
        assertEquals(Integer.MAX_VALUE, ImageUtils.hammingDistance("0000000000000000", null));
        assertEquals(Integer.MAX_VALUE, ImageUtils.hammingDistance("0000", "0000000000000000"));
        assertEquals(Integer.MAX_VALUE, ImageUtils.hammingDistance("zzzzzzzzzzzzzzzz", "0000000000000000"));
    }

    @Test
    void hammingDistanceShouldCountDifferingBits() {
        assertEquals(0, ImageUtils.hammingDistance("0000000000000000", "0000000000000000"));
        // 末位 f(1111) vs 0(0000) → 差 4 位
        assertEquals(4, ImageUtils.hammingDistance("0000000000000000", "000000000000000f"));
        // 全 1 vs 全 0 → 64 位全差
        assertEquals(64, ImageUtils.hammingDistance("ffffffffffffffff", "0000000000000000"));
        // 大小写不敏感
        assertEquals(4, ImageUtils.hammingDistance("000000000000000F", "0000000000000000"));
    }

    // ==================== 造图 helper ====================

    /** 斜向灰度渐变图（256×256）：确定性、有明确的低频结构 */
    private static byte[] gradientImage() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = (x + y) / 2;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img, "png");
    }

    /** 渐变底 + 一个大圆 + 一个矩形：模拟「一张照片」的低频结构 */
    private static BufferedImage photoishImage() {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = (x + y) / 2;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(200, 120, 40));
        g.fillOval(60, 60, 130, 130);
        g.setColor(new java.awt.Color(40, 90, 180));
        g.fillRect(10, 200, 110, 50);
        g.dispose();
        return img;
    }

    /** 白噪声（固定种子 → 确定性）：低频系数是乱的，与任何结构图都离得远 */
    private static byte[] noiseImage() throws Exception {
        java.util.Random random = new java.util.Random(42);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = random.nextInt(256);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img, "png");
    }

    private static byte[] solidImage(int rgb) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return toBytes(img, "png");
    }

    private static byte[] toBytes(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
