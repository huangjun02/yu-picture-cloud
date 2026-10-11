package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#compress(byte[], String)} 的单元测试。
 *
 * <p><b>不需要 Spring 容器、不碰 COS、不碰数据库</b> —— compress 是纯函数
 * （byte[] 进 byte[] 出），这正是把它放进 ImageUtils 而不是 Service 的理由之一：
 * 测试可以脱离一切外部设施直跑。
 *
 * <h3>造图数据的关键：JPEG 的 outputQuality 是「绝对质量」而不是「压缩力度」</h3>
 *
 * <p>这是本测试类最容易被写错的地方，实测数据（1200×1200 真实感图，各质量编码后再压 0.8）：
 * <pre>
 *   输入等效质量   原始大小      压 0.8 后      结果
 *   q1.00        1,444,741      267,445      有效 ✓ 小 82%
 *   q0.95          707,586      278,765      有效 ✓ 小 61%
 *   q0.90          456,647      307,970      有效 ✓ 小 33%
 *   q0.80          269,781      269,552      几乎无收益（1%）
 *   q0.75          222,170      222,170      守门拦截（不变大）
 * </pre>
 *
 * <p>JDK {@code ImageIO.write(img, "jpg", out)} 的默认质量约 <b>0.75</b>。所以
 * 用「默认写出的 JPEG」当输入去测「压缩应生效」是<b>必然失败</b>的用例 ——
 * 那不是代码有问题，是数据造错了（等于测「升质能不能减小体积」）。
 *
 * <p>结论：<b>要测「压缩生效」，输入必须比目标质量高</b>（真实场景 = 手机原图 q0.9+）；
 * 要测「守门」，输入就故意用低质量图。两个用例都需要，方向相反。
 */
class ImageUtilsCompressTest {

    /** 造数据的尺寸。够大才有压缩空间；太小的话 JPEG 头占比过高，噪声会盖过信号 */
    private static final int PHOTO_SIZE = 1200;

    @BeforeAll
    static void disableImageIoDiskCache() {
        // ⚠️ 这行必须在造数据之前执行。ImageIO 默认把编解码中间数据缓存到磁盘
        // （java.io.tmpdir 下建临时文件），tmpdir 出问题时直接抛
        // `IIOException: Can't create cache file!` —— 本测试类的造图代码比被测方法
        // 先运行，此时 ImageUtils 的静态块尚未触发，所以这里必须显式关一次。
        ImageIO.setUseCache(false);
    }

    // ==================== 造数据 ====================

