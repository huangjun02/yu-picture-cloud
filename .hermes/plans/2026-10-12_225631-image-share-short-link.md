# 图片分享短链（Image Share Short Link）实现计划

> 目标仓库：后端 `E:\project\yu-picture-cloud`、前端 `E:\project\yu-picture-cloud-frontend`（两个独立 git 仓）。
> 读本计划的人对代码库零上下文 —— 每处要改的文件、要贴的代码、要跑的验证命令都写全，不要"按需理解"。

---

## 1. Goal

让用户为自己（或管理员为任意）的图片生成一条**免登录可直接打开的前端分享短链** `http://<origin>/share/<shareCode>`，游客凭码就能看图。

---

## 2. Current context / assumptions

### 2.1 已确认的三个决策（用户已拍板）

| 决策点 | 结论 |
|---|---|
| 访问权限 | **免登录可看**：只把「查看分享」这一个接口加进 Sa-Token 白名单；生成分享码仍要求登录 + 归属校验 |
| 短链落点 | **前端分享页** `/share/:code`（含图片预览 + 基本信息，前后端配套） |
| 可见性 | 查看时按现有 `checkImageVisible` 语义：**已通过**任何人（含游客）可看；**未通过**只有本人/管理员可看，其他人（含游客）报 `40400` |

### 2.2 现状（改前必须知道的既有约定，逐条都已在代码里成立）

- 后端端口 8123，`context-path=/api`；前端 5173，Vite 代理 `/api`→8123。
- **Sa-Token 全局拦截器默认拦全部**，白名单在 `SaTokenConfig.WHITE_LIST`（`src/main/java/com/huang/yupicture/config/SaTokenConfig.java`），路径**不带 `/api` 前缀**。
- 统一响应 `{code,data,message}`，`code=0` 成功。错误码：`40000` 参数错、`40100` 未登录、`40101` 无权限、`40400` 数据不存在、`50000` 系统异常（见 `common/ErrorCode.java`）。
- **雪花 id 对外一律 String**（`ImageVO.id`/`userId` 都是 String），Service 里用 `ParamUtils.parseId` 转 long，失败抛 40000。
- **归属校验** `checkImageOwner(image, loginUser)` 是 `private`，在 `ImageServiceImpl`（本人 or 管理员，否则 40101）。
- **可见性校验** `checkImageVisible(image, loginUser)` 也是 `private`，在 `ImageServiceImpl`（本人/管理员全看；其他人只能看已通过，否则 40400）。
- **MP 的 NOT_NULL 更新策略**：`updateById(entity)` 会跳过 null 字段 → "传了才改、没传不动"。刻意**不要**用 `LambdaUpdateWrapper.set(x, null)` 以外的姿势改 null，本功能用不到。
- **DDL 必须由用户手动执行**（我连不了库 / 不读凭据）：新结构追加 `sql/0N_*.sql`；**已执行过的脚本不改语义**；同时把最终结构同步进 `sql/05_create_table_image.sql`（给全新环境一步到位 —— 05 里现在就内联着审核四字段，这是既有先例）。
- **测试**：`@SpringBootTest + @AutoConfigureMockMvc + @Transactional`（类上）；`@AfterEach` 清 `satoken:*`；造数用 helper；断言用**差值法**；一半用例应是越权尝试。
- **前端**：`src/api/request.ts` 的响应拦截器把 `{code,data}` 拆开，`code!==0` 会 `message.error` 并 reject（页面 catch 里不用再弹）。
- **前端路由守卫**（`src/router/index.ts`）：未登录且非公开页 → 踢登录页；**已登录且是公开页 → 踢回 `/`**。

### 2.3 假设

- COS 已配好（本功能不碰 COS）。
- 分享码用随机 Base62、长度 8：`62^8 ≈ 2.18e14`，碰撞概率可忽略。
- 短链的"短"体现在 **码**，域名仍是前端自身 origin（本地即 `http://localhost:5173`）。不引短域名/第三方短链服务。

---

## 3. Architecture / proposed approach

在 `image` 表加一列 `shareCode`（随机唯一码，未分享为 NULL）+ 唯一索引；`ImageServiceImpl` 新增 `shareImage(id)`（本人/管理员，生成幂等分享码）和 `getSharedImage(shareCode)`（按码查图并按可见性放行）；Controller 暴露 `POST /image/share`（需登录）与 `POST /image/share/view`（白名单免登录，返回 `ImageVO`）；前端新增公开页 `/share/:code` 解析码并渲染，详情页加「分享」按钮复制链接。

后端只回**分享码**，完整链接由前端用 `window.location.origin + '/share/' + code` 拼 —— 链接格式天然属于前端的路由知识（`/share/:code` 是前端路由），放后端反而要多一份配置。

---

## 4. Step-by-step tasks

