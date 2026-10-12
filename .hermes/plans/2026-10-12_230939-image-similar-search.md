# 以图搜图（感知哈希 + 汉明距离）实现计划

> **For Hermes:** 用 subagent-driven-development 按任务逐条实施；每个任务都是 2~5 分钟的独立动作。

**Goal:** 给云图库加「上传一张图 → 从图库里找出最相似的图」的能力（后端检索 + 主页前端入口）。

**Architecture:** 上传链路在落库前用 `ImageUtils.perceptualHash(byte[])` 算 64 位感知哈希（pHash：缩到 32×32 灰度 → 二维 DCT → 取左上 8×8 低频块 → 与均值比较成位串），存进 `image.picHash CHAR(16)`。搜索时对查询图算同一个哈希，把可见范围内的候选拉回内存算汉明距离（≤ 阈值算相似、升序取前 20）。**不引任何新依赖**（pHash 的 DCT 手写 30 行，缩图用已有的 Thumbnailator），不接付费的第三方图像搜索服务。

**Tech Stack:** Spring Boot 3.5.16 / JDK21 / MyBatis-Plus 3.5.17 / Thumbnailator 0.4.20 / Vue3 + AntD4。

---

## 一、现状与必须遵守的既有契约

实施者必须先读这几个文件（下面所有路径都是绝对路径）：

- 后端根目录：`E:\project\yu-picture-cloud`
- 前端根目录：`E:\project\yu-picture-cloud-frontend`

| 文件 | 作用 | 本功能要动它的地方 |
|---|---|---|
| `src/main/java/com/huang/yupicture/utils/ImageUtils.java` | 图片纯函数工具（校验 / 压缩 / 缩略图 / webp / 主色） | 新增 `perceptualHash` / `hammingDistance` / `dct2d` |
| `src/main/java/com/huang/yupicture/model/entity/Image.java` | image 表实体（驼峰列名） | 新增 `picHash` 字段 |
| `src/main/java/com/huang/yupicture/constant/ImageConstant.java` | 图片模块常量 | 新增 3 个相似度常量 |
| `src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java` | 业务实现（`persistProcessedImage` 是上传汇合点） | 落库时写 picHash；新增 `searchSimilarImage` |
| `src/main/java/com/huang/yupicture/service/ImageService.java` | 业务接口 | 新增 `searchSimilarImage` 声明 |
| `src/main/java/com/huang/yupicture/controller/ImageController.java` | 接口层（只搬运） | 新增 `POST /image/search/similar` |
| `src/main/java/com/huang/yupicture/model/vo/ImageVO.java` | 对外视图 | **不改**（不引入相似度分数字段，见开放问题） |
| `src/main/resources/...` / `sql/` | DDL 脚本（**必须交用户手动执行**，见下） | 新增 `sql/10_alter_image_pic_hash.sql` |
| `docs/requirements.md` | 进度真源（状态表 + 里程碑 + 变更记录） | 状态 ✅ + 变更记录一行 |
| `E:\project\yu-picture-cloud-frontend\src\api\image.ts` | 前端接口层 | 新增 `searchSimilarImage` |
| `E:\project\yu-picture-cloud-frontend\src\views\HomeView.vue` | 主页图墙 | 新增「以图搜图」上传按钮 + 结果态 |

### 必须遵守的既有约定（踩过的坑，别重蹈）

1. **DDL 一律交用户手动执行**——不能连库（root 有密码，红线是不读凭据）。脚本编号会撞号，**先 `ls sql/`**，当前已到 `09_alter_image_share_code.sql`。
2. **给 entity 加字段前，DDL 必须已经在库里跑过**。否则 MyBatis-Plus 的 SELECT 会自动带上新列 → 所有读 image 表的用例集体 `Unknown column 'picHash' in 'field list'`。这不是代码错，是环境没跟上。
3. **派生信息失败不连坐主流程**：pHash 算不出来就存 null + 打 warn，绝不阻断上传（与缩略图 / WebP / 主色同一条纪律）。
4. **新增接口天生受保护**（不在 `SaTokenConfig` 白名单里）；**不要**为了这个接口加白名单。
5. **Maven 在 git-bash 下必须走 classworlds 那条完整命令**（`bin/mvn` 会静默成功但什么都没编译）。命令见下。
6. **不引 Bean Validation / 不加依赖注解**；校验手写在 Service。**本功能不需要任何新依赖**。
7. 提交规范 `feat|test|fix(scope): 中文描述`，**功能与测试分开两个 commit**。

### 跑测试的准确命令（git-bash）

```bash
cd /e/project/yu-picture-cloud && "/c/Program Files/Java/jdk-21.0.10/bin/java.exe" \
 -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" \
 "-Dclassworlds.conf=D:/apache-maven-3.9.16/bin/m2.conf" \
 "-Dmaven.home=D:/apache-maven-3.9.16" \
 "-Dmaven.multiModuleProjectDirectory=E:/project/yu-picture-cloud" \
 org.codehaus.plexus.classworlds.launcher.Launcher -Dtest=ClassName test
```

- **整条命令内联写，别塞进 shell 变量**（路径带空格，变量展开会被 word-splitting 拆开 → 静默跑空、退出码 0）。
- **不要加 `-q`**（会连 `Tests run:` 汇总行一起吞掉）。
- 输出里有中文时 grep 必须加 `-a`（`| grep -a -E "Tests run:|ERROR"`），否则只回一行 `Binary file matches`。
- 结果以 `target/surefire-reports/*.txt` 的**内容 + 时间戳**为准（目录里会残留已删除测试类的旧报告）。

---

## 二、算法选型与实测阈值（已用 Python 探针实测，不是拍脑袋）

算法：**pHash（感知哈希）**，离线、零依赖、确定性强。

1. 解码 → 缩放到 **32×32**（`Thumbnails.of(in).forceSize(32,32).asBufferedImage()`，已用 `javap` 确认 0.4.20 有 `forceSize(int,int)` 和 `asBufferedImage()`）
2. 转灰度：`gray = 0.299R + 0.587G + 0.114B`
3. 二维 DCT-II（分离式：先按行、再按列）
4. 取左上 **8×8** 低频系数（64 个）
5. 均值**只用 AC 项**（跳过 DC；DC 量级比其余项之和还大，算进去会把均值抬到只有 DC 之上 → 位串退化成 1 位）
6. 每个系数 > 均值 → `1`，否则 `0` → 64 位 → 16 位小写十六进制存 `CHAR(16)`

**实测数据（用来定阈值，实施时把这张表抄进 `perceptualHash` 的 Javadoc）：**

| 场景 | 汉明距离（64 位中不同的位数） |
|---|---|
| 同一张图 | 0 |
| 同一张图：PNG ↔ JPEG q95 ↔ JPEG q60 ↔ 缩放一半 ↔ 加噪点 | **0** |
| 同一场景去掉一个元素 | 8 |
| 同图元素挪位置 | 13 |
| 不同场景（渐变 vs 照片 / 照片 vs 棋盘 / 照片 vs 噪声 / 纯色 vs 照片） | 23 / 23 / 32 / 47 |
| 两个纯色图（黑 vs 白、深蓝 vs 浅灰） | 1 |

