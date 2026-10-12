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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    /** 调色板容量：最多保留几个主色。「背景 + 主体 + 点缀」三种就够表达一张图 */
    public static final int PALETTE_SIZE = 3;

    /** 一条主色的最低占比：低于它说明只是零散像素，不构成「这张图的颜色」 */
    private static final double PALETTE_MIN_RATIO = 0.08;

    /**
     * 并簇距离（曼哈顿：|ΔR|+|ΔG|+|ΔB|）—— 两个候选色近于它就当成同一种颜色合并。
     *
     * <p>取 60 ≈ 每通道平均差 20（0~255 上不到 8%）：够把「白底的几种白」
     * （#FDF9FB / #FCFAFE / #FEFCFE…）并成一个簇，又不至于把蓝和紫（间距 ≥150）吞成一个。
     */
    private static final int PALETTE_MERGE_DISTANCE = 60;

    /**
     * 调色板里的一个主色：色值 + 占比 + 所属色系。
     *
     * @param color  {@code #RRGGBB} 大写
     * @param ratio  占采样像素的百分比（0~100；不保证求和为 100，低于门槛的那几条已被丢掉）
     * @param family 色系（与前端色板对应）。存进 JSON 是给 SQL 用的：
     *               {@code JSON_CONTAINS(picPalette, '"blue"', '$[*].family')} ——
     *               数据库里没有 classifyColorFamily，筛选条件只能在写入侧先算好
     */
    public record PaletteColor(String color, int ratio, String family) {
    }

    /** 主色的量化位数：RGB 每通道只保留高 3 位（8 桶）—— 量化后分桶统计才有意义，
     *  否则「统计出现最多的颜色」在一亿种取值上几乎每个像素都是独立桶 */
    private static final int COLOR_QUANTIZE_BITS = 3;

    /**
     * 提取图片调色板（按颜色搜索用）。
     *
     * <p><b>为什么不是一个主色：</b>单主色取的是「面积最大的颜色」—— 遇到
     * <b>白底 + 彩色主体</b>（动漫壁纸、商品图、截图）时，主色永远是那片背景白，
     * 主体色被彻底盖住：用户点「蓝色」搜不到任何一张「白底蓝主体」的图，
     * 而这恰恰是最该搜到的一类。调色板把「背景 / 主体 / 点缀」都收进来，
     * 筛选条件也随之从「主色落在这个色系」升级成「<b>任意一条主色落在这个色系</b>」。
     *
     * <p>算法（沿用「采样 + 量化直方图」，只是从「取最大桶」升级成「并簇取前几簇」）：<br>
     * ① 等步长采样（每 {@value #COLOR_SAMPLE_STEP} 个像素采 1 个）；<br>
     * ② 每通道保留高 {@value #COLOR_QUANTIZE_BITS} 位 → 桶 key，累计每桶的像素数与 RGB 之和；<br>
     * ③ 桶按像素数降序，<b>顺序并簇</b>：像素最多的桶先立为一个簇中心，把与它曼哈顿距离
     * ≤ {@value #PALETTE_MERGE_DISTANCE} 的桶全吸进来（按像素数加权求均值），
     * 再处理下一个未归簇的桶，如此往复 —— 桶是量化的产物，同一片颜色常横跨好几个桶，
     * 不合并的话「白的深浅」会把调色板名额占满；<br>
     * ④ 按像素数取前 {@value #PALETTE_SIZE} 个簇，丢掉占比低于 {@value #PALETTE_MIN_RATIO}
     * 的（零星像素不是「这张图的颜色」），<b>但至少保留一条</b>（整张图都是零散像素时也得有个主色）。
     *
     * <p><b>每一步都确定性</b>（并列项按色值定序，不吃 HashMap 的遍历顺序）：调色板要落库
     * 当检索键，同一张图两次提取出不同结果等于筛选失灵。
     *
     * <p>失败一律返回<b>空列表</b>而不抛异常：颜色是搜索的「增强维度」，提不出来只是这张图
     * 搜不到颜色，不该让上传失败 —— 与缩略图 / WebP 同一条「派生物失败不连坐主流程」的纪律。
     * （用空列表而不是 null：调用方一个 {@code isEmpty()} 就够，不必到处防 NPE。）
     *
     * @return 按占比降序的调色板；解码失败 / 空 bytes 返回空列表
     */
    public static List<PaletteColor> extractPalette(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        Map<Integer, long[]> buckets;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null || image.getWidth() <= 0) {
                return List.of();
            }
            buckets = histogram(image);
        } catch (Exception e) {
            return List.of();
        }
        if (buckets.isEmpty()) {
            return List.of();
        }

        // ① 桶 → 候选（代表色 = 桶内均值，权重 = 桶内像素数），按权重降序
        List<BucketColor> candidates = new ArrayList<>(buckets.size());
        for (long[] stats : buckets.values()) {
            if (stats[0] > 0) {
                candidates.add(new BucketColor(
                        (int) Math.round(stats[1] / (double) stats[0]),
                        (int) Math.round(stats[2] / (double) stats[0]),
                        (int) Math.round(stats[3] / (double) stats[0]),
                        stats[0]));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort(BucketColor.BY_WEIGHT_DESC);
        long total = 0;
        for (BucketColor candidate : candidates) {
            total += candidate.weight;
        }

        // ② 顺序并簇
        List<BucketColor> clusters = new ArrayList<>();
        boolean[] merged = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (merged[i]) {
                continue;
            }
            BucketColor center = candidates.get(i);
            merged[i] = true;
            double sumR = center.r * (double) center.weight;
            double sumG = center.g * (double) center.weight;
            double sumB = center.b * (double) center.weight;
            long weight = center.weight;
            for (int j = i + 1; j < candidates.size(); j++) {
                if (merged[j]) {
                    continue;
                }
                BucketColor other = candidates.get(j);
                if (manhattan(center, other) <= PALETTE_MERGE_DISTANCE) {
                    merged[j] = true;
                    weight += other.weight;
                    sumR += other.r * (double) other.weight;
                    sumG += other.g * (double) other.weight;
                    sumB += other.b * (double) other.weight;
                }
            }
            clusters.add(new BucketColor((int) Math.round(sumR / weight),
                    (int) Math.round(sumG / weight), (int) Math.round(sumB / weight), weight));
        }

        // ③ 前几条 + 占比守门（簇已按权重降序：后面的只会更小，一旦不达标就可以停）
        List<PaletteColor> palette = new ArrayList<>(PALETTE_SIZE);
        for (BucketColor cluster : clusters) {
            if (palette.size() >= PALETTE_SIZE) {
                break;
            }
            double ratio = cluster.weight / (double) total;
            if (ratio < PALETTE_MIN_RATIO && !palette.isEmpty()) {
                break;
            }
            String hex = String.format("#%02X%02X%02X", cluster.r, cluster.g, cluster.b);
            palette.add(new PaletteColor(hex, (int) Math.round(ratio * 100), classifyColorFamily(hex)));
        }
        return palette;
    }

    /**
     * 提取图片主色调 —— 调色板的<b>第一名</b>。
     *
     * <p>单主色是调色板的特例，不是另一套算法：详情页「主色」那一行、以及任何
     * 只需要一个颜色的场景都走它，与实际筛选用的调色板不会出现两套口径。
     * （早期版本是「取最大量化桶的均值」，升级成调色板后同样是「按像素数最多的那一簇」。）
     *
     * @return {@code #RRGGBB}（大写）；解码失败 / 空 bytes 返回 {@code null}
     */
    public static String extractMainColor(byte[] bytes) {
        List<PaletteColor> palette = extractPalette(bytes);
        return palette.isEmpty() ? null : palette.get(0).color();
    }

    /** 采样 + 量化直方图：桶 key → [像素数, R 累计, G 累计, B 累计] */
    private static Map<Integer, long[]> histogram(BufferedImage image) {
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
        return buckets;
    }

    /** 两个颜色的曼哈顿距离（|ΔR|+|ΔG|+|ΔB|）—— 并簇判据。只跟固定阈值比大小，
     *  不需要欧氏距离那次开方（省 sqrt，排序与判定结果不变）。 */
    private static int manhattan(BucketColor a, BucketColor b) {
        return Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b);
    }

    /**
     * 调色板 → 落库用的 JSON 字符串。
     *
     * <p>为什么手写而不走 Jackson：{@link ImageUtils} 是<b>不依赖 Spring 的纯工具类</b>
     * （所有方法都能脱离容器单测），为一段固定形状的字符串注入 ObjectMapper 不值当。
     * <b>只有本类自己产出的调色板能喂给它</b>：三个字段分别受控于十六进制格式化、
     * 百分比整数、色系常量，都不含需要转义的字符 —— 喂用户输入就会出现问题
     * （这不是通用 JSON 序列化器，别拿它序列化业务数据）。
     */
    public static String paletteToJson(List<PaletteColor> palette) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < palette.size(); i++) {
            PaletteColor color = palette.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"color\":\"").append(color.color())
                    .append("\",\"ratio\":").append(color.ratio())
                    .append(",\"family\":\"").append(color.family()).append("\"}");
        }
        return json.append(']').toString();
    }

    /** 直方图桶 / 并簇后的簇：一个候选颜色的均值 + 权重（像素数） */
    private static final class BucketColor {

        /** 权重降序，并列时按色值定序 —— 定序是为了让「同一张图两次提取结果一致」成立 */
        private static final Comparator<BucketColor> BY_WEIGHT_DESC = Comparator
                .comparingLong((BucketColor c) -> c.weight).reversed()
                .thenComparingInt(c -> c.r)
                .thenComparingInt(c -> c.g)
                .thenComparingInt(c -> c.b);

        private final int r;
        private final int g;
        private final int b;
        private final long weight;

        private BucketColor(int r, int g, int b, long weight) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.weight = weight;
        }
    }

    /** 保留 RGB 一个通道的高 3 位（0~7，共 8 桶）—— 颜色量化的基本操作。
     *  ⚠️ 是右移 5 位（丢低 5 位），不是右移 3 位——右移 3 位保留的是高 5 位（32 桶），
     *  桶太细会让直方图退化成「几乎每像素一桶」。实现时注释与代码必须对上。 */
    private static int quantize(int channel) {
        return channel >> (8 - COLOR_QUANTIZE_BITS);
    }

    /**
     * 把 #RRGGBB 归类到离散色系（与前端色板一一对应，见前端 constants/colors.ts）。
     *
     * <p><b>为什么需要色系：</b>主色提取产出的是连续空间里的具体色值
     * （如 #FDF9FB「近似白」、#338CFD「亮蓝」），而筛选入口是离散色板
     * （#FFFFFF、#1677FF…）。精确匹配两个空间几乎永远对不上——用户点「白色」
     * 却筛不出任何「近似白」的图，等于功能不可用。色系是把连续值收敛到
     * 离散类别的唯一桥梁。
     *
     * <p>规则（HSV 色相 + 明度/饱和度双重判定）：
     * ① V ≥ 0.92 且 S ≤ 0.12 → white；② V ≤ 0.16 → black；
     * ③ S ≤ 0.25 且 0.16 < V < 0.92 → 按亮度二分 gray 区间归 white/black
     * （低饱和度没有色相可言，硬算色相会被噪声牵着走）；
     * ④ 其余按色相角分档：红 <15 或 ≥345，橙 15~45，黄 45~70，绿 70~160，
     * 青 160~200，蓝 200~260，紫 260~300，≥300 且 <345 → 棕/紫边界按亮度分
     * （暗色偏红棕归棕，其余归紫）。
     *
     * @param hexColor {@code #RRGGBB}（大小写均可）
     * @return 色系名（小写英文，与色板 value 对应）；格式不合法返回 {@code null}
     */
    public static String classifyColorFamily(String hexColor) {
        if (hexColor == null) {
            return null;
        }
        String trimmed = hexColor.trim();
        if (!trimmed.matches("^#[0-9A-Fa-f]{6}$")) {
            return null;
        }
        int r = Integer.parseInt(trimmed.substring(1, 3), 16);
        int g = Integer.parseInt(trimmed.substring(3, 5), 16);
        int b = Integer.parseInt(trimmed.substring(5, 7), 16);

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        double v = max / 255.0;
        double s = max == 0 ? 0 : (double) (max - min) / max;

        if (v >= 0.92 && s <= 0.12) {
            return "white";
        }
        if (v <= 0.16) {
            return "black";
        }
        if (s <= 0.25) {
            return v >= 0.5 ? "white" : "black";
        }

        // 色相角（0~360）：标准 HSV 公式，max 是谁决定落在哪个扇区
        double delta = max - min;
        double hue;
        if (max == r) {
            hue = 60 * (((g - b) / delta) % 6);
        } else if (max == g) {
            hue = 60 * ((b - r) / delta + 2);
        } else {
            hue = 60 * ((r - g) / delta + 4);
        }
        if (hue < 0) {
            hue += 360;
        }

        if (hue < 15 || hue >= 345) {
            return v < 0.4 ? "brown" : "red";
        }
        if (hue < 45) {
            return v < 0.45 ? "brown" : "orange";
        }
        if (hue < 70) {
            return "yellow";
        }
        if (hue < 160) {
            return "green";
        }
        if (hue < 200) {
            return "cyan";
        }
        if (hue < 260) {
            return "blue";
        }
        if (hue < 300) {
            return "purple";
        }
        // 300~345：亮的是紫，暗的偏红棕
        return v < 0.4 ? "brown" : "purple";
    }

    // ==================== 以图搜图：感知哈希（pHash） ====================

    /** pHash 的 DCT 输入边长（缩到 32×32 再做变换，低频信息足够、又没有噪声干扰） */
    private static final int HASH_SIZE = 32;

    /** 取左上多少 × 多少的低频块（8×8 = 64 位哈希，是 pHash 的经典取值） */
    private static final int HASH_LOW_FREQ = 8;

    /** 哈希总位数（= HASH_LOW_FREQ²）。64 位 → 16 个十六进制字符 */
    public static final int HASH_BITS = HASH_LOW_FREQ * HASH_LOW_FREQ;

    /**
     * 计算图片的感知哈希（pHash）—— 以图搜图的检索键。
     *
     * <p><b>为什么是 pHash 而不是「比较像素」或「比较主色」：</b>
     * pHash 提取的是图片的<b>低频结构</b>（哪边亮、哪边暗、大块形状怎么分布），
     * 天然对「重新编码 / 缩放 / 加噪点 / 改亮度」不敏感 —— 同一张图存成 PNG 还是 JPEG q60、
     * 缩放一半，哈希都一模一样（实测汉明距离 0）。这正是「以图搜图」要的性质：
     * 找的是<b>同一张图的不同版本 / 同一张图本身</b>，不是「颜色接近的图」。
     *
     * <p>算法：① 缩到 32×32 → ② 转灰度 → ③ 二维 DCT-II → ④ 取左上 8×8 低频系数
     * （64 个）→ ⑤ 与「除 DC 外的均值」比较成位串 → ⑥ 打包成 16 位十六进制。
     *
     * <p><b>实测距离表（64 位中不同的位数，用来理解「多远算不像」）：</b>
     * <pre>
     *   同一张图（PNG / JPEG q95 / JPEG q60 / 缩放一半 / 加噪点）…… 0
     *   同一场景去掉一个元素 …………………………………… 8
     *   同图元素挪位置 …………………………………………… 13
     *   不同场景（照片 vs 棋盘 / 照片 vs 噪声 / 纯色 vs 照片）… 23 / 32 / 47
     * </pre>
     * 所以 {@code ImageConstant.SIMILAR_MAX_DISTANCE = 10} 是一个能落地、几乎不误召的阈值。
     *
     * <p><b>失败返回 null 而不是抛异常</b>：解码不了 / 格式不支持，只意味着「这张图不参与
     * 以图搜图」，不该让上传失败 —— 与缩略图 / WebP / 主色同一条「派生物失败不连坐主流程」
     * 的纪律。调用方（上传链路）拿到 null 就跳过，搜到的候选里 picHash 为 null 的也跳过。
     *
     * @param bytes 图片字节
     * @return 16 位小写十六进制哈希；解码失败 / 空字节返回 {@code null}
     */
    public static String perceptualHash(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage small;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            // forceSize 是「压成精确的 32×32」（不保持宽高比）—— 哈希要的是一张固定尺寸的
            // 指纹图，变形无所谓，确定性才有所谓。size() 是等比装进框里，会得到非正方形。
            small = Thumbnails.of(in).forceSize(HASH_SIZE, HASH_SIZE).asBufferedImage();
        } catch (Exception e) {
            return null;
        }
        if (small == null) {
            return null;
        }

        // 灰度矩阵（BT.601 亮度权重，与人类对亮度的感知一致）
        double[][] gray = new double[HASH_SIZE][HASH_SIZE];
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                int rgb = small.getRGB(x, y);
                gray[y][x] = 0.299 * ((rgb >> 16) & 0xFF)
                        + 0.587 * ((rgb >> 8) & 0xFF)
                        + 0.114 * (rgb & 0xFF);
            }
        }

        double[][] dct = dct2d(gray);

        // 抹掉浮点噪声：常量图（纯色）的低频 AC 系数<b>在数学上恰好是 0</b>，但浮点运算会
        // 留下 1e-13 量级的残渣。不清掉的话，下面「系数 > 均值」的比较就由残渣的符号决定 ——
        // 同一张纯色图的哈希会随平台 / JIT 的浮点差异乱跳（实测两张纯色图距离 52，
        // 本该是 0）。门限取 DC 的极小比例：真实结构（哪怕是很柔和的渐变）能量远高于它，
        // 不会被误抹，所以对正常图片是空操作。
        double noiseFloor = Math.abs(dct[0][0]) * 1e-9 + 1e-6;
        for (int u = 0; u < HASH_LOW_FREQ; u++) {
            for (int v = 0; v < HASH_LOW_FREQ; v++) {
                if (Math.abs(dct[u][v]) < noiseFloor) {
                    dct[u][v] = 0;
                }
            }
        }

        // 均值只用 AC 项（跳过 DC）：DC 是整张图的平均亮度，量级比所有高频项加起来还大，
        // 混进去会把均值抬到只有 DC 之上 → 64 位里 63 位恒为 0，哈希退化成 1 位。
        double sum = 0;
        for (int u = 0; u < HASH_LOW_FREQ; u++) {
            for (int v = 0; v < HASH_LOW_FREQ; v++) {
                if (u == 0 && v == 0) {
                    continue;
                }
                sum += dct[u][v];
            }
        }
        double avg = sum / (HASH_BITS - 1);

        StringBuilder bits = new StringBuilder(HASH_BITS);
        for (int u = 0; u < HASH_LOW_FREQ; u++) {
            for (int v = 0; v < HASH_LOW_FREQ; v++) {
                bits.append(dct[u][v] > avg ? '1' : '0');
            }
        }
        return bitsToHex(bits.toString());
    }

    /**
     * 二维 DCT-II，分离式实现（先对每行做一维 DCT、再对每列做一次）。
     *
     * <p>分离性是 DCT 的基本性质：二维变换可以拆成两次一维变换，复杂度从 O(n⁴) 降到 O(n³)。
     * 32×32 下每次哈希约 6.5 万次 cos 计算 —— 毫秒级，不值得为它引第三方库。
     *
     * @param matrix 方阵（本功能固定 32×32）
     * @return 同尺寸的 DCT 系数矩阵，[0][0] 是 DC 项
     */
    private static double[][] dct2d(double[][] matrix) {
        int n = matrix.length;
        // 正交归一化系数：α(0)=√(1/n)、α(k)=√(2/n)
        double[] alpha = new double[n];
        for (int i = 0; i < n; i++) {
            alpha[i] = (i == 0) ? Math.sqrt(1.0 / n) : Math.sqrt(2.0 / n);
        }

        // 第一步：按行（固定 y，沿 x 变换）
        double[][] rows = new double[n][n];
        for (int y = 0; y < n; y++) {
            for (int u = 0; u < n; u++) {
                double sum = 0;
                for (int x = 0; x < n; x++) {
                    sum += matrix[y][x] * Math.cos((2 * x + 1) * u * Math.PI / (2.0 * n));
                }
                rows[y][u] = alpha[u] * sum;
            }
        }

        // 第二步：按列（固定 u，沿 y 变换）
        double[][] out = new double[n][n];
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                double sum = 0;
                for (int y = 0; y < n; y++) {
                    sum += rows[y][u] * Math.cos((2 * y + 1) * v * Math.PI / (2.0 * n));
                }
                out[v][u] = alpha[v] * sum;
            }
        }
        return out;
    }

    /** 64 位的 '0'/'1' 串 → 16 个十六进制字符（Character.forDigit 产出小写，与库里存储一致） */
    private static String bitsToHex(String bits) {
        StringBuilder hex = new StringBuilder(bits.length() / 4);
        for (int i = 0; i < bits.length(); i += 4) {
            hex.append(Character.forDigit(Integer.parseInt(bits.substring(i, i + 4), 2), 16));
        }
        return hex.toString();
    }

    /**
     * 两个感知哈希的汉明距离（64 位里有多少位不同）—— 越小越像。
     *
     * <p>按十六进制字符逐位比：每个字符 4 位，{@code Integer.bitCount(a ^ b)} 一次数出
     * 这 4 位里差异的个数（异或后 1 的个数就是不同位的个数）。
     *
     * <p><b>非法输入返回 {@code Integer.MAX_VALUE}</b>：让调用方的
     * {@code distance <= 阈值} 判断自然为 false（这条候选被排除），不需要在每个调用点
     * 再写一遍 null 判断。长度不一致 / 非十六进制字符 / null 都走这条路。
     *
     * @param hashA 16 位十六进制哈希
     * @param hashB 16 位十六进制哈希
     * @return 汉明距离（0~64）；任一入参非法时返回 {@code Integer.MAX_VALUE}
     */
    public static int hammingDistance(String hashA, String hashB) {
        if (hashA == null || hashB == null || hashA.length() != hashB.length()) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (int i = 0; i < hashA.length(); i++) {
            int a = Character.digit(Character.toLowerCase(hashA.charAt(i)), 16);
            int b = Character.digit(Character.toLowerCase(hashB.charAt(i)), 16);
            if (a < 0 || b < 0) {
                return Integer.MAX_VALUE;
            }
            distance += Integer.bitCount(a ^ b);
        }
        return distance;
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