> 约定：后端 Maven 一律走 classworlds 那条命令（`bin/mvn` 在 git-bash 下会"静默成功"）。设一个 shell 变量省得每次重敲：
> ```bash
> MVN='/c/Program Files/Java/jdk-21.0.10/bin/java.exe -classpath D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar -Dclassworlds.conf=D:/apache-maven-3.9.16/bin/m2.conf -Dmaven.home=D:/apache-maven-3.9.16 -Dmaven.multiModuleProjectDirectory=E:/project/yu-picture-cloud org.codehaus.plexus.classworlds.launcher.Launcher'
> cd /e/project/yu-picture-cloud
> # 跑全量：$MVN test
> # 跑单条：$MVN "-Dtest=ImageControllerTest#方法名" test
> ```
> ⚠️ 读测试结果看 `target/surefire-reports/*.txt` 的**时间戳**（累积会留旧报告）；grep 含中文的输出**必须加 `-a`**。

---

### Phase 0 — 数据库（必须先做，且由用户手动执行）

#### Task 0.1 写迁移脚本 `sql/09_alter_image_share_code.sql`

新建文件，内容**逐字**如下：

```sql
-- ============================================================
-- 云图库 yu-picture-cloud · 图片表加分享短链码（图片分享）
-- 执行前提：05_create_table_image.sql（及 06/07/08）已执行
-- 幂等性：⚠️ MySQL 的 ADD COLUMN 没有 IF NOT EXISTS，重复执行会报 Duplicate column —— 执行前先看列在不在。
-- ============================================================

USE yu_picture_cloud;

-- 分享短链码：随机 Base62 8 位；未分享为 NULL。
-- 为什么是随机码而不是自增 / 时间戳：短链是"凭码即可查看"的持有凭证，
-- 顺序或可预测的码等于把别人未公开的图也暴露出去（枚举即可遍历）。
ALTER TABLE `image`
    ADD COLUMN shareCode VARCHAR(16) NULL COMMENT '分享短链码（随机 Base62，未分享为 NULL）' AFTER isDelete;

-- 唯一索引：既是生成时防冲突的兜底（撞码靠它挡住并重试），
-- 也是"按码查图"的索引（短链查询是全表唯一命中，必须走索引）。
-- 用普通唯一索引而非 (shareCode, isDelete) 复合索引：分享码从不复用，
-- 一张被逻辑删除的图保留它的码也不会与任何新码相撞（8 位 Base62 空间 62^8≈2.2e14）。
ALTER TABLE `image`
    ADD UNIQUE KEY uk_shareCode (shareCode);
```

验证：`ls sql/ | tail -1` → 期望输出 `09_alter_image_share_code.sql`。

#### Task 0.2 同步最终结构进 `sql/05_create_table_image.sql`

在 `05` 的建表语句里，`isDelete` 那行**之后**补上分享码列与唯一键，使全新环境一步到位（与 05 里已内联的审核四字段同一个先例）：

把：
```sql
    isDelete     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否删除：0-正常，1-已删除（逻辑删除）',
    PRIMARY KEY (id),
```
改为：
```sql
    isDelete     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否删除：0-正常，1-已删除（逻辑删除）',
    shareCode    VARCHAR(16)   NULL COMMENT '分享短链码（随机 Base62，未分享为 NULL）',
    PRIMARY KEY (id),
    -- 分享短链：按码查图走它；同时防生成时撞码
    UNIQUE KEY uk_shareCode (shareCode),
```

验证：`grep -n "shareCode\|uk_shareCode" sql/05_create_table_image.sql` → 期望两行命中。

#### Task 0.3 ⛔【用户手动执行，阻塞后续】执行 `sql/09`

> 我无法连库（不读凭据）。**这一步必须由用户在本机执行**，否则后面 entity 加了字段，MyBatis-Plus 的 SELECT 会自动带上 `shareCode` 列，所有读 image 表的测试会集体 `Unknown column 'shareCode' in 'field list'`（BadSqlGrammar）—— 看着像代码写错，其实是 DDL 没落库。

用户在 IDEA 打开 `sql/09_alter_image_share_code.sql` 按 `Ctrl+Enter` 执行（或 `mysql -uroot -p < sql/09_alter_image_share_code.sql`）。

**验证（用户执行后）**：任一方式确认两列/键存在，例如在 IDEA Database 面板刷新 image 表看到 `shareCode` 列；或跑 Task 1.3 的测试，不再报 `Unknown column`。

---

### Phase 1 — 后端：数据模型 + 生成/查看逻辑

#### Task 1.1 给实体加字段 `shareCode`

文件：`src/main/java/com/huang/yupicture/model/entity/Image.java`

在 `isDelete` 字段（`@TableLogic private Integer isDelete;`）**之后、`serialVersionUID` 之前**插入：

```java
    /**
     * 分享短链码（随机 Base62 8 位；未分享为 null）。
     *
     * <p><b>为什么是随机码：</b>它是"凭码即可查看"的持有凭证 —— 用自增 / 时间戳这类
     * 可预测的值，别人能枚举出未公开的图。用 {@link java.security.SecureRandom} 生成。
     *
     * <p>唯一性由数据库唯一索引 {@code uk_shareCode} 兜底（代码里的"先查再插"防不住并发）。
     */
    private String shareCode;
```

验证：`$MVN -q -o compile` 后 `ls -l --time-style=+%H:%M target/classes/com/huang/yupicture/model/entity/Image.class` → 时间戳是刚才。**注意**：`bin/mvn` 会静默不编译，必须走 classworlds 命令并核对 class 时间戳。

#### Task 1.2 加常量