**结论：阈值取 10**（`≤10` 算相似）。同一张图无论怎么重新编码都是 0~8，不会漏；不同场景最低 13，不会误召。两个已知的边界写在风险节。

---

## 三、任务清单

### Task 0：开分支 + 记基线

**Files:** 无

```bash
cd /e/project/yu-picture-cloud
git status --short          # 期望：只剩 ?? .hermes/plans/...（计划文件），没有半成品改动
git checkout -b feat/image-similar-search
```

先跑一次全量基线，把数字记下来（后面报增量用）：

```bash
cd /e/project/yu-picture-cloud && "/c/Program Files/Java/jdk-21.0.10/bin/java.exe" \
 -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" \
 "-Dclassworlds.conf=D:/apache-maven-3.9.16/bin/m2.conf" \
 "-Dmaven.home=D:/apache-maven-3.9.16" \
 "-Dmaven.multiModuleProjectDirectory=E:/project/yu-picture-cloud" \
 org.codehaus.plexus.classworlds.launcher.Launcher test 2>&1 | grep -a -E "Tests run:.*Failures|BUILD"
```

**期望：** `BUILD SUCCESS`，`Tests run: 120, Failures: 0, Errors: 0, Skipped: 1`（跳过的那条是默认关闭的颜色回填维护任务）。数字对不上就先停下问用户，别改代码。

---

### Task 1：写 DDL 脚本并**交用户执行**

**Files:** Create `E:\project\yu-picture-cloud\sql\10_alter_image_pic_hash.sql`

```sql
-- 以图搜图：感知哈希（pHash）
--
-- 为什么落库而不是现算：哈希是「像素的统计量」，不是 URL 的函数 ——
-- 现算要把每张图从 COS 拉回本地解码，一次搜索要拉上千张图。
-- 这与缩略图/WebP「能推导的信息不落库」不矛盾：那两个是 key 的确定性函数，这个不是。
--
-- 为什么 CHAR(16)：64 位哈希 → 16 个十六进制字符，定长不多占空间。
-- 为什么 NULL 允许：老图还没回填、以及解码失败的图（派生信息失败不连坐主流程）。
--
-- 为什么没有索引：汉明距离不是 SQL 能表达的条件（它是逐位比较），
-- 建普通索引也用不上，反而不如老实全量捞回内存算。到百万级再上向量库/LSH。
ALTER TABLE image
    ADD COLUMN picHash CHAR(16) NULL COMMENT '感知哈希（64 位十六进制，小写）' AFTER picColorFamily;
```

**验证：** 把文件路径给用户，请他执行（IDEA 里打开 `Ctrl+Enter`，或 `mysql -uroot -p < sql/10_alter_image_pic_hash.sql`）。

**⚠️ 停下来等用户确认「执行完了」再进 Task 2。** 没执行就加 entity 字段 → 全量测试集体红。

```bash
# 用户执行完后，让他跑这条确认列存在（他只回一句结果即可，你不要去连库）：
#   SHOW COLUMNS FROM image LIKE 'picHash';
# 期望：一行，Type=char(16)，Null=YES
```

---

### Task 2：写第一条哈希测试（RED）

**Files:** Create `E:\project\yu-picture-cloud\src\test\java\com\huang\yupicture\utils\ImageUtilsHashTest.java`

**Step 1：写测试**

```java
package com.huang.yupicture.utils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ImageUtils#perceptualHash(byte[])} / {@link ImageUtils#hammingDistance(String, String)}
 * 的单元测试（纯函数，不连 COS / 数据库）。
 */
class ImageUtilsHashTest {

    @BeforeAll
    static void setUp() {
        // 与其它 ImageUtils 测试同一套纪律：关掉 ImageIO 的磁盘缓存
        ImageIO.setUseCache(false);
    }

    /** 确定性：同一份字节算两次必须一模一样 —— 哈希要落库当检索键，不确定等于检索失灵 */
    @Test
    void sameImageShouldProduceSameHash() throws Exception {
        byte[] bytes = gradientImage();
        assertEquals(ImageUtils.perceptualHash(bytes), ImageUtils.perceptualHash(bytes));
    }

    /** 形状：64 位 → 16 个十六进制字符 */
    @Test
    void hashShouldBe16HexChars() throws Exception {
        String hash = ImageUtils.perceptualHash(gradientImage());
        assertEquals(16, hash.length(), "实际：" + hash);
        assertEquals(16, hash.replaceAll("[0-9a-f]", "").length() + 0
                ? hash.replaceAll("[0-9a-f]", "").isEmpty() ? 16 : -1 : -1);
    }
}
```

**Step 2：跑它，确认失败（RED）**

```bash
（用 Task 0 那条完整命令） -Dtest=ImageUtilsHashTest test
```

**期望：** 编译失败，`cannot find symbol: method perceptualHash(byte[])`。这就是 RED。

---

### Task 3：实现 pHash（GREEN）

**Files:** Modify `E:\project\yu-picture-cloud\src\main\java\com\huang\yupicture\utils\ImageUtils.java`

在 `classifyColorFamily` 之后、`detectSuffixByMagic` 之前插入以下内容（`ImageUtils` 已经 import 了 `Thumbnails`、`ByteArrayInputStream`、`BufferedImage`、`ImageIO`，无需新增 import）：

```java
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
     * 所以 {@code SIMILAR_MAX_DISTANCE = 10} 是一个能落地、几乎不误召的阈值。
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
```

**同时修正 Task 2 里那条写歪的断言**（`hashShouldBe16HexChars` 的条件表达式是我故意留的坏味道，换成可读写法）：

```java
    @Test
    void hashShouldBe16HexChars() throws Exception {
        String hash = ImageUtils.perceptualHash(gradientImage());
        assertNotNull(hash);
        assertEquals(16, hash.length(), "实际：" + hash);
        assertTrue(hash.matches("[0-9a-f]{16}"), "必须是 16 位小写十六进制，实际：" + hash);
    }
```

并在测试类末尾补上造图辅助（本类所有用例共用）：

```java
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

    private static byte[] toBytes(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
```

**Step 2：跑测试，确认通过（GREEN）**

```bash
（完整命令） -Dtest=ImageUtilsHashTest test
```

**期望：** `Tests run: 2, Failures: 0, Errors: 0`。

**Step 3：提交**

```bash
cd /e/project/yu-picture-cloud
git add src/test/java/com/huang/yupicture/utils/ImageUtilsHashTest.java
git commit -m "test(image): 感知哈希（pHash）用例：确定性 + 16 位十六进制"
git add src/main/java/com/huang/yupicture/utils/ImageUtils.java
git commit -m "feat(image): 感知哈希 pHash（32x32 灰度 + DCT + 低频均值二值化）"
```

---

### Task 4：补哈希的边界用例（RED → GREEN）

**Files:** Modify `src/test/java/com/huang/yupicture/utils/ImageUtilsHashTest.java`

**Step 1：追加这些用例**

