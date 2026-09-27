package com.huang.yupicture.utils;

import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/**
 * 图片文件工具：上传前的校验 + 读取元信息。
 *
 * <p><b>核心规则：不能只信文件名后缀。</b>
 * 后缀是"用户自称"—— 把 {@code evil.exe} 改名成 {@code girl.jpg} 只需按一下 F2。
 * 真实类型只能看**文件内容开头的"魔数"**（magic number，每种格式约定的固定字节）。
 *
 * <p>不校验的后果很具体：恶意脚本以图片名义存进你的桶，若哪天桶被挂上自定义域名、
 * 或前端某处把 URL 当脚本加载，就成了可执行的攻击载荷 —— 而这个漏洞是你"信任了文件名"造出来的。
 */
public final class ImageUtils {

    /**
     * 允许的格式白名单。
     *
     * <p>用白名单而不是黑名单：黑名单要穷举所有危险格式（永远漏），
     * 白名单只列出"我确实支持"的（漏不掉）。这是安全校验的通用原则。
     */
    private static final Set<String> ALLOWED_SUFFIX = Set.of("jpg", "jpeg", "png", "webp");

    /** 单文件大小上限 —— 与 application.yml 里 multipart 的配置保持一致 */
    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private ImageUtils() {
    }

    /**
     * 校验上传文件并返回小写后缀（不含点号）。
     *
     * @param file 上传的文件
     * @return 后缀，如 {@code jpg}
     * @throws BusinessException 文件为空 / 过大 / 后缀不在白名单
     */
    public static String checkAndGetSuffix(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        }
        // 第二道大小防线：框架层的 max-file-size 已经挡过一次，
        // 但那道门槛管的是"整个请求体"，这里管的是"业务允许的图片大小"，
        // 两者口径将来可能不同（比如多文件上传），所以各自校验一次。
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片大小不能超过 5MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (ParamUtils.isBlank(originalFilename)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件名不能为空");
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件缺少后缀名");
        }
        // 统一转小写再比：JPG / Jpg / jpg 都是同一个格式
        String suffix = originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_SUFFIX.contains(suffix)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持 jpg / jpeg / png / webp 格式");
        }
        return suffix;
    }

    /**
     * 校验文件内容是否真的是它自称的格式（读魔数比对）。
     *
     * @param bytes  文件内容
     * @param suffix 后缀（小写，已由 {@link #checkAndGetSuffix} 校验）
     * @throws BusinessException 内容与后缀不一致
     */
    public static void checkContent(byte[] bytes, String suffix) {
        if (!matchesMagic(bytes, suffix)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "文件内容与后缀不符，请上传真实的图片文件");
        }
    }

    /**
     * 读取图片宽高。
     *
     * <p><b>webp 会拿不到尺寸</b>：JDK 内置的 ImageIO 解码器只覆盖 jpg / png / gif 等，
     * 不含 webp。返回 {@code null} 表示"读不出来"，调用方要把宽高留空 —— <b>不能当成 0</b>，
     * 否则前端按 {@code 0/0} 算宽高比会得到 NaN。
     *
     * <p>不额外引 webp 解码库的理由：为一个字段多引一个二进制依赖不划算，
     * 而宽高只是"列表页预留位置"的优化，缺了不会坏功能。
     *
     * @return 长度为 2 的数组 {@code [宽, 高]}；解析失败返回 {@code null}
     */
    public static int[] readSize(byte[] bytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                return null;
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按后缀比对文件头。
     *
     * <p>{@code & 0xFF} 是必须的：Java 的 byte 是**有符号**的，
     * {@code (byte) 0x89} 实际是 -119，直接跟 {@code 0x89} 比永远不相等。
     * 无符号扩展成 int 才是我们要的字节值。（ASCII 范围的字节没这个问题，但不统一写法早晚踩坑。）
     */
    private static boolean matchesMagic(byte[] bytes, String suffix) {
        // 最短的格式头也要 12 字节（webp），不够长必然不是有效文件
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        return switch (suffix) {
            // JPEG: FF D8 FF
            case "jpg", "jpeg" -> (bytes[0] & 0xFF) == 0xFF
                    && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF;
            // PNG: 89 50 4E 47 0D 0A 1A 0A（"PNG\r\n\x1a\n"）
            case "png" -> (bytes[0] & 0xFF) == 0x89
                    && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            // WEBP: "RIFF" + 4 字节长度 + "WEBP"
            case "webp" -> bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }
}