文件：`src/main/java/com/huang/yupicture/constant/ImageConstant.java`

在 `AUTO_REVIEW_MESSAGE` 常量后插入：

```java
    /** 分享短链码长度（随机 Base62 字符数）：62^8 ≈ 2.2e14，碰撞概率可忽略 */
    public static final int SHARE_CODE_LENGTH = 8;
```

#### Task 1.3 先写失败测试（TDD：红）

文件：`src/test/java/com/huang/yupicture/controller/ImageControllerTest.java`

在类末尾（最后一个 `}` 之前）追加以下 helper + 测试。**先只加 helper 与 3 个测试**，`shareImage`/`getSharedImage` 尚未存在 → 编译失败即为"红"。

```java
    // ==================== 七、图片分享短链 ====================

    /** 给图片设置分享码（直接改库，绕过生成接口 —— 查看用例不依赖生成逻辑） */
    private void setShareCode(long imageId, String shareCode) {
        Image patch = new Image();
        patch.setId(imageId);
        patch.setShareCode(shareCode);
        imageService.updateById(patch);
    }

    @Test
    void shareImageShouldRequireOwner() throws Exception {
        long ownerId = prepareUser("img_s_owner1", "user");
        long imageId = prepareImage(ownerId, "要分享的图", "风景");

        // 未登录 → 被全局拦截器拦下（40100）
        mockMvc.perform(post("/image/share").param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(40100));

        // 别人 → 40101（归属校验），且不能真的写入分享码
        prepareUser("img_s_other1", "user");
        Cookie otherCookie = loginAndGetCookie("img_s_other1");
        mockMvc.perform(post("/image/share").cookie(otherCookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(40101));
        assertNull(imageService.getById(imageId).getShareCode(), "越权分享不能真的写入分享码");
    }

    @Test
    void shareImageShouldGenerateAndBeIdempotent() throws Exception {
        long ownerId = prepareUser("img_s_owner2", "user");
        Cookie cookie = loginAndGetCookie("img_s_owner2");
        long imageId = prepareImage(ownerId, "要分享的图", "风景");

        MvcResult first = mockMvc.perform(post("/image/share").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String code1 = objectMapper.readTree(first.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").asText();
        assertFalse(code1.isBlank(), "要返回一个非空分享码");
        assertEquals(code1, imageService.getById(imageId).getShareCode(), "分享码要落库");

        // 再点一次分享：复用同一个码（幂等 —— 重复分享不该产生一串不同链接）
        MvcResult second = mockMvc.perform(post("/image/share").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String code2 = objectMapper.readTree(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").asText();
        assertEquals(code1, code2, "重复分享必须返回同一个码（幂等）");
    }

    @Test
    void shareViewShouldBeAnonymousForPassedImage() throws Exception {
        long ownerId = prepareUser("img_s_owner3", "user");
        long imageId = preparePassedImage(ownerId, "可分享的图", "风景");
        setShareCode(imageId, "sharePassed123");

        // 不带 cookie（游客）→ 免登录可看已通过的图
        mockMvc.perform(post("/image/share/view").param("shareCode", "sharePassed123"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("可分享的图"));
    }
```

验证：`$MVN "-Dtest=ImageControllerTest" test 2>&1 | grep -a -E "ERROR|cannot find symbol|BUILD"` → 期望出现 `cannot find symbol`（`shareImage`/`getSharedImage` 未定义）或编译失败。**这就是红。**

#### Task 1.4 实现 `shareImage` + `getSharedImage`（TDD：绿）

**1.4a** 文件 `src/main/java/com/huang/yupicture/service/ImageService.java`，在 `deleteImage` 声明之后插入接口方法：

```java
    /**
     * 生成（或复用）图片的分享短链码 —— 本人或管理员。
     *
     * <p><b>幂等</b>：已分享过就直接返回原码，重复点「分享」不会产生一串不同链接。
     *
     * @param id 图片 id（字符串形式的雪花 id）
     * @return 分享短链码（随机 Base62；前端用 {@code origin + "/share/" + code} 拼完整链接）
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40400）／无权分享他人的图（40101）
     */
    String shareImage(String id);

    /**
     * 按分享码查看图片（<b>免登录</b>，已加入 SaTokenConfig 白名单）。
     *
     * <p>可见性与 {@code getImageById} 完全一致：已通过任何人可看；未通过只有本人/管理员可看，
     * 其他人（含游客）报 40400「图片不存在」—— 连存在性都不暴露。
     * 已逻辑删除的图查询不到 → 短链自然"失效"。
     *
     * @param shareCode 分享码
     * @return 脱敏后的图片信息
     * @throws com.huang.yupicture.common.BusinessException 分享码为空（40000）／图不存在或已失效（40400）
     */
    ImageVO getSharedImage(String shareCode);
```

**1.4b** 文件 `src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java`：

顶部 import 区加：
```java
import java.security.SecureRandom;
```

类字段区（`private OutboundHttpClient outboundHttpClient;` 之后）加：

```java
    /** 分享码字符表（Base62：字母大小写 + 数字）与随机源 */
    private static final char[] SHARE_CODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * 分享码的随机源用 SecureRandom 而不是 Random：
     * 分享码是"持有即可查看"的凭证，可预测的随机数等于码可被枚举。
     */
    private static final SecureRandom SHARE_RANDOM = new SecureRandom();
```

