package com.huang.yupicture.utils;

import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    static {
        // ImageIO 默认把编解码的中间数据缓存到磁盘（java.io.tmpdir 下建临时文件）。
        // 在受限环境（tmpdir 不可写 / 磁盘清理中）会直接抛
        // `IIOException: Can't create cache file!` —— 测试里就撞到过。
        // 本项目全程操作内存里的 byte[]（单图 ≤ 5MB），关掉磁盘缓存改用内存：
        // 更快、无临时文件残留、也不会再被 tmpdir 的问题绊倒。
        ImageIO.setUseCache(false);
    }

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

    /** JPEG 压缩质量（0.0~1.0）。0.8 是"肉眼几乎无损、体积减半"的常用起点 */
    public static final float COMPRESS_QUALITY = 0.8f;

    /**
     * 把图片压缩到指定质量（只降质量、不改尺寸）。
     *
     * <p><b>只对 JPEG 生效</b>，其余格式原样返回，各有明确原因：
     * <ul>
     *   <li><b>PNG</b>：无损格式，{@code outputQuality} 对它几乎不减体积；
     *       硬转 JPEG 能变小但会丢透明通道（透明区域变黑）—— 得不偿失</li>
     *   <li><b>WebP</b>：JDK 的 ImageIO <b>能读不能写</b> webp（{@link #readSize} 读不出
     *       webp 尺寸是同一个根源），Thumbnails 底层走 ImageIO，输出时直接抛异常</li>
     * </ul>
     *
     * <p>全程内存操作：byte[] 进 byte[] 出，不落临时文件
     * （临时文件要管创建、清理、失败路径上的删除，漏一步就是垃圾文件堆积；
     * 而单图 ≤ 5MB，内存里转一圈毫无压力，方法结束自动释放）。
     *
     * <p><b>守门：压完不比原始小就放弃。</b>小图（如 10KB 头像）、已高度压缩的 JPEG
     * 再压一遍可能反而更大（JPEG 每次保存都重新量化，重复压缩还可能劣化）——
     * 压缩是手段不是目的，"压过"本身没有意义，变小才有。
     *
     * @param bytes  图片原始字节（已通过魔数校验）
     * @param suffix 小写后缀（jpg / jpeg / png / webp）
     * @return 压缩后的字节；无需/无法压缩时原样返回
     */
    public static byte[] compress(byte[] bytes, String suffix) {
        // ① 压不动的格式直接放行（常量在左，防 NPE 写法与 isAdmin() 一致）
        if ("png".equals(suffix) || "webp".equals(suffix)) {
            return bytes;
        }

        // ② 内存进出：ByteArrayInputStream 包装已有字节（读方向管道），
        //    ByteArrayOutputStream 攒压缩结果（写方向管道），全程不碰磁盘
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Thumbnails.of(new ByteArrayInputStream(bytes))
                    .scale(1.0)                        // 原尺寸；不调 size/scale 时 Builder 会抛 IllegalStateException
                    .outputQuality(COMPRESS_QUALITY)   // 只降质量 —— "压缩"的全部来源
                    .toOutputStream(out);
        } catch (IOException e) {
            // 不往外抛受检异常（项目规约，readSize() 先例）；
            // 但也不能静默当"没压"（用户以为压了实际没压，日志与实际不符）—— 翻译成系统异常
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片压缩失败");
        }

        // ③ 守门：压完 >= 原始大小就放弃（等号也算"没赚到"，不值得为它换内容）
        if (out.size() >= bytes.length) {
            return bytes;
        }
        return out.toByteArray();
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