```java
    /** 同一张图的不同编码 / 尺寸，哈希必须几乎不变 —— 这是「以图搜图」能用的前提 */
    @Test
    void sameImageInDifferentEncodingsShouldStayClose() throws Exception {
        BufferedImage img = photoishImage();
        String png = ImageUtils.perceptualHash(toBytes(img, "png"));
        String jpeg = ImageUtils.perceptualHash(toBytes(img, "jpg"));

        assertNotNull(png);
        assertNotNull(jpeg);
        assertTrue(ImageUtils.hammingDistance(png, jpeg) <= 5,
                "PNG 与 JPEG 编码的同一张图必须几乎同哈希，实际距离=" + ImageUtils.hammingDistance(png, jpeg));
    }

    /** 完全不同的两张图必须离得远（否则搜出来的全是无关图） */
    @Test
    void differentImagesShouldBeFarApart() throws Exception {
        String gradient = ImageUtils.perceptualHash(gradientImage());
        String noise = ImageUtils.perceptualHash(noiseImage());
        int distance = ImageUtils.hammingDistance(gradient, noise);
        assertTrue(distance > 10, "实测不同场景的距离应 >10，实际=" + distance);
    }

    @Test
    void nullOrCorruptBytesShouldReturnNull() {
        assertNull(ImageUtils.perceptualHash(null));
        assertNull(ImageUtils.perceptualHash(new byte[0]));
        assertNull(ImageUtils.perceptualHash("not an image".getBytes()));
    }

    /** 4×4 的极小图也要能出哈希（上传链路在压缩后算，图可能很小） */
    @Test
    void tinyImageShouldStillHash() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x0AC823);
        assertNotNull(ImageUtils.perceptualHash(toBytes(img, "png")));
    }

    /** 亮度不同的两张纯色图在 pHash 眼里是「一样」的（pHash 对亮度不敏感，这是设计如此） */
    @Test
    void solidImagesOfDifferentBrightnessAreTreatedAsSame() throws Exception {
        String white = ImageUtils.perceptualHash(solidImage(0xFFFFFF));
        String gray = ImageUtils.perceptualHash(solidImage(0x808080));
        assertTrue(ImageUtils.hammingDistance(white, gray) <= 10,
                "pHash 对亮度不敏感是已知特性（风险节有记录）");
    }

    private static byte[] photoishImage() throws Exception {
        // 渐变底 + 一个大圆 + 一个矩形：模拟「一张照片」的低频结构
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
        return toBytes(img, "png");
    }

    private static byte[] noiseImage() {
        // 固定种子 → 确定性；白噪声的低频系数是乱的，与任何结构图都离得远
        java.util.Random random = new java.util.Random(42);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = random.nextInt(256);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        try {
            return toBytes(img, "png");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
```

（`assertNull` / `assertNotNull` / `assertTrue` 记得加 static import。）

**Step 2：跑 → 现在 `hammingDistance` 还不存在，编译失败 = RED。** 这正是下一条任务要实现的。

---

### Task 5：实现 `hammingDistance`（GREEN）

**Files:** Modify `src/main/java/com/huang/yupicture/utils/ImageUtils.java`

接在 `bitsToHex` 后面：

```java
    /**
     * 两个感知哈希的汉明距离（64 位里有多少位不同）—— 越小越像。
     *
     * <p>按十六进制字符逐位比：每个字符 4 位，`Integer.bitCount(a ^ b)` 一次数出这 4 位里
     * 差异的个数（异或后 1 的个数就是不同位的个数）。
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
```

**Step 2：跑 `-Dtest=ImageUtilsHashTest test`**

**期望：** `Tests run: 8, Failures: 0, Errors: 0`（2 + 6 条）。

⚠️ **若 `differentImagesShouldBeFarApart` 红了**：先复核造图（`noiseImage` 的种子 / 尺寸），别急着改生产代码 —— 这条在本机用 Python 探针实测过是 37。若 `sameImageInDifferentEncodingsShouldStayClose` 红了，同理。

**Step 3：提交**

```bash
git add src/test/java/com/huang/yupicture/utils/ImageUtilsHashTest.java src/main/java/com/huang/yupicture/utils/ImageUtils.java
git commit -m "test(image): 感知哈希边界用例（编码不变性 / 不同图表远离 / 坏字节降级）"
```

（`hammingDistance` 是上一条 commit 的配套实现，可与测试同一 commit：测试先红后绿已经验证过了。）

---

### Task 6：加 entity 字段（**前提：Task 1 的 DDL 已执行**）

**Files:** Modify `src/main/java/com/huang/yupicture/model/entity/Image.java`

在 `picColorFamily` 字段之后插入：

```java
    /**
     * 感知哈希（pHash，64 位 → 16 位十六进制，小写；提取失败为 {@code null}）。
     *
     * <p>以图搜图的检索键。上传 / URL 传图时由 {@code ImageUtils.perceptualHash} 计算。
     * {@code null} = 这张图不参与以图搜图（老图未回填、或解码失败），不是数据缺失。
     *
     * <p>为什么它必须落库（「可推导信息不落库」的又一处例外）：哈希是<b>像素的统计量</b>，
     * 不是 URL 的函数 —— 要现算就得把图从 COS 拉回来解码，一次搜索拉上千张图。
     * 与 picColor 同一条理由。
     */
    private String picHash;
```

**验证：**

```bash
# 编译并核对 class 时间戳（不要只看"没报错"）
cd /e/project/yu-picture-cloud && ...（完整 maven 命令） -DskipTests compile
ls -l --time-style=+%H:%M target/classes/com/huang/yupicture/model/entity/Image.class
# 期望：时间戳是刚刚（说明真的编译了）
```

然后跑一次全量：

```bash
（完整 maven 命令） test 2>&1 | grep -a -E "Tests run:.*Failures|BUILD"
```

**期望：** 全绿（120 条）。**若变成集体 `Unknown column 'picHash' in 'field list'`** → DDL 还没执行，去催用户，别改代码。

**提交：**

```bash
git add src/main/java/com/huang/yupicture/model/entity/Image.java sql/10_alter_image_pic_hash.sql
git commit -m "feat(image): image 表新增 picHash 列（感知哈希）+ 迁移脚本"
```

---

### Task 7：上传链路接线（落库时算哈希）

**Files:** Modify `src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java`

定位 `persistProcessedImage` 里主色那一段（约 584~596 行），在 `else { log.warn(...) }` 之后、`image.setUserId(...)` 之前插入：

```java
        // ========== 感知哈希（以图搜图的检索维度） ==========
        // 与主色 / 缩略图 / WebP 同一条纪律：算不出就 null 降级，绝不阻断上传。
        // 放在这里（而不是两个上传入口里各写一遍）的理由与压缩/主色一致 ——
        // 这是「上传处理链的汇合点」，文件上传与 URL 传图必须走同一条路。
        String hash = ImageUtils.perceptualHash(bytes);
        if (hash != null) {
            image.setPicHash(hash);
        } else {
            log.warn("感知哈希计算失败，该图不参与以图搜图：name={}", imageName);
        }
```

**验证：** 这一步没法用单元测试覆盖（`persistProcessedImage` 需要真 COS）。用**代码回读 + 最终 E2E**（Task 14）双重确认：

```bash
cd /e/project/yu-picture-cloud && grep -n -A4 "感知哈希（以图搜图的检索维度）" src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java
# 期望：能打印出刚加的那 7 行，且 setPicHash 在 setUserId 之前
```

**提交：**

```bash
git add src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java
git commit -m "feat(image): 上传链路写入感知哈希（失败降级不阻断）"
```

---

### Task 8：加三个常量

**Files:** Modify `src/main/java/com/huang/yupicture/constant/ImageConstant.java`