在 `deleteImage` 方法之后插入：

```java
    @Override
    public String shareImage(String id) {
        User loginUser = userService.getLoginUser();
        long imageId = ParamUtils.parseId(id);

        Image image = this.getById(imageId);
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        checkImageOwner(image, loginUser);

        // 幂等：已经分享过就复用原码（重复点「分享」不该产生一串不同链接）
        if (!ParamUtils.isBlank(image.getShareCode())) {
            return image.getShareCode();
        }

        // 生成唯一分享码。撞码概率极低，但唯一索引会挡住并发下的极端情况 →
        // 抓到 DuplicateKeyException 就换一个码重试，最多 SHARE_CODE_MAX_RETRY 次。
        for (int i = 0; i < SHARE_CODE_MAX_RETRY; i++) {
            String code = generateShareCode();
            Image update = new Image();
            update.setId(imageId);
            update.setShareCode(code);
            try {
                if (this.updateById(update)) {
                    log.info("生成分享短链：imageId={}, operatorId={}", imageId, loginUser.getId());
                    return code;
                }
            } catch (DuplicateKeyException e) {
                // 撞 uk_shareCode：换一个码重试（8 位 Base62 空间极大，实际几乎不会发生）
                log.warn("分享码冲突，重试：code={}", code);
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成分享链接失败，请重试");
    }

    /** 分享码重试上限（防极端情况下死循环） */
    private static final int SHARE_CODE_MAX_RETRY = 5;

    /** 生成一个随机分享码（Base62 × SHARE_CODE_LENGTH 位） */
    private String generateShareCode() {
        StringBuilder sb = new StringBuilder(ImageConstant.SHARE_CODE_LENGTH);
        for (int i = 0; i < ImageConstant.SHARE_CODE_LENGTH; i++) {
            sb.append(SHARE_CODE_ALPHABET[SHARE_RANDOM.nextInt(SHARE_CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    @Override
    public ImageVO getSharedImage(String shareCode) {
        if (ParamUtils.isBlank(shareCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分享码不能为空");
        }
        // 按码查图：uk_shareCode 唯一命中，走索引。逻辑删除的图 MP 会自动补 isDelete=0 → 查不到 → 短链失效
        Image image = this.getOne(new LambdaQueryWrapper<Image>().eq(Image::getShareCode, shareCode));
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "分享的图片不存在或已失效");
        }

        // 可见性与 checkImageVisible 同一套语义，区别只是这里登录态可为空（游客）
        User loginUser = userService.getLoginUserOrNull();
        if (loginUser != null && (isAdmin(loginUser) || loginUser.getId().equals(image.getUserId()))) {
            // 本人 / 管理员：任何状态都能看
            return toImageVO(image);
        }
        // 其他人（含游客）：只能看已通过；未通过报「不存在」（连存在性都不暴露）
        boolean passed = Integer.valueOf(ImageConstant.REVIEW_STATUS_PASS).equals(image.getReviewStatus());
        if (!passed) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        return toImageVO(image);
    }
```

顶部 import 区还需补：
```java
import org.springframework.dao.DuplicateKeyException;
```

**1.4c** 新增空安全的登录查询。文件 `src/main/java/com/huang/yupicture/service/UserService.java`，在 `getLoginUser()` 声明之后插入：

```java
    /**
     * 静默取当前登录用户：<b>未登录返回 null，不抛异常</b>。
     *
     * <p>给「登录可选」的接口用（如分享查看：游客能看已通过的图，登录态存在时本人才看得到自己的全部）。
     * 与 {@link #getLoginUser()} 的差别只有「未登录时抛不抛」——需要强制登录的接口一律用后者。
     *
     * @return 用户实体；未登录或登录态指向的用户已被删 → null
     */
    User getLoginUserOrNull();
```

文件 `src/main/java/com/huang/yupicture/service/impl/UserServiceImpl.java`，把现有 `getLoginUser()` 改为复用新方法（行为不变：未登录仍抛 40100）：

```java
    @Override
    public User getLoginUser() {
        User user = getLoginUserOrNull();
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public User getLoginUserOrNull() {
        // isLogin() 内部从 cookie/header 解析 token；没有或已过期都是 false
        if (!StpUtil.isLogin()) {
            return null;
        }
        long userId = StpUtil.getLoginIdAsLong();
        // 极端情况：登录态还在、但用户已被删（逻辑删除也算）→ 按"未登录"处理
        return this.getById(userId);
    }
```

验证（红转绿）：`$MVN "-Dtest=ImageControllerTest" test`（不加 -q）。读结果：
```bash
grep -a -H "Tests run:" target/surefire-reports/*ImageControllerTest*.txt
```
期望 `Failures: 0, Errors: 0`，且 3 个新用例在列。**若整类报 `Unknown column 'shareCode'` → 回 Task 0.3 让用户先执行 DDL。**

#### Task 1.5 暴露两个 Controller 接口

文件：`src/main/java/com/huang/yupicture/controller/ImageController.java`

在 `deleteImage` 方法之后插入：

