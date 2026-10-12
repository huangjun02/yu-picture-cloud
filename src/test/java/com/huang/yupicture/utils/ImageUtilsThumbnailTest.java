package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageUtils#thumbnail(byte[], String)} 的单元测试。
 *
 * <p>与 {@link ImageUtilsCompressTest} 同一套纪律：纯函数直跑，不碰 Spring / COS / 数据库；
 * {@code @BeforeAll} 里必须再关一次 ImageIO 磁盘缓存（造图代码先于被测方法运行，
 * ImageUtils 的静态块那时还没触发）。
 */
class ImageUtilsThumbnailTest {

    private static final int THUMB = ImageUtils.THUMBNAIL_SIZE;

    @BeforeAll
    static void disableImageIoDiskCache() {
        ImageIO.setUseCache(false);
    }

    // ==================== 造数据 ====================

    /** 造一张 photoLike 风格的 JPEG（细节见 ImageUtilsCompressTest 的说明） */
    private byte[] jpeg(int width, int height, float quality) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int x = 0; x < width; x++) {
            g.setColor(new Color((x * 255) / width, 100, 150));
            g.drawLine(x, 0, x, height);
        }
        g.setColor(new Color(255, 230, 120));
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        return jpegAtQuality(image, quality);
    }

    /** 用显式质量因子编码 JPEG（默认质量的坑见 ImageUtilsCompressTest） */
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

    /** 造一张带透明区域的 PNG（800×500，左半透明、右半不透明） */
    private byte[] pngWithAlpha(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 60, 60, 0));      // alpha=0：完全透明
        g.fillRect(0, 0, width / 2, height);
        g.setColor(new Color(200, 60, 60, 255));    // 完全不透明
        g.fillRect(width / 2, 0, width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ==================== 用例 ====================

    @Test
    void thumbnailShouldShrinkLargePhotoIntoBox() throws IOException {
        // 场景：用户传 1200×1200 的大图 → 列表页只需要 200×200 以内的小图
        byte[] original = jpeg(1200, 1200, 0.95f);
        byte[] thumb = ImageUtils.thumbnail(original, "jpg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb));
        assertNotNull(decoded, "缩略图必须仍可被 ImageIO 解码");
        // size() 的语义是"装进框里"，正方形图应正好 200×200
        assertEquals(THUMB, decoded.getWidth(), "正方形大图的缩略图宽应为 200");
        assertEquals(THUMB, decoded.getHeight(), "正方形大图的缩略图高应为 200");
        // 缩略图必须真的小得多（1200² → 200²，面积差 36 倍）—— 卡住"尺寸没缩"这种退化
        assertTrue(thumb.length < original.length,
                "缩略图应比原图小：original=" + original.length + ", thumb=" + thumb.length);
    }

    @Test
    void thumbnailShouldKeepAspectRatio() throws IOException {
        // 等比缩放是硬要求：800×400 的图缩成 200×100，不是硬拉成 200×200 的变形图
        byte[] original = jpeg(800, 400, 0.95f);
        byte[] thumb = ImageUtils.thumbnail(original, "jpg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb));
        assertNotNull(decoded);
        assertEquals(THUMB, decoded.getWidth(), "宽边应顶到框边 200");
        assertEquals(100, decoded.getHeight(), "800:400 = 2:1，等比缩放后高应为 100");
    }

    @Test
    void thumbnailShouldReturnNullForSmallImage() {
        // 场景：100×100 的小头像 —— 放大到 200 只会变糊变大，守门必须拦下
        // 造一张 100×100 的真 JPEG（不能随便塞假字节：这个分支要先解码量尺寸）
        byte[] tiny = new byte[0];
        try {
            tiny = jpeg(100, 100, 0.9f);
        } catch (IOException ignored) {
        }
        assertNull(ImageUtils.thumbnail(tiny, "jpg"), "比缩略图还小的图应返回 null（不放大）");
    }

    @Test
    void thumbnailShouldReturnNullForWebp() {
        // webp：JDK ImageIO 能读不能写，格式判断在解码之前，任何字节都走 null 分支
        byte[] fake = new byte[100];
        assertNull(ImageUtils.thumbnail(fake, "webp"), "webp 应返回 null，调用方回退原图");
    }

    @Test
    void pngThumbnailShouldKeepTransparency() throws IOException {
        // PNG 缩略图保持 PNG：硬转 JPEG 会丢透明通道（压缩模块验证过的坑，这里回归固化）
        byte[] original = pngWithAlpha(800, 500);
        byte[] thumb = ImageUtils.thumbnail(original, "png");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb));
        assertNotNull(decoded);
        assertTrue(decoded.getColorModel().hasAlpha(),
                "PNG 缩略图必须保留透明通道（丢了 = 被转成了 JPEG）");
        assertEquals(THUMB, decoded.getWidth(), "800×500 等比缩进 200 框，宽顶满");
        assertEquals(125, decoded.getHeight(), "800:500 = 8:5，缩放后高 125");
    }

    @Test
    void jpegThumbnailShouldActuallyBeJpeg() throws IOException {
        // 输出格式回归：jpg 进 → jpeg 出（魔数 FF D8 FF），防止哪天 outputFormat 写错格式名
        byte[] original = jpeg(1200, 900, 0.95f);
        byte[] thumb = ImageUtils.thumbnail(original, "jpg");

        assertEquals(0xFF, thumb[0] & 0xFF, "JPEG 必须以 0xFF 开头");
        assertEquals(0xD8, thumb[1] & 0xFF, "JPEG 第二字节必须是 0xD8");
        assertFalse(thumb[2] == 'P' && thumb[3] == 'N' && thumb[4] == 'G',
                "jpg 输入不应产出 PNG（格式名传错时 Thumbnails 会静默转格式）");
    }
}