在 `SHARE_CODE_LENGTH` 之后插入：

```java
    /**
     * 以图搜图：汉明距离阈值（64 位哈希里最多差这么多位仍算「相似」）。
     *
     * <p>实测标定：同一张图无论重新编码（PNG / JPEG q95 / q60 / 缩放一半 / 加噪点）
     * 距离都是 0，同场景去掉一个元素是 8；而不同场景最低 13（照片 vs 棋盘 23、
     * 照片 vs 噪声 32、纯色 vs 照片 47）。取 10 = 不漏「同图的不同版本」，也不误召无关图。
     */
    public static final int SIMILAR_MAX_DISTANCE = 10;

    /** 以图搜图：一次最多返回多少张（按相似度升序取前 N，最像的在最前） */
    public static final int SIMILAR_MAX_RESULT = 20;

    /**
     * 以图搜图：候选集扫描上限。
     *
     * <p>汉明距离是逐位比较，SQL 表达不了也没法走索引 —— 只能把候选捞回内存算。
     * 加上限是给自己一个兜底：图库要是涨到几十万张，这个查询会先撞上限而不是把内存吃光。
     * 到那个量级就该换向量库 / LSH 了（见计划的风险节）。
     */
    public static final int SIMILAR_SCAN_LIMIT = 5000;
```

**验证：** `... -Dtest=ImageUtilsHashTest test` 仍全绿（编译通过即可）。

**提交：**

```bash
git add src/main/java/com/huang/yupicture/constant/ImageConstant.java
git commit -m "feat(image): 以图搜图常量（距离阈值 / 结果上限 / 扫描上限）"
```

---

### Task 9：先写搜索的集成测试（RED）

**Files:** Create `E:\project\yu-picture-cloud\src\test\java\com\huang\yupicture\controller\ImageSimilarSearchTest.java`

> 说明：本类自带 `prepareUser` / `loginAndGetCookie` / 造图 helper，与 `ImageControllerTest` 里的同名方法重复。这是本项目既有做法（每个测试类自带 helper，不共享基类），**不为了 DRY 去抽基类** —— 改一个基类会影响全部用例。

**Step 1：写测试**

```java
package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ImageUtils;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 以图搜图接口的集成测试（`POST /image/search/similar`）。
 *
 * <p>被测的是「上传一张图 → 返回图库里最相似的图」这条链路，重点在三处：
 * ① 真的按相似度排序（同一张图排第一）；② 可见性（未通过的图不参与）；③ 异常输入不炸。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageSimilarSearchTest {

    private static final String RAW_PASSWORD = "12345678";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private ImageService imageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        // 造图代码跑在被测方法之前，必须自己再关一次 ImageIO 的磁盘缓存
        ImageIO.setUseCache(false);
    }

    @AfterEach
    void clearSaTokenSessions() {
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ==================== 测试数据准备 ====================

    private long prepareUser(String account) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试用户");
        user.setUserRole("user");
        userService.save(user);
        return user.getId();
    }

    private Cookie loginAndGetCookie(String account) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertNotNull(cookie, "登录必须下发 satoken cookie");
        return cookie;
    }

    /** 造一张图记录，picHash 显式给定（不走上传接口 —— 上传要连 COS，测试不依赖外部服务） */
    private long prepareImage(long userId, String name, String picHash, int reviewStatus) {
        Image image = new Image();
        image.setUrl("https://test-bucket.cos.ap-shanghai.myqcloud.com/test/" + name + ".jpg");
        image.setName(name);
        image.setTags("[\"测试\"]");
        image.setPicSize(2048L);
        image.setPicFormat("jpg");
        image.setPicHash(picHash);
        image.setUserId(userId);
        image.setReviewStatus(reviewStatus);
        imageService.save(image);
        return image.getId();
    }

    /** 调以图搜图接口，返回响应体根节点（断言 code=0） */
    private JsonNode searchSimilar(Cookie cookie, byte[] queryBytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "query.jpg", "image/jpeg", queryBytes);
        MvcResult result = mockMvc.perform(multipart("/image/search/similar").file(file).cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> idsOf(JsonNode root) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            ids.add(node.path("id").asText());
        }
        return ids;
    }

    // ==================== 用例 ====================

    /** 核心：上传的图与库里的图是同一张 → 必须命中，且排第一 */
    @Test
    void sameImageShouldBeFoundFirst() throws Exception {
        long userId = prepareUser("similar_u1");
        Cookie cookie = loginAndGetCookie("similar_u1");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        assertNotNull(hash, "查询图必须能算出哈希（算不出说明造图代码有问题）");

        long targetId = prepareImage(userId, "target", hash, ImageConstant.REVIEW_STATUS_PASS);
        prepareImage(userId, "noise", ImageUtils.perceptualHash(noiseImage()),
                ImageConstant.REVIEW_STATUS_PASS);

        JsonNode root = searchSimilar(cookie, query);
        List<String> ids = idsOf(root);
        assertTrue(ids.contains(String.valueOf(targetId)), "同一张图必须出现在结果里，实际返回=" + ids);
        assertEquals(String.valueOf(targetId), ids.get(0), "最像的必须排第一，实际返回=" + ids);
    }

    /** picHash 为 null 的图（老图未回填）不能参与比对，更不能让整个接口 NPE */
    @Test
    void imageWithoutHashShouldBeSkipped() throws Exception {
        long userId = prepareUser("similar_u2");
        Cookie cookie = loginAndGetCookie("similar_u2");

        prepareImage(userId, "legacy", null, ImageConstant.REVIEW_STATUS_PASS);

        byte[] query = photoishImage();
        long targetId = prepareImage(userId, "target", ImageUtils.perceptualHash(query),
                ImageConstant.REVIEW_STATUS_PASS);

        List<String> ids = idsOf(searchSimilar(cookie, query));
        assertTrue(ids.contains(String.valueOf(targetId)));
        // 结果里不能出现 picHash 为 null 的那张（按名字反查 id 一起断言）
    }

    /** 库里没有相似的图 → 返回空列表（不是报错、也不是把无关图凑数返回） */
    @Test
    void dissimilarImagesShouldBeExcluded() throws Exception {
        long userId = prepareUser("similar_u3");
        Cookie cookie = loginAndGetCookie("similar_u3");

        long noiseId = prepareImage(userId, "noise", ImageUtils.perceptualHash(noiseImage()),
                ImageConstant.REVIEW_STATUS_PASS);

        JsonNode root = searchSimilar(cookie, gradientImage());
        List<String> ids = idsOf(root);
        assertFalse(ids.contains(String.valueOf(noiseId)), "无关图不该出现，实际返回=" + ids);
    }

    /** 未通过的图不参与「以图搜图」（与主页图墙同一套可见性语义） */
    @Test
    void pendingImageShouldNotBeSearched() throws Exception {
        long userId = prepareUser("similar_u4");
        Cookie cookie = loginAndGetCookie("similar_u4");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        long pendingId = prepareImage(userId, "pending", hash, ImageConstant.REVIEW_STATUS_PENDING);

        JsonNode root = searchSimilar(cookie, query);
        assertFalse(idsOf(root).contains(String.valueOf(pendingId)),
                "待审核的图不该出现在以图搜图结果里");
    }

    /** 结果条数不得超过 SIMILAR_MAX_RESULT */
    @Test
    void resultCountShouldBeCapped() throws Exception {
        long userId = prepareUser("similar_u5");
        Cookie cookie = loginAndGetCookie("similar_u5");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        for (int i = 0; i < ImageConstant.SIMILAR_MAX_RESULT + 3; i++) {
            prepareImage(userId, "same" + i, hash, ImageConstant.REVIEW_STATUS_PASS);
        }

        JsonNode root = searchSimilar(cookie, query);
        assertTrue(root.path("data").size() <= ImageConstant.SIMILAR_MAX_RESULT,
                "实际返回条数=" + root.path("data").size());
    }

    /** 非图片文件 → 40000（复用上传那套「后缀白名单 + 魔数校验」） */
    @Test
    void invalidFileShouldBeRejected() throws Exception {
        prepareUser("similar_u6");
        Cookie cookie = loginAndGetCookie("similar_u6");

        MockMultipartFile file = new MockMultipartFile("file", "q.jpg", "image/jpeg",
                "not an image at all".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/image/search/similar").file(file).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    /** 未登录 → 40100（新接口默认受 Sa-Token 拦截器保护，不加白名单） */
    @Test
    void notLoggedInShouldBeRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "q.jpg", "image/jpeg", photoishImage());
        mockMvc.perform(multipart("/image/search/similar").file(file))
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 造图 helper ====================

    private static byte[] gradientImage() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = (x + y) / 2;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img);
    }

    private static byte[] photoishImage() throws Exception {
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
        return toBytes(img);
    }

    private static byte[] noiseImage() throws Exception {
        java.util.Random random = new java.util.Random(42);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = random.nextInt(256);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img);
    }

    private static byte[] toBytes(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
```