```java
    /**
     * 生成分享短链（本人或管理员）。
     *
     * <p>只返回分享码；完整短链由前端拼（{@code origin + "/share/" + code}）——
     * 链接格式天然属于前端的路由知识，放后端反而要多一份 base-url 配置。
     */
    @PostMapping("/share")
    @Operation(summary = "生成分享短链（本人或管理员）")
    public BaseResponse<String> shareImage(@RequestParam String id) {
        return ResultUtils.success(imageService.shareImage(id));
    }

    /**
     * 按分享码查看图片（<b>免登录</b> —— 已加入 SaTokenConfig 白名单）。
     *
     * <p>这是本模块第二个"仅登录可选"的入口：游客凭码看已通过的图；带登录态时本人/管理员
     * 还能看自己未通过的图。
     */
    @PostMapping("/share/view")
    @Operation(summary = "按分享码查看图片（免登录）")
    public BaseResponse<ImageVO> getSharedImage(@RequestParam String shareCode) {
        return ResultUtils.success(imageService.getSharedImage(shareCode));
    }
```

验证：`$MVN -q -o compile` → 无错误（错误行含中文，用 `2>&1 | grep -a ERROR`）。

#### Task 1.6 白名单放行查看接口

文件：`src/main/java/com/huang/yupicture/config/SaTokenConfig.java`

在 `WHITE_LIST` 数组里 `/error` 那条**之前**插入（**注意不带 `/api` 前缀**）：

```java
            // 分享查看：短链免登录（游客凭码看已通过的图）。
            // 只放行这一个"查看"接口；生成分享码（/image/share）仍要求登录 + 归属校验
            // —— 别图省事写成 "/image/share/**"，那会把生成接口也敞开。
            "/image/share/view",
```

> 之所以安全：Ant 的 `"/image/share/view"` 是精确路径，不会匹配 `/image/share`；反之 `"/image/share"` 也不匹配 `/image/share/view`。

#### Task 1.7 补全分享相关测试（查看的边界分支）

在 `ImageControllerTest` 的第七节继续追加：

```java
    @Test
    void shareViewShouldHideOthersPendingImage() throws Exception {
        long ownerId = prepareUser("img_s_owner4", "user");
        long imageId = prepareImage(ownerId, "别人的待审核图", "风景");   // PENDING
        setShareCode(imageId, "sharePending123");

        // 游客打开未通过审核的短链 → 40400（与可见性一致）
        mockMvc.perform(post("/image/share/view").param("shareCode", "sharePending123"))
                .andExpect(jsonPath("$.code").value(40400));

        // 本人带登录态打开自己的 → 可见
        Cookie cookie = loginAndGetCookie("img_s_owner4");
        mockMvc.perform(post("/image/share/view").cookie(cookie).param("shareCode", "sharePending123"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("别人的待审核图"));
    }

    @Test
    void shareViewShouldReturn404ForUnknownOrDeletedAnd400ForBlank() throws Exception {
        // 不存在的码 → 40400
        mockMvc.perform(post("/image/share/view").param("shareCode", "nope00000"))
                .andExpect(jsonPath("$.code").value(40400));

        // 空码 → 40000（参数错误，与"数据不存在"分开）
        mockMvc.perform(post("/image/share/view").param("shareCode", ""))
                .andExpect(jsonPath("$.code").value(40000));

        // 已删除的图：短链应"失效"（逻辑删除后按码查不到）
        long ownerId = prepareUser("img_s_owner5", "user");
        long imageId = preparePassedImage(ownerId, "将被删的图", "风景");
        setShareCode(imageId, "shareDeleted123");
        Cookie cookie = loginAndGetCookie("img_s_owner5");
        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/image/share/view").param("shareCode", "shareDeleted123"))
                .andExpect(jsonPath("$.code").value(40400));
    }
```

验证：`$MVN "-Dtest=ImageControllerTest" test` → `target/surefire-reports/*ImageControllerTest*.txt` 里 `Failures: 0, Errors: 0`。

#### Task 1.8 全量回归

```bash
cd /e/project/yu-picture-cloud
$MVN test 2>&1 | grep -a -E "Tests run:|BUILD|ERROR"
```
期望 `BUILD SUCCESS`，总用例数 = 改前 120 + 新增 5 = **125**（另 +1 skipped 的默认关闭维护任务）。
核对口径：先 `find src/test -name "*.java" | wc -l` 与 surefire 报告时间戳排除残留。

#### Task 1.9 提交后端（功能/测试分开）

```bash
cd /e/project/yu-picture-cloud
git add sql/09_alter_image_share_code.sql sql/05_create_table_image.sql \
  src/main/java/com/huang/yupicture/model/entity/Image.java \
  src/main/java/com/huang/yupicture/constant/ImageConstant.java \
  src/main/java/com/huang/yupicture/service/ImageService.java \
  src/main/java/com/huang/yupicture/service/impl/ImageServiceImpl.java \
  src/main/java/com/huang/yupicture/service/UserService.java \
  src/main/java/com/huang/yupicture/service/impl/UserServiceImpl.java \
  src/main/java/com/huang/yupicture/controller/ImageController.java \
  src/main/java/com/huang/yupicture/config/SaTokenConfig.java
git commit -m "feat(image): 图片分享短链（生成分享码 + 免登录查看）"

git add src/test/java/com/huang/yupicture/controller/ImageControllerTest.java
git commit -m "test(image): 图片分享短链用例（生成幂等 / 越权 / 可见性 / 失效）"
```

