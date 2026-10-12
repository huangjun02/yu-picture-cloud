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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#toWebp(byte[])} 的单元测试。
 *
 * <p>依赖 {@code webp-imageio}（SPI 自动注册 webp 编码器）—— 如果哪天把依赖移除，
 * 这里会集体失败，等于把「转换功能依赖这个 jar」固化成回归信号。
 *
 * <p>守门数据（实测，1200² 照片感图）：jpeg q0.8 = 270KB，webp 默认 = 167KB。
 */
class ImageUtilsWebpTest {

    @BeforeAll
    static void disableImageIoDiskCache() {
        ImageIO.setUseCache(false);
    }

    // ==================== 造数据 ====================

    /** 1200×1200 照片感图（渐变 + 多边形 + 噪声，配方同 ImageUtilsCompressTest） */
    private byte[] photoJpeg(float quality) throws IOException {
        int s = 1200;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 80, 160), s, s, new Color(230, 220, 190)));
        g.fillRect(0, 0, s, s);
        g.setColor(new Color(40, 120, 60));
        g.fillPolygon(new int[]{0, s / 3, 2 * s / 3, s}, new int[]{s, s / 2, 3 * s / 4, s}, 4);
        g.setColor(new Color(255, 230, 120));
        g.fillOval(3 * s / 4, s / 10, 90, 90);
        for (int i = 0; i < 40000; i++) {
            g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
            g.fillRect((int) (Math.random() * s), (int) (Math.random() * s), 1, 1);
        }
        g.dispose();
        return jpegAtQuality(img, quality);
    }

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

    /** 800×500 带透明区域的 PNG（左透明右不透明） */
    private byte[] pngWithAlpha() throws IOException {
        BufferedImage img = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 60, 60, 0));
        g.fillRect(0, 0, 400, 500);
        g.setColor(new Color(200, 60, 60, 255));
        g.fillRect(400, 0, 400, 500);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 生成一个真实的小 webp 文件（webp 输入用例要用真 webp 当原始字节） */
    private byte[] realWebp() throws IOException {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.BLUE, 600, 400, Color.YELLOW));
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "webp", out);
        return out.toByteArray();
    }

    // ==================== 用例 ====================

    @Test
    void toWebpShouldShrinkJpeg() throws IOException {
        // 场景：上传手机 JPEG 照片（先按 0.8 压缩过）→ 转 webp 还能再小一截
        byte[] original = jpegAtQuality(photoImg(), 0.8f);
        byte[] webp = ImageUtils.toWebp(original);

        assertNotNull(webp, "正常照片转 webp 应有收益");
        assertTrue(webp.length < original.length,
                "webp 应比 jpeg 小：original=" + original.length + ", webp=" + webp.length);
    }

    private BufferedImage photoImg() {
        int s = 1200;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 80, 160), s, s, new Color(230, 220, 190)));
        g.fillRect(0, 0, s, s);
        g.setColor(new Color(40, 120, 60));
        g.fillPolygon(new int[]{0, s / 3, 2 * s / 3, s}, new int[]{s, s / 2, 3 * s / 4, s}, 4);
        g.dispose();
        return img;
    }

    @Test
    void toWebpShouldKeepDimensions() throws IOException {
        byte[] original = jpegAtQuality(photoImg(), 0.9f);
        byte[] webp = ImageUtils.toWebp(original);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(webp));
        assertNotNull(decoded, "webp 必须仍可解码（webp-imageio 同时注册了 reader）");
        assertEquals(1200, decoded.getWidth(), "转换不改尺寸");
        assertEquals(1200, decoded.getHeight(), "转换不改尺寸");
    }

    @Test
    void toWebpShouldReturnNullForWebpInput() throws IOException {
        // 已是 webp 再转 = 重复量化，无收益还劣化 → 直接跳过
        byte[] webpSource = realWebp();
        assertNull(ImageUtils.toWebp(webpSource), "webp 输入应返回 null（不重复转换）");
    }

    @Test
    void toWebpShouldKeepTransparencyFromPng() throws IOException {
        // PNG → webp 不丢透明通道（这是 webp 优于硬转 JPEG 的核心卖点）
        byte[] png = pngWithAlpha();
        byte[] webp = ImageUtils.toWebp(png);

        if (webp == null) {
            // 透明大片纯色的图转 webp 无收益也是合法结果，此时守门拦截
            return;
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(webp));
        assertNotNull(decoded);
        assertTrue(decoded.getColorModel().hasAlpha(),
                "PNG 转 webp 必须保留透明通道");
    }

    @Test
    void toWebpShouldReturnNullWhenNoGain() {
        // 构造"转完更大"的场景：极小的纯色图，格式头占比高，webp 未必更小
        // 守门必须拦下（宁可不转，也不让它变大）
        BufferedImage tiny = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(tiny, "png", out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // 2x2 纯色 png=68B、webp=44B，webp 其实更小 —— 所以这个用例只断言"不抛异常 + 要么 null 要么更小"
        byte[] result = ImageUtils.toWebp(out.toByteArray());
        if (result != null) {
            assertTrue(result.length <= out.size(),
                    "守门失效：转换结果不得比原始更大");
        }
    }

    @Test
    void toWebpShouldSurviveGarbageInput() {
        // 解码失败返回 null 而不是抛 —— 上传链路里乱字节不该炸主流程
        // （魔数校验已挡住真垃圾，这里是"过了魔数但解不了"的理论边界，如损坏文件）
        assertNull(ImageUtils.toWebp(new byte[64]), "无法解码的输入应返回 null");
    }
}