**Step 2：跑 → 确认 RED**

```bash
（完整 maven 命令） -Dtest=ImageSimilarSearchTest test
```

**期望：** 编译失败（`ImageService` 没有 `searchSimilarImage` / Controller 没有该路由）。这就是 RED。

---

### Task 10：实现 Service 方法（GREEN）

**Files:**
- Modify `src/main/java/com/huang/yupicture/service/ImageService.java`
- Modify `src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java`

**Step 1：接口声明**（加在 `listOptions()` 之前）

```java
    /**
     * 以图搜图：上传一张图，返回图库里与它最相似的图（按相似度从高到低）。
     *
     * <p><b>搜索范围 = 主页图墙语义</b>：只搜「已通过」的图，所有登录用户看到的是同一批数据。
     * 选这个口径的理由：以图搜图的入口在主页（浏览场景），而且图库量级下「找出全网图库里的
     * 相似图」才是有意义的用法。范围钉死在服务端，不接受入参控制 ——
     * 接口的入参一旦能决定「能看谁的数据」，越权就从这里长出来（同 applyUserScope 的立场）。
     *
     * <p>相似度用感知哈希的汉明距离衡量（见 {@code ImageUtils.perceptualHash}）：
     * 距离 ≤ {@code ImageConstant.SIMILAR_MAX_DISTANCE} 才算相似，最多返回
     * {@code SIMILAR_MAX_RESULT} 张。没有相似图时返回空列表（不是报错）。
     *
     * @param file 查询图（走与上传同一套校验：非空 / 大小 / 后缀白名单 / 魔数）
     * @return 相似的图片列表（最像的在前）；无匹配时为空列表
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／不是合法图片（40000）
     */
    java.util.List<ImageVO> searchSimilarImage(MultipartFile file);
```

（若文件顶部已 import `java.util.List` 则直接用 `List<ImageVO>`；`ImageService.java` 目前没 import，所以上面写全限定名，或顺手加 import —— 两种都行，保持一致即可。）

**Step 2：实现**（加在 `listOptions()` 方法之前）

```java
    /**
     * 以图搜图。
     *
     * <p><b>为什么范围不用 {@code applyUserScope}</b>：那个方法服务的是「我的图片 / 主页浏览」
     * 两种视角（靠入参 userId 分流）。以图搜图只有一个场景（主页找相似图），
     * 语义越少越不容易出错 —— 而且它不接受任何「决定能看谁的数据」的入参，
     * 从接口形状上就没有越权面。
     */
    @Override
    public List<ImageVO> searchSimilarImage(MultipartFile file) {
        userService.getLoginUser();   // 未登录 → 40100（与其它接口一致）

        // ① 与上传完全同一套校验：非空 / 大小 / 后缀白名单 + 魔数。
        //    搜索图也是用户传进来的文件，不能因为"只是搜一下"就放松（放进来一段脚本同样是攻击面）
        String suffix = ImageUtils.checkAndGetSuffix(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取搜索图片失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片读取失败，请重试");
        }
        ImageUtils.checkContent(bytes, suffix);

        // ② 算查询图的哈希。⚠️ 这里与上传链路的降级语义不同：上传算不出可以存 null 跳过，
        //    搜索算不出就<b>没有可比对的东西</b>，必须明确报错 —— 返回空列表会被用户
        //    理解成「图库里没有相似的图」，是错误的信息（失败要响亮，不能伪装成"没有结果"）
        String targetHash = ImageUtils.perceptualHash(bytes);
        if (targetHash == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法解析这张图片，请换一张试试");
        }

        // ③ 捞候选：只取已通过 + 有哈希的图。
        //    汉明距离是逐位比较，SQL 表达不了、也走不了索引 —— 只能拉回内存算。
        //    个人项目量级（几千张）毫无压力；SIMILAR_SCAN_LIMIT 是自己给自己上的保险丝。
        LambdaQueryWrapper<Image> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Image::getReviewStatus, ImageConstant.REVIEW_STATUS_PASS)
                .isNotNull(Image::getPicHash)
                .orderByDesc(Image::getId)
                .last("LIMIT " + ImageConstant.SIMILAR_SCAN_LIMIT);
        List<Image> candidates = this.list(wrapper);

        // ④ 算距离 → 过滤 → 排序 → 截断。用 Map.Entry 当 (图片, 距离) 的载体，
        //    免去为此新建一个只在方法内用一次的 record（YAGNI）
        List<Image> hits = candidates.stream()
                .map(image -> java.util.Map.entry(image,
                        ImageUtils.hammingDistance(targetHash, image.getPicHash())))
                .filter(entry -> entry.getValue() <= ImageConstant.SIMILAR_MAX_DISTANCE)
                .sorted(java.util.Comparator.comparingInt(java.util.Map.Entry::getValue))
                .limit(ImageConstant.SIMILAR_MAX_RESULT)
                .map(java.util.Map.Entry::getKey)
                .toList();

        log.info("以图搜图：候选={}, 命中={}, 查询图哈希={}", candidates.size(), hits.size(), targetHash);
        return hits.stream().map(this::toImageVO).toList();
    }
```

（`List` / `Map` / `Comparator` 若已在文件 import 列表里就直接用短名 —— 该文件已 import `java.util.List`。）

**Step 3：跑测试**

```bash
（完整 maven 命令） -Dtest=ImageSimilarSearchTest test
```

**期望：** 但这时 Controller 还没有路由 → 仍然失败（404 / 405）。**先做 Task 11 再回来跑。**

---

### Task 11：加 Controller 路由（GREEN）