---

### Phase 2 — 前端：分享 API + 公开分享页 + 分享按钮

> 前端是独立仓 `E:\project\yu-picture-cloud-frontend`。类型走 `src/api/generated/schema.d.ts`（`npm run gen:api` 从后端生成）；本功能**没有新 VO**（返回 String 和既有 ImageVO），无需重跑 gen:api —— 但若后端在跑，顺手跑一次也无害。

#### Task 2.1 API 封装

文件：`src/api/image.ts`，在末尾追加：

```ts
/**
 * 生成分享短链（本人或管理员）。返回分享码。
 *
 * ⚠️ 是 POST + URL 参数（同 getImage / deleteImage）：@RequestParam 的参数塞进 body
 * 会因缺参被后端包成 50000「系统内部异常」，看着像后端 bug。
 *
 * 完整短链由前端拼：`${window.location.origin}/share/${code}` —— 链接格式属于前端路由知识。
 */
export function shareImage(id: string): Promise<string> {
  return post<string>(`/image/share?id=${encodeURIComponent(id)}`)
}

/**
 * 按分享码查看图片（**免登录** —— 后端已把 /image/share/view 加进白名单）。
 * 已通过的图游客可看；未通过/不存在/已删除 → 后端 40400，拦截器会弹提示，页面走空态。
 */
export function getSharedImage(shareCode: string): Promise<ImageVO> {
  return post<ImageVO>(`/image/share/view?shareCode=${encodeURIComponent(shareCode)}`)
}
```

验证：`npx vue-tsc --noEmit -p tsconfig.app.json`（或 Task 2.5 的 `npm run type-check`）→ 无报错。

#### Task 2.2 公开分享页 `/share/:code`

新建文件 `src/views/image/ImageShareView.vue`：

```vue
<template>
  <div class="share-page">
    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !record" description="分享的图片不存在或已失效" />
      <div v-else-if="record" class="share-card">
        <div class="share-stage">
          <a-image
            :src="displaySrc"
            :fallback="record.url"
            :preview="{ src: record.webpUrl ?? record.url }"
            @error="onImgError"
          />
        </div>
        <div class="share-info">
          <h1 class="share-name">{{ record.name || '未命名图片' }}</h1>
          <p class="share-intro">{{ record.introduction || '暂无简介' }}</p>
          <div class="share-meta">
            <span>作者：用户 {{ String(record.userId ?? '').slice(-6) }}</span>
            <span>上传时间：{{ formatDateTime(record.createTime) }}</span>
          </div>
          <a-button type="primary" block @click="goHome">进入图库</a-button>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getSharedImage, type ImageVO } from '@/api/image'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()

const shareCode = String(route.params.code ?? '')
const loading = ref(false)
const record = ref<ImageVO | null>(null)

/** 优先 WebP 副本（更小），没有就原图 —— 与详情页同一策略 */
const displaySrc = computed(() => record.value?.webpUrl ?? record.value?.url ?? '')

/** WebP 副本 404（存量旧图）→ 回退原图 */
function onImgError(event: Event) {
  const img = event.target as HTMLImageElement
  const url = record.value?.url
  if (url && record.value?.webpUrl && img.src !== url) {
    img.src = url
  }
}

function goHome() {
  router.push('/')
}

onMounted(async () => {
  if (!shareCode) return
  loading.value = true
  try {
    record.value = await getSharedImage(shareCode)
  } catch {
    // 拦截器已弹提示；页面走空态
    record.value = null
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 40px 16px;
  background: #f5f6f8;
}

.share-card {
  width: 100%;
  max-width: 720px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgb(0 0 0 / 3%), 0 4px 16px rgb(0 0 0 / 6%);
  overflow: hidden;
}

.share-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 260px;
  padding: 24px;
  background: #fafafa;
}

.share-stage :deep(img) {
  max-width: 100%;
  max-height: 62vh;
  border-radius: 8px;
  object-fit: contain;
}

.share-info {
  padding: 20px 24px 24px;
}

.share-name {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
  color: rgb(0 0 0 / 88%);
  word-break: break-all;
}

.share-intro {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  color: rgb(0 0 0 / 55%);
  white-space: pre-wrap;
}

.share-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 20px;
  font-size: 13px;
  color: rgb(0 0 0 / 45%);
}
</style>
```

验证：`npm run type-check` → 无报错。

#### Task 2.3 注册路由（关键：登录用户也能看分享页）

文件：`src/router/index.ts`

① 顶部 import 区加：
```ts
import ImageShareView from '@/views/image/ImageShareView.vue'
```

② 在 `routes` 数组里、`BasicLayout` 那条**之外**（顶层，与 `/user/login`、`/user/register` 同级）新增一条：
```ts
    {
      // 分享页：免登录 + 独立布局（游客没有侧边栏/用户区）。
      // 不在 BasicLayout 下，所以它不会被"未登录踢登录页"影响 —— 见下方守卫改造。
      path: '/share/:code',
      name: 'image-share',
      component: ImageShareView,
    },
```

