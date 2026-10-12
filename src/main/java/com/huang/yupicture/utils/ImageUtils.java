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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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

    /** 主色提取的采样步长：每 N 个像素采 1 个（大图全扫太慢，颜色统计对精度不敏感） */
    private static final int COLOR_SAMPLE_STEP = 4;

    /** 主色的量化位数：RGB 每通道只保留高 3 位（8 桶）—— 量化后分桶统计才有意义，
     *  否则「统计出现最多的颜色」在一亿种取值上几乎每个像素都是独立桶 */
    private static final int COLOR_QUANTIZE_BITS = 3;

    /**
     * 提取图片主色调（按颜色搜索用）。
     *
     * <p>算法是「RGB 量化 + 直方图统计」：<br>
     * ① 等步长采样（每 {@value #COLOR_SAMPLE_STEP} 个像素采 1 个，颜色统计对精度不敏感，
     * 4000×3000 的照片全扫 1200 万次取像素远慢于需要的精度）；<br>
     * ② 每通道只保留高 {@value #COLOR_QUANTIZE_BITS} 位后拼成桶 key（不量化的话
     * 几乎每个像素都是独立颜色，「最多」无从统计起）；<br>
     * ③ 取像素数最多的桶，桶内取均值还原成 {@code #RRGGBB}。
     *
     * <p>失败一律返回 {@code null} 而不是抛异常：颜色是搜索的「增强维度」，
     * 提不出来只是这张图搜不到颜色，不该让上传失败 —— 与缩略图 / WebP 同一条
     * 「派生物失败不连坐主流程」的纪律。
     *
     * @return {@code #RRGGBB}（大写）；解码失败 / 空 bytes 返回 {@code null}
     */
    public static String extractMainColor(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null || image.getWidth() <= 0) {
                return null;
            }
            // 桶 key（量化后的 RGB）-> [像素数, R 累计, G 累计, B 累计]
            Map<Integer, long[]> buckets = new HashMap<>();
            int step = COLOR_SAMPLE_STEP;
            for (int y = 0; y < image.getHeight(); y += step) {
                for (int x = 0; x < image.getWidth(); x += step) {
                    int rgb = image.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int key = quantize(r) << 6 | quantize(g) << 3 | quantize(b);
                    long[] bucket = buckets.computeIfAbsent(key, k -> new long[4]);
                    bucket[0]++;
                    bucket[1] += r;
                    bucket[2] += g;
                    bucket[3] += b;
                }
            }
            if (buckets.isEmpty()) {
                return null;
            }
            // 取像素数最多的桶（并列时取谁都不影响「主色」语义，HashMap 遍历顺序无关紧要）
            Map.Entry<Integer, long[]> best = null;
            for (Map.Entry<Integer, long[]> entry : buckets.entrySet()) {
                if (best == null || entry.getValue()[0] > best.getValue()[0]) {
                    best = entry;
                }
            }
            long[] stats = best.getValue();
            // 桶内取均值还原 —— 桶代表一段颜色区间，均值比区间代表值更接近真实观感
            long r = Math.round(stats[1] / (double) stats[0]);
            long g = Math.round(stats[2] / (double) stats[0]);
            long b = Math.round(stats[3] / (double) stats[0]);
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return null;
        }
    }

    /** 保留 RGB 一个通道的高 3 位（0~7），低 5 位归零 —— 颜色量化的基本操作 */
    private static int quantize(int channel) {
        return channel >> COLOR_QUANTIZE_BITS;
    }

    /**
     * 按魔数反推格式（URL 传图用：地址没有后缀时，格式只能看内容）。
     *
     * <p>与 {@link #matchesMagic} 的区别：那个是「拿着用户声称的后缀来验证」，
     * 这个是「没有任何声称，从内容里读出真相」。jpg 的头（FF D8 FF）、png 的头
     * （89 50 4E 47）都是各自格式强约定的固定字节，能唯一反推。
     *
     * @return 小写后缀；认不出的格式返回 null（调用方报"无法识别"）
     */
    public static String detectSuffixByMagic(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** 后缀是否在白名单内（URL 传图从路径尾段推断后缀后，先用它过滤一眼） */
    public static boolean isSupportedSuffix(String suffix) {
        return suffix != null && ALLOWED_SUFFIX.contains(suffix.toLowerCase(Locale.ROOT));
    }

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

    /** 缩略图边长上限（px）。列表页图片格子就是 200×200，缩略图没必要比格子还大 */
    public static final int THUMBNAIL_SIZE = 200;

    /**
     * 生成缩略图（列表页专用的小尺寸版本）。
     *
     * <p><b>为什么要有缩略图：</b>列表页一页 10 张图，每张都拉原图（几百 KB ~ 几 MB），
     * 而展示的格子只有 200×200 —— 带宽和加载时间是「缩小 10 倍也看不出来」的白白浪费。
     * 压缩（{@link #compress}）省的是「同一张图的冗余质量」，缩略图省的是
     * 「根本不需要那么大」—— 两者的浪费来源不同，所以两个都要做。
     *
     * <p><b>等比缩放，不变形：</b>{@code size(200, 200)} 的语义是「装进 200×200 的框里，
     * 保持宽高比」—— 800×400 的图会缩成 200×100，而不是硬拉成 200×200 的正方形。
     * （要「强行填满」得用 {@code forceSize}，那是另一回事，图库场景不用。）
     *
     * <p><b>守门：已经比缩略图还小的图不放大。</b>一张 100×100 的头像放大到 200×200
     * 只会变糊、变大（插值出来的像素不携带新信息）—— 和压缩「压完不比原始小就放弃」
     * 是同一个思想：<b>处理是手段，收益才是目的。</b>
     *
     * <p><b>webp 返回 null（而不是抛异常）</b>：JDK 的 ImageIO 能读不能写 webp
     * （{@link #readSize} 读不出 webp 尺寸是同一个根源），这不是「这张图有问题」，
     * 而是「这个格式目前不支持生成缩略图」—— 调用方拿到 null 就跳过，
     * 列表页回退到原图，功能不坏。
     *
     * @param bytes  图片字节（已通过魔数校验）
     * @param suffix 小写后缀（jpg / jpeg / png / webp）
     * @return 缩略图字节；webp 或「比缩略图还小」时返回 null（调用方跳过即可）
     */
    public static byte[] thumbnail(byte[] bytes, String suffix) {
        // ① webp 造不了缩略图 → null，调用方回退原图
        if ("webp".equals(suffix)) {
            return null;
        }

        // ② 守门：小图不放大（先量尺寸再决定要不要动手，跟压缩的"压完比大小"前后呼应：
        //    压缩是"做完检查值不值"，缩略图是"做之前就知道不值"—— 能提前判断的就别浪费那次编解码）
        int[] size = readSize(bytes);
        if (size != null && size[0] <= THUMBNAIL_SIZE && size[1] <= THUMBNAIL_SIZE) {
            return null;
        }

        // ③ 缩略图保持原格式：PNG 转 JPEG 会丢透明通道（压缩模块验证过的坑），这里不重蹈覆辙
        String outputFormat = "png".equals(suffix) ? "png" : "jpeg";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Thumbnails.of(new ByteArrayInputStream(bytes))
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)   // 等比缩放进 200×200 的框
                    .outputFormat(outputFormat)
                    .outputQuality(COMPRESS_QUALITY)        // 对 jpeg 生效；png 无损格式自动忽略
                    .toOutputStream(out);
        } catch (IOException e) {
            // 与 compress() 同一处置：不外抛受检异常，但也不静默吞掉 —— 翻译成系统异常
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "缩略图生成失败");
        }
        return out.toByteArray();
    }

    /** WebP 转换质量（0.0~1.0）。与 COMPRESS_QUALITY 同值：肉眼几乎无损的起点 */
    public static final float WEBP_QUALITY = 0.8f;

    /**
     * 把图片转码成 WebP 格式（保留原尺寸）。
     *
     * <p><b>收益来源：</b>WebP 的压缩算法比 JPEG 新一代（Google 基于 VP8），
     * 同等画质下体积小 25%~39%（本项目实测 1200² 照片感图：jpeg q0.8 = 270KB，
     * webp 0.8 = 167KB）。转换是「重新编码」不是「压缩历史」—— 对已是 webp 的图
     * 再转一次只会重复量化，直接返回 null（调用方跳过）。
     *
     * <p><b>为什么能写 webp 了：</b>{@code webp-imageio}（pom 里有）通过 ImageIO 的
     * SPI 机制注册了 webp 编码器 —— {@code ImageIO.write(img, "webp", out)} 本来
     * 写不出（JDK 内置只有 jpg/png/gif），引依赖后 SPI 自动生效，一行代码都不用改。
     * 代价：jar 内带 native 库（.dll/.so），只能在支持的平台跑 —— 这就是当初
     * 「为读 webp 宽高要不要引依赖」争论时选择不引、现在「为写 webp」拍板引的原因：
     * <b>读不到尺寸只是少个字段，写不出格式是整个功能做不了</b>，必要性完全不同。
     *
     * <p><b>透明通道保留：</b>webp 支持 alpha，PNG 转 webp 不丢透明区域
     * （这是它优于「PNG→JPEG」硬转的地方）。内部解码后按原图类型重编码，
     * 透明信息在 BufferedImage 里保住了。
     *
     * <p><b>守门：转完不比原始小就放弃。</b>与 {@link #compress} 同一思想 ——
     * 已高度优化的图（如截图工具直接存成的高压 webp/png）转码后可能更大，
     * 返回 null 让调用方保留原格式。
     *
     * @param bytes  图片字节（已通过魔数校验，非 webp）
     * @return webp 字节；转换无收益时返回 null
     */
    public static byte[] toWebp(byte[] bytes) {
        BufferedImage image;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            image = ImageIO.read(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片解码失败");
        }
        if (image == null) {
            // 解码失败不炸上传：返回 null，调用方保留原格式（与缩略图的降级思路一致）
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "webp", out);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "WebP 编码失败");
        }

        // 守门：转完 >= 原始就放弃（等号也算没赚到）
        if (out.size() >= bytes.length) {
            return null;
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