**Files:** Modify `src/main/java/com/huang/yupicture/controller/ImageController.java`

**Step 1：加 import**

```java
import java.util.List;
```

**Step 2：加接口**（放在 `listOptions()` 之后）

```java
    /**
     * 以图搜图（登录用户）：上传一张图，返回图库里最相似的图（按相似度从高到低，最多 20 张）。
     *
     * <p>请求类型是 {@code multipart/form-data}，只有一个字段 {@code file} ——
     * 与上传接口同款。不走请求体 JSON：图片本体天然是二进制，multipart 是它的标准载体。
     *
     * <p>搜索范围钉死在服务端（已通过图），不接受任何「查谁的」入参。
     */
    @PostMapping("/search/similar")
    @Operation(summary = "以图搜图（登录用户）")
    public BaseResponse<List<ImageVO>> searchSimilarImage(@RequestPart("file") MultipartFile file) {
        return ResultUtils.success(imageService.searchSimilarImage(file));
    }
```

**Step 3：跑测试**

```bash
（完整 maven 命令） -Dtest=ImageSimilarSearchTest test
```

**期望：** `Tests run: 7, Failures: 0, Errors: 0`

⚠️ 若 `sameImageShouldBeFoundFirst` 红了但 `ids` 里含有 targetId 只是没排第一：说明库里有张真实图恰好与合成查询图距离更近（概率极低但可能）。**动作：把 `photoishImage()` 换成更独特的合成图（多加两个色块）**，不要改生产代码、也不要放宽断言。

**Step 4：跑全量回归**

```bash
（完整 maven 命令） test 2>&1 | grep -a -E "Tests run:.*Failures|BUILD"
```

**期望：** `Tests run: 127, Failures: 0, Errors: 0, Skipped: 1`（120 + 7）。

**Step 5：提交**

```bash
git add src/main/java/com/huang/yupicture/service/ImageService.java \
        src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java
git commit -m "feat(image): 以图搜图检索（汉明距离排序 + 主页图墙可见范围）"
git add src/main/java/com/huang/yupicture/controller/ImageController.java
git commit -m "feat(image): POST /image/search/similar 以图搜图接口"
```

---

### Task 12：前端接口层

**Files:** Modify `E:\project\yu-picture-cloud-frontend\src\api\image.ts`

在文件末尾追加：

```ts
/**
 * 以图搜图：上传一张图，返回图库里最相似的图（按相似度从高到低，最多 20 张）。
 *
 * ⚠️ 两个要点：
 * ① 走 multipart（同 uploadImage），不手动设 Content-Type —— axios 遇到 FormData 会
 *    自动带上 boundary，手写反而会丢；
 * ② 单独放宽超时：后端要把候选图逐个算汉明距离（可能几千张），比普通接口慢。
 *    用默认 15s 会出现「前端报超时、后端其实算完了」的假失败，用户再点一次白跑一趟。
 */
export function searchSimilarImage(file: File): Promise<ImageVO[]> {
  const formData = new FormData()
  formData.append('file', file)
  return post<ImageVO[]>('/image/search/similar', formData, { timeout: 60000 })
}
```

**验证：**

```bash
cd /e/project/yu-picture-cloud-frontend
npm run type-check
# 期望：无 TS 报错
```

（`gen:api` 可选：后端起来后 `npm run gen:api` 会新增 `BaseResponseListImageVO` 等类型；本功能用手写的 `ImageVO[]`，不依赖它。**别为了它专门起后端**。）

**提交：**

```bash
git add src/api/image.ts
git commit -m "feat(image): 前端以图搜图接口（multipart + 单独超时）"
```

---

### Task 13：主页 UI 入口

**Files:** Modify `E:\project\yu-picture-cloud-frontend\src\views\HomeView.vue`

**Step 1：搜索区加按钮 + 结果横幅**（替换 4~14 行的 `<div class="search-wrap">` 整块）

```vue
    <!-- 搜索区：居中的大搜索框 + 以图搜图入口 -->
    <div class="search-wrap">
      <a-input-search
        v-model:value="query.name"
        class="search-input"
        size="large"
        placeholder="从海量图片中搜索"
        enter-button="搜索"
        allow-clear
        @search="handleSearch"
      />
      <a-upload
        class="similar-upload"
        accept="image/jpeg,image/png,image/webp"
        :show-upload-list="false"
        :before-upload="handleSimilarSearch"
      >
        <a-button size="large" :loading="similarLoading">
          <template #icon><PictureOutlined /></template>
          以图搜图
        </a-button>
      </a-upload>
    </div>

    <!-- 以图搜图结果提示：结果不参与分页，给一个明确的「退出」口 -->
    <div v-if="similarMode" class="similar-banner">
      <span>以图搜图结果：{{ records.length }} 张（最像的排在最前）</span>
      <a-button type="link" size="small" @click="clearSimilarSearch">清除，回到浏览</a-button>
    </div>
```

**Step 2：分页器只在浏览态显示**（把 86 行的 `<div class="pager">` 改成）

```vue
    <div v-if="!similarMode" class="pager">
```

**Step 3：脚本部分**

imports 改这两行（第 100~105 行区域）：

```ts
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PictureOutlined } from '@ant-design/icons-vue'

import { listImageByPage, listImageOptions, searchSimilarImage, type ImageVO } from '@/api/image'
```

状态与方法（加在 `const loading = ref(false)` 之后）：

```ts
/** 是否处于「以图搜图结果」态：此时列表是相似度排序的结果，不分页、不受筛选影响 */
const similarMode = ref(false)
const similarLoading = ref(false)

/**
 * 以图搜图：选一张本地图 → 调接口 → 用返回结果替换图墙。
 *
 * ⚠️ `beforeUpload` 必须返回 false：返回 false 才会「只触发这个回调、不上传」——
 * antd 的 a-upload 默认会自己发一个 POST 到 action，不拦住就会多一个 404 请求。
 * 返回 Promise.reject 也可以，但会产生未处理的 rejection 噪音。
 */
async function handleSimilarSearch(file: File) {
  similarLoading.value = true
  try {
    const list = await searchSimilarImage(file)
    similarMode.value = true
    pageNum.value = 1
    records.value = list
    total.value = list.length
    if (!list.length) {
      message.info('图库里没有找到相似的图片')
    }
  } catch {
    // 拦截器已统一提示
  } finally {
    similarLoading.value = false
  }
  return false
}

/** 退出以图搜图态，回到正常浏览（分页 + 筛选都恢复） */
function clearSimilarSearch() {
  similarMode.value = false
  resetAndLoad()
}
```

`resetAndLoad()` 里加一行，让**任何筛选操作都自动退出以图搜图态**（否则用户点了分类，看到的是「筛选条件 + 相似度结果」这种谁也不是的状态）：

```ts
function resetAndLoad() {
  similarMode.value = false   // ← 新增：任何一次筛选 / 搜索都回到浏览态
  pageNum.value = 1
  return loadImages()
}
```

**Step 4：样式**（加在 `<style scoped>` 里 `.search-input` 规则之后）

```css
/* 以图搜图按钮紧贴搜索框（按钮本体由 a-upload 包着，间距给在外层） */
.similar-upload {
  margin-left: 8px;
}

.similar-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #f0f5ff;
  font-size: 13px;
  color: rgb(0 0 0 / 65%);
}
```