③ 把公开路由常量拆成两个（**这是本任务的重点**：原来"已登录用户访问公开页会被踢回 `/`"，会让登录用户打不开分享链接）：

把：
```ts
/** 无需登录即可访问的页面 */
const PUBLIC_ROUTES = ['user-login', 'user-register']
```
改为：
```ts
/** 无需登录即可访问的页面（游客可进）。分享页在此列：游客和已登录用户都要能看 */
const PUBLIC_ROUTES = ['user-login', 'user-register', 'image-share']

/** 已登录用户不该再停留的页面（只有登录/注册页）—— 分享页不在此列 */
const REDIRECT_WHEN_LOGGED_IN = ['user-login', 'user-register']
```

④ 改守卫里用这两个常量：
把：
```ts
  const isPublic = PUBLIC_ROUTES.includes(String(to.name))

  if (!isPublic && !userStore.loginUser) {
    return { name: 'user-login' }
  }
  if (isPublic && userStore.loginUser) {
    // 已登录就别再看登录/注册页
    return { path: '/' }
  }
```
改为：
```ts
  const isPublic = PUBLIC_ROUTES.includes(String(to.name))

  if (!isPublic && !userStore.loginUser) {
    return { name: 'user-login' }
  }
  // 只有登录/注册页在"已登录"时踢回首页；分享页登录后照样能看（否则本人点自己的分享链接会被弹走）
  if (REDIRECT_WHEN_LOGGED_IN.includes(String(to.name)) && userStore.loginUser) {
    return { path: '/' }
  }
```

#### Task 2.4 详情页加「分享」按钮

文件：`src/views/image/ImageDetailView.vue`

① `@ant-design/icons-vue` 的 import 里加 `ShareAltOutlined`：
```ts
import {
  ArrowLeftOutlined,
  AuditOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ShareAltOutlined,
} from '@ant-design/icons-vue'
```

② `@/api/image` 的 import 里加 `shareImage`：
```ts
import {
  deleteImage,
  downloadImage,
  getImage,
  listImageOptions,
  shareImage,
  updateImage,
  type ImageUpdateRequest,
  type ImageVO,
} from '@/api/image'
```

③ 模板 `action-col` 里，在「下载原图」按钮之后插入（**只在 `canOperate` 时显示** —— 生成分享码是本人/管理员动作，与编辑/删除同一道闸）：
```html
            <a-button v-if="canOperate" block :loading="sharing" @click="handleShare">
              <ShareAltOutlined />
              分享
            </a-button>
```

④ `script setup` 里，「下载」段的 handleDownload 之后加：
```ts
// ---- 分享 ----
const sharing = ref(false)

/**
 * 生成（或复用）分享码，把完整短链复制到剪贴板。
 * 后端幂等：已分享过返回原码，所以重复点同一条链接，不会产生多份。
 */
async function handleShare() {
  if (!record.value?.id || sharing.value) return
  sharing.value = true
  try {
    const code = await shareImage(record.value.id)
    const link = `${window.location.origin}/share/${code}`
    try {
      await navigator.clipboard.writeText(link)
      message.success('分享链接已复制：' + link)
    } catch {
      // 剪贴板不可用（非安全上下文 / 权限被拒）→ 退化成把链接展示出来让用户手抄
      message.info('分享链接：' + link)
    }
  } catch {
    // 拦截器已提示
  } finally {
    sharing.value = false
  }
}
```

#### Task 2.5 前端类型检查 + 构建

```bash
cd /e/project/yu-picture-cloud-frontend
npm run type-check
```
期望：无输出 / 退出码 0（`vue-tsc --build` 静默即通过）。

```bash
npm run build-only
```
期望：`✓ built in ...s`，无错误。

#### Task 2.6 真机验证（前后端都由用户在自己 IDEA / 终端起）

1. 用户启动后端（8123）与前端（5173）。
2. 打开 `http://localhost:5173/`，登录，进任一图片详情页，点「分享」→ 应弹「分享链接已复制」。
3. **开无痕窗口**（不带登录态）粘贴该链接 `http://localhost:5173/share/<code>` → 应看到图片预览 + 名称/简介。
   - 若分享的是**未通过审核**的图：无痕打开应显示「分享的图片不存在或已失效」（可见性规则在正确工作，不是 bug）。
4. 收尾：`git status` 确认没有临时文件残留。

> 用 `desktop_preview(action='open', url='http://localhost:5173/share/<code>')` + 等 6~8s + `read` 也能只读验证渲染文本（`browser_exec` 会拒 localhost）。

#### Task 2.7 提交前端

```bash
cd /e/project/yu-picture-cloud-frontend
git add src/api/image.ts src/router/index.ts src/views/image/ImageShareView.vue src/views/image/ImageDetailView.vue
git commit -m "feat(image): 图片分享页与分享按钮（短链 /share/:code）"
```

---

### Phase 3 — 文档同步（代码/文档三处一致）

#### Task 3.1 更新 `docs/requirements.md`