    /**
     * 生成一张「像照片」的图：渐变天空 + 多边形 + 圆形 + 细噪声。
     *
     * <p>为什么不用纯随机噪声：噪声图几乎不可压缩，JPEG 对它的表现与真实照片差别很大，
     * 用它测出的结论不能迁移到生产。真实照片有大片相似区域 + 局部细节，JPEG 真正擅长的是这种。
     */
    private BufferedImage photoLike() {
        BufferedImage image = new BufferedImage(PHOTO_SIZE, PHOTO_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 80, 160),
                PHOTO_SIZE, PHOTO_SIZE, new Color(230, 220, 190)));
        g.fillRect(0, 0, PHOTO_SIZE, PHOTO_SIZE);
        g.setColor(new Color(40, 120, 60));
        g.fillPolygon(new int[]{0, PHOTO_SIZE / 3, PHOTO_SIZE * 2 / 3, PHOTO_SIZE},
                new int[]{PHOTO_SIZE, PHOTO_SIZE / 2, PHOTO_SIZE * 3 / 4, PHOTO_SIZE}, 4);
        g.setColor(new Color(255, 230, 120));
        g.fillOval(PHOTO_SIZE * 3 / 4, PHOTO_SIZE / 10, 90, 90);
        for (int i = 0; i < 40000; i++) {
            g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
            g.fillRect((int) (Math.random() * PHOTO_SIZE), (int) (Math.random() * PHOTO_SIZE), 1, 1);
        }
        g.dispose();
        return image;
    }

    /**
     * 用<b>显式指定的质量</b>把图编码成 JPEG。
     *
     * <p>不能图省事用 {@code ImageIO.write(img, "jpg", out)}：那个默认质量约 0.75，
     * 想要「高质量输入」这个前提就不成立了。必须走 ImageWriter + ImageWriteParam
     * 才能控制质量因子 —— 这也是本测试类唯一需要这么"啰嗦"的地方。
     */
    private byte[] jpegAtQuality(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** 生成纯色小 PNG（无损、信息量低、文件极小） */
    private byte[] tinyPng() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ==================== 用例 ====================

    @Test
    void compressShouldShrinkHighQualityPhoto() throws IOException {
        // 场景：用户上传手机拍的原图（q0.95 等效）—— 这是压缩功能存在的意义
        byte[] original = jpegAtQuality(photoLike(), 0.95f);
        byte[] compressed = ImageUtils.compress(original, "jpg");

        // 实测该输入压缩后约为原始的 39%（小 61%）。断言"至少小 20%"：
        // 留足编码器/平台差异的余量，但卡住"压缩基本没效果"这种退化
        assertTrue(compressed.length < original.length * 0.8,
                "高质量照片压缩后应至少小 20%：original=" + original.length
                        + ", compressed=" + compressed.length);
    }

    @Test
    void compressShouldNotInflateLowQualityJpeg() throws IOException {
        // 场景：用户上传的是一张已经压过的图（q0.75 等效，与 ImageIO 默认质量相当）。
        // 再压 0.8 = 升质，必然变大 → 守门必须原样放行。
        // 这个用例把"JPEG outputQuality 是绝对质量因子"这个坑固化成回归测试：
        // 若有人把守门逻辑删掉，这里立刻变红。
        byte[] original = jpegAtQuality(photoLike(), 0.75f);
        byte[] result = ImageUtils.compress(original, "jpg");

        assertTrue(result.length <= original.length,
                "守门失效：压缩结果不得比原始更大（original=" + original.length
                        + ", result=" + result.length + "）");
        // 守门是"原样返回"，内容必须逐字节一致（不是"重新编码出一个差不多大的"）
        assertArrayEquals(original, result, "守门触发时应原样返回原始字节");
    }

    @Test
    void compressShouldKeepPngUntouched() throws IOException {
        byte[] original = tinyPng();
        byte[] result = ImageUtils.compress(original, "png");

        // PNG 走"跳过"分支：无损格式、outputQuality 对它近乎无效，硬转 JPEG 会丢透明通道
        assertArrayEquals(original, result, "PNG 不应被改动");
    }

    @Test
    void compressShouldKeepWebpUntouched() {
        // webp 不需要真图 —— 格式判断在解码之前，任何字节都会走"原样返回"分支
        byte[] fake = new byte[100];
        byte[] result = ImageUtils.compress(fake, "webp");

        assertArrayEquals(fake, result, "webp 应原样返回（JDK 的 ImageIO 写不出 webp）");
    }

    @Test
    void compressedJpegShouldStillBeDecodable() throws IOException {
        // 压缩后的字节必须仍是合法 JPEG —— 防"压出一个坏文件"这种静默数据损坏
        byte[] original = jpegAtQuality(photoLike(), 0.95f);
        byte[] compressed = ImageUtils.compress(original, "jpg");

        var decoded = ImageIO.read(new ByteArrayInputStream(compressed));
        assertNotNull(decoded, "压缩结果必须仍可被 ImageIO 解码");
        assertEquals(PHOTO_SIZE, decoded.getWidth(), "scale(1.0) 不应改变尺寸");
        assertEquals(PHOTO_SIZE, decoded.getHeight(), "scale(1.0) 不应改变尺寸");
    }
}