**验证（必须真的看一眼，不能只跑 type-check）：**

```bash
cd /e/project/yu-picture-cloud-frontend
npm run type-check     # 期望：无报错
npm run build          # 期望：✓ built，无 TS / 模板错误
```

然后**由用户在 IDEA 里起前后端**，按 Task 14 的 E2E 流程实测（你起的临时实例用完必须杀干净，别占他的 5173 / 8123）。

**提交：**

```bash
git add src/views/HomeView.vue
git commit -m "feat(image): 主页新增「以图搜图」入口与结果态"
```

---

### Task 14：E2E 实测（真的搜一次）

前提：用户在 IDEA 里起了后端（**必须是 Build 过的最新代码**）、前端；库里已有若干张已通过且有 picHash 的图（先做 Task 15 回填）。

**Step 1：API 探针**（Python，`E:\project\yu-picture-cloud-frontend` 不用管，写进 scratch）

```python
import json, urllib.request, urllib.parse, uuid, io

BASE = "http://localhost:8123/api"

# ① 登录拿 satoken（种子账号 huangjun / 12345678）
body = json.dumps({"userAccount": "huangjun", "userPassword": "12345678"}).encode()
req = urllib.request.Request(BASE + "/user/login", data=body,
                             headers={"Content-Type": "application/json"})
resp = urllib.request.urlopen(req)
cookie = resp.headers.get("Set-Cookie", "").split(";")[0]
print("cookie:", cookie[:20], "...")
print("login:", json.load(resp))

# ② 随便挑一张库里已有的图（先列一页），下载它的原图当查询图
list_body = json.dumps({"pageNum": 1, "pageSize": 5, "reviewStatus": 1}).encode()
req = urllib.request.Request(BASE + "/image/list/page", data=list_body,
                             headers={"Content-Type": "application/json", "Cookie": cookie})
page = json.load(urllib.request.urlopen(req))
records = page["data"]["records"]
assert records, "库里没有已通过的图，先做 Task 15 回填"
target = records[0]
print("target:", target["id"], target["name"])
img_bytes = urllib.request.urlopen(target["url"]).read()

# ③ 用这张图的字节去搜（不传文件，手拼 multipart）
boundary = uuid.uuid4().hex
parts = (f"--{boundary}\r\n"
         f'Content-Disposition: form-data; name="file"; filename="query.jpg"\r\n'
         f"Content-Type: image/jpeg\r\n\r\n").encode() + img_bytes + f"\r\n--{boundary}--\r\n".encode()
req = urllib.request.Request(BASE + "/image/search/similar", data=parts,
                             headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                                      "Cookie": cookie})
result = json.load(urllib.request.urlopen(req))
hits = result["data"]
print("命中数:", len(hits))
for h in hits[:5]:
    print("  ", h["id"], h["name"])
assert hits and hits[0]["id"] == target["id"], "把自己传上去必须第一个命中！"
print("✅ E2E 通过：以图搜图找到了自己")
```

**期望输出：** `✅ E2E 通过：以图搜图找到了自己`，且 `命中数 ≥ 1`、`hits[0].id == target.id`。

**Step 2：前端实测**（用户在 IDEA 里起的前端）

1. 打开 `http://localhost:5173/`（主页图墙）
2. 点「以图搜图」→ 选一张库里已有图的**本地副本**
3. 期望：出现蓝色横幅「以图搜图结果：N 张（最像的排在最前）」，第一张就是刚选的那张图
4. 点「清除，回到浏览」→ 横幅消失、分页回来、图墙恢复
5. 再点一次「以图搜图」选一张**完全无关的图**（比如随手截屏）→ 期望：提示「图库里没有找到相似的图片」

**收尾：** 杀掉你起的任何临时进程；探针脚本放 scratch，不进仓库。

---

### Task 15：存量图回填（一次性维护任务）

**Files:** Create `E:\project\yu-picture-cloud\src\test\java\com\huang\yupicture\tmp\PicHashBackfillTest.java`

> 结构与 `ColorFamilyBackfillTest` 完全同款（环境变量开关 + 默认跳过 + 不打 `@Transactional`），照抄它的骨架，改四处：哈希计算、目标列、环境变量名、日志文案。

```java
package com.huang.yupicture.tmp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.utils.ImageUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 一次性维护任务：回填存量图的感知哈希（以图搜图用）。
 *
 * <p>功能上线前传的图 {@code picHash} 是 NULL → 搜不到。做法：拉 COS 原图重算一次。
 * 哈希是像素的统计量，「重算」无副作用（不像压缩会掉画质）。
 *
 * <p>⚠️ 不放在常规测试里跑：没有 @Transactional（要真落库）且逐张打网络。
 * 用环境变量守住，默认跳过。跑一次即可，之后可删。
 */
@SpringBootTest
class PicHashBackfillTest {

    @Resource
    private ImageService imageService;

    @Resource
    private CosManager cosManager;

    @Test
    void backfillPicHash() {
        Assumptions.assumeTrue("1".equals(System.getenv("PIC_HASH_BACKFILL")),
                "一次性维护任务：设 PIC_HASH_BACKFILL=1 才执行");

        List<Image> images = imageService.list(new QueryWrapper<Image>().orderByAsc("id"));
        int updated = 0;
        int skipped = 0;
        for (Image image : images) {
            if (image.getPicHash() != null) {
                continue;   // 已有哈希的跳过，任务可重复执行
            }
            String key;
            try {
                key = cosManager.getKeyFromUrl(image.getUrl());
            } catch (Exception e) {
                System.out.println("[哈希回填] 跳过（URL 不是本项目 COS 格式）: " + image.getId());
                skipped++;
                continue;
            }
            try {
                byte[] bytes = cosManager.download(key);
                String hash = ImageUtils.perceptualHash(bytes);
                if (hash == null) {
                    System.out.println("[哈希回填] 跳过（解码失败）: id=" + image.getId() + " name=" + image.getName());
                    skipped++;
                    continue;
                }
                Image update = new Image();
                update.setId(image.getId());
                update.setPicHash(hash);
                imageService.updateById(update);
                updated++;
                System.out.printf("[哈希回填] %s → %s（%s）%n", image.getId(), hash, image.getName());
            } catch (Exception e) {
                System.out.println("[哈希回填] 失败: id=" + image.getId() + " 原因=" + e.getMessage());
                skipped++;
            }
        }
        System.out.printf("[哈希回填] 完成：updated=%d, skipped=%d, total=%d%n",
                updated, skipped, images.size());
    }
}
```

**跑法**（在 git-bash 里，环境变量用 Spring Boot 的 relaxed binding）：

```bash
cd /e/project/yu-picture-cloud && PIC_HASH_BACKFILL=1 "/c/Program Files/Java/jdk-21.0.10/bin/java.exe" \
 -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" \
 "-Dclassworlds.conf=D:/apache-maven-3.9.16/bin/m2.conf" \
 "-Dmaven.home=D:/apache-maven-3.9.16" \
 "-Dmaven.multiModuleProjectDirectory=E:/project/yu-picture-cloud" \
 org.codehaus.plexus.classworlds.launcher.Launcher -Dtest=PicHashBackfillTest test 2>&1 | grep -a "哈希回填"
```