- 3.2 表格里把 `| 图片分享（公开链接） | 后续 | 📋 | 生成可直接访问的短链 |` 改为：
  `| 图片分享（公开链接） | 后续 | ✅ | 生成随机短链码 `shareCode`（Base62×8，唯一索引兜底），前端 `/share/:code` 免登录查看；可见性与详情一致（未通过仅本人/管理员可看） |`
- 5.2 数据模型不必大改（image 已在"规划中表"里，实际早已建；可选：把 image 从 5.2 挪到 5.x 已建表 —— 属既有债务，可不动）。
- 附：变更记录追加一行（照既有格式）：
  `| 2026-10-12 | **图片分享短链**：image 加 shareCode（sql/09 + 05 同步）+ uk_shareCode；`POST /image/share`（本人/管理员，幂等生成）+ `POST /image/share/view`（免登录，白名单）；可见性沿用 checkImageVisible（未通过仅本人/管理员）；前端公开页 `/share/:code` + 详情页分享按钮。测试 120 → 125 全绿 |`

#### Task 3.2 记学习日志

文件：`C:\Users\Administrator.DESKTOP-5S865Q9\Desktop\学习文档\学习日志.md`
追加一条（日期 + 功能 + 关键机制 + 踩到的点），风格沿用该文件既有条目。

---

## 5. Tests / validation（汇总）

| 层 | 命令 | 期望 |
|---|---|---|
| 后端编译 | `$MVN -q -o compile` | 无错误（grep -a ERROR 为空） |
| 后端分享用例 | `$MVN "-Dtest=ImageControllerTest" test` | `Tests run: N, Failures: 0, Errors: 0`（N = 原 24 + 5） |
| 后端全量 | `$MVN test` | `BUILD SUCCESS`，共 **125** 用例（+1 skipped） |
| 前端类型 | `npm run type-check` | 退出码 0 |
| 前端构建 | `npm run build-only` | `✓ built ...` |
| 端到端 | 见 Task 2.6 | 无痕窗口凭短链看到已通过图；未通过图在无痕下 404 文案 |

**TDD 节奏**：Task 1.3 先写用例 → 编译失败（红）→ Task 1.4 实现 → 转绿 → Task 1.7 补边界用例 → Task 1.8 全量绿 → 提交。每完成一个 Phase 独立 commit。

---

## 6. Risks / tradeoffs / open questions

### 已定的取舍

- **只放行一个查看接口**（`/image/share/view`），生成接口仍受登录 + 归属保护。这是"免登录可看"与"游客不能碰任何数据接口"这条既有铁律之间**最小的口子** —— 铁律的本意是防越权，而分享查看本就该公开。
- **短链不做重定向**：`/share/:code` 是前端路由，游客浏览器仍会加载整个 SPA 再去调接口。比"后端 302 到 COS URL"多一次请求，但换来一个带元信息的分享页（且 COS URL 是长期公开地址，302 等于把原图直链泄出去）。代价：生产环境需要 SPA history fallback（见下）。
- **分享码不设过期**：本功能只解决"生成 + 查看"。过期/撤销分享是独立需求（要再加列或表），YAGNI，留作 open question。
- **不把 shareCode 放进 ImageVO**：列表/详情默认不暴露分享码；详情页点「分享」时按需生成/复用即可。

### 风险

1. **SPA history 路由的生产部署**：`/share/:code` 是 `createWebHistory` 路由，直接访问/刷新需要服务器把未知路径回退到 `index.html`。Vite dev（5173）默认支持；**生产若用 `vite preview` 或 nginx 需自配 `try_files $uri /index.html`**，否则分享链接刷新会 404。本项目目前只本地跑，记为部署期注意事项。
2. **DDL 未落库**：Task 0.3 是硬前置。entity 加字段后所有 image 相关测试会集体 `Unknown column 'shareCode'` —— 判定法 `grep -a -A3 BadSqlGrammar target/surefire-reports/*.txt`，此时正确动作是催用户执行 DDL，别回头改代码。
3. **`navigator.clipboard` 需要安全上下文**：`localhost` 与 https 视为安全，`http://<局域网 IP>` 下会被拒 → 已用 `try/catch` 退化成把链接显示出来。
4. **分享码唯一索引 vs 逻辑删除**：被删的图保留其码继续占用唯一键。因码为随机、从不复用，不会与任何新码相撞；但若将来"删除后回收码"会撞索引（当前无此需求）。
5. **`getLoginUserOrNull` 的抽取时机**：目前只有一个调用点（`getSharedImage`）。之所以现在抽进 `UserService`：它是"读登录态"这件基础设施的既有归属地，与 `getLoginUser()` 并列成对，且分享类公开入口后续会再用 —— 若执行者认为违反"第二个调用点才抽"的纪律，也可先在 `ImageServiceImpl` 里 `private` 化（用 `StpUtil` + `userService.getById`），后续复用时再上提。

### Open questions（不阻塞本计划，落地前可再拍）

- 分享要不要**过期时间 / 手动取消分享**？（当前永久有效）
- 已登录用户看别人分享的**未通过**图，是否也该像游客一样 404？（当前：是，只有本人/管理员例外 —— 与 `checkImageVisible` 一致）
- 后端是否要提供 `POST /image/share/cancel` 撤销分享码（置回 NULL 释放链接）？