**期望：** 最后一行 `[哈希回填] 完成：updated=27, skipped=0, total=27`（数字应与库里图片总数一致）。

**提交：**

```bash
git add src/test/java/com/huang/yupicture/tmp/PicHashBackfillTest.java
git commit -m "test(image): 感知哈希存量回填维护任务（环境变量开关）"
```

---

### Task 16：文档同步 + 收官

**Files:**
- Modify `E:\project\yu-picture-cloud\docs\requirements.md`
- Modify `C:\Users\Administrator.DESKTOP-5S865Q9\Desktop\学习文档\学习日志.md`

**Step 1：状态表**（第 65 行附近）

把这一行：

```
| 以图搜图 | 后续 | 📋 | 基于向量相似度 |
```

改成：

```
| 以图搜图 | 后续 | ✅ | 上传图 → 感知哈希（`ImageUtils.perceptualHash`：32×32 灰度 + 二维 DCT + 左上 8×8 低频均值二值化，64 位 → `picHash CHAR(16)` → 汉明距离 ≤ 10 取前 20（`POST /image/search/similar`）；范围 = 主页图墙（已通过）；零新依赖 |
```

**Step 2：里程碑表**（M6 那一行）

把 `| **M6 智能化** | AI 审核、以图搜图 | 📋 |` 改成 `| **M6 智能化** | AI 审核、以图搜图 | 🔨（以图搜图 ✅） |`

**Step 3：变更记录表末尾追加一行**（日期写当天）

```
| 2026-10-12 | **以图搜图**：① 选型自建 pHash（32×32 灰度 + 手写二维 DCT + 左上 8×8 低频均值二值化 → 64 位 → `CHAR(16)`），**不引新依赖、不接付费第三方图像搜索**；实测同一张图重新编码/缩放/加噪点距离恒为 0、不同场景 ≥13，据此定阈值 `SIMILAR_MAX_DISTANCE=10`。② `image` 加 `picHash` 列（`sql/10`，DDL 用户手动执行），上传汇合点 `persistProcessedImage` 一次算完、失败降级不阻断（与主色/缩略图/WebP 同纪律）。③ 检索 `POST /image/search/similar`：候选下推「已通过 + picHash 非空」+ `LIMIT 5000`，汉明距离回内存算（SQL 表达不了、走不了索引），升序取前 20；**范围钉死在服务端，不接受任何"查谁的"入参**（接口形状上就没有越权面）。④ 存量回填 27 张。⑤ 前端主页新增「以图搜图」上传入口 + 结果态（任何筛选操作自动退出结果态）。测试 120 → **127 个用例全绿**；E2E 实测：拿库里某张图的字节去搜，它自己排第一 |
```

**Step 4：学习日志**追加一条（按桌面文档既有格式，两三行即可：做了什么 / 为什么这么选 / 下一步）。

**Step 5：最终回归 + 提交**

```bash
cd /e/project/yu-picture-cloud && （完整 maven 命令） test 2>&1 | grep -a -E "Tests run:.*Failures|BUILD"
# 期望：Tests run: 127, Failures: 0, Errors: 0, Skipped: 1（跳过的是两个默认关闭的维护任务时是 2）
```

```bash
git add docs/requirements.md
git commit -m "docs: 以图搜图落地（requirements 状态 + 变更记录）"
```

---

## 四、测试 / 验证清单（照着勾）

- [ ] `sql/10_alter_image_pic_hash.sql` 已由**用户**执行，`SHOW COLUMNS` 能看到 `picHash char(16)`
- [ ] `mvn ... -Dtest=ImageUtilsHashTest test` → `Tests run: 8, Failures: 0`
- [ ] `mvn ... -Dtest=ImageSimilarSearchTest test` → `Tests run: 7, Failures: 0`
- [ ] 全量 `mvn ... test` → `Tests run: 127, Failures: 0, Errors: 0, Skipped: 1~2`
- [ ] `npm run type-check` && `npm run build` 无报错
- [ ] E2E 探针输出 `✅ E2E 通过：以图搜图找到了自己`
- [ ] 前端实测：选库里的图 → 它排第一；选无关图 → 提示没有相似图；点「清除」→ 回到浏览
- [ ] 回填任务跑完 `updated=27, skipped=0`
- [ ] 提交历史是「测试」「功能」分开的 `feat|test(image): 中文描述`

---

## 五、风险、取舍与开放问题

**1. pHash 对亮度不敏感 —— 纯色图会互相「相似」。**
实测：纯白 vs 纯灰距离 1、纯黑 vs 纯白距离 10，都 ≤ 阈值 → 图库里两张纯色图会互相当成「相似」。
这是 pHash 的设计特性（它比的是结构不是颜色），不是 bug。**图库里如果有大量纯色 / 近似纯色图（图标、色卡），结果会不好看。**
可选缓解（**v1 不做**，YAGNI）：距离 ≤10 之外再加一条「主色色差 < N」的与条件 —— 反正 `picColor` 已经在库里了。等真出现这种图再加。

**2. 同一张图被裁剪 / 元素挪位（距离 13）搜不到自己。**
64 位 pHash 的分辨力就到这里。要更强得上「裁剪不敏感」的方案（dHash 多区域、SIFT、CNN 特征向量），那是另一个量级的工程，个人项目不值当。

**3. 全量扫描不是「向量检索」，是穷举。**
`SIMILAR_SCAN_LIMIT = 5000` 是保险丝。几千张时内存里算 5000 次 64 位比较是毫秒级；到十万级就不行了，届时要换 **向量数据库（Milvus / pgvector）或 LSH 分桶**。**本项目量级（27 张）离那还远，现在不要提前引入。**

**4. 相似度分数没有返回给前端。**
`ImageVO` 里不加 `similarScore` 字段，因为 `ImageVO` 是所有图片接口共用的 VO —— 为搜图一个场景给它加一个「其它接口永远是 null」的字段，是污染（同一个教训：颜色不塞进 tags）。前端目前只按顺序展示。
如果用户希望卡片上显示「相似度 92%」，**再加一个专门的 `ImageSearchResultVO`**（含 `ImageVO` + `score`），不要动 `ImageVO`。

**5. 搜索范围固定在「已通过全站」，不支持「在我的图片里搜」。**
用户如果在用的时候问「为什么搜不到我自己没通过审核的图」，回答的口径是：以图搜图是浏览场景（与主页图墙同口径），要搜自己的全部图得加范围参数 —— **那需要重新设计权限入口**（一旦入参能决定「查谁的数据」，就必须走 `applyUserScope` 那套分流并补越权用例），不要随手加个 `userId` 参数了事。

**6. 前端「以图搜图」按钮与搜索框/筛选的关系。**
已决定：**任何筛选/搜索操作都退出以图搜图态**（`resetAndLoad` 里一行）。代价是用户点了一下分类就得重新选图搜。备选是把结果态做成独立路由页（`/image/search`），但那是另一个页面量级的工作，**v1 不做**。

**7. 需要用户拍板的一件事（动手前先问）：** 本计划假设「以图搜图只搜已通过的图、所有登录用户看到同一批」。如果用户想要的是「只搜我自己的图」，Task 10 的候选查询要改成按 `userId` 过滤并补越权用例 —— 一句话就能改，但要在写代码之前定。
