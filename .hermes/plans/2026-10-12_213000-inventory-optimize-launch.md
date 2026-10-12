# 云图库：模块盘点 + 优化/新增建议 + 「从零重写」建议与路径

> 日期：2026-10-12　状态：计划（待用户确认后执行）
> 用户原话拆解：① 盘点目前做了哪些模块/功能；② 我有什么优化/新增建议；③ 如果从零开始有什么建议（**本轮不实施从零重写**，先上线，之后自己再写一遍）。
> 本计划的「实施部分」只做三件事：**A. 上线收尾**、**B. 一批低风险优化**、**C. 给未来重写版的蓝图文档**。从零重写本身不在本计划内。

---

## Goal

产出一份「项目现状盘点 + 优化清单 + 从零重写蓝图」的交付：先把项目收尾到可上线状态，落实几个低风险优化，并把重写建议沉淀成文档供日后照着写。

## Current context / assumptions

已核实（读 `docs/requirements.md` 变更记录 + git log）：

- **路径**：后端 `E:\project\yu-picture-cloud`（Spring Boot 3.5.16 + JDK21 + MyBatis-Plus + Sa-Token + MySQL + Redis + 腾讯云 COS），前端 `E:\project\yu-picture-cloud-frontend`（Vue3 + Vite8 + TS + Ant Design Vue 4）。端口 8123 / 5173。
- **测试**：104 个用例全绿（离线跑：git-bash 下 bin/mvn 失效，须用 `java -cp plexus-classworlds` 方式，见下文命令）。
- **已完成模块**：
  | 里程碑 | 内容 |
  |---|---|
  | M1/M1.5 | 骨架、统一响应、健康检查、DDL、分页插件、CORS、前端脚手架 |
  | M2 用户模块 | 注册/登录/登出/当前用户、全局登录拦截、管理员用户管理（含前端页） |
  | M3 图片模块 | 上传（白名单+魔数+5MB+COS）、详情、分页列表、编辑、删除（先清 COS 后删记录）、名称搜索、归属校验 |
  | M4 图片处理 | JPEG 按需压缩、WebP 副本（原图保留、URL 推导派生文件）、200×200 缩略图 |
  | M4.5 审核+浏览 | 先过后审、管理员自动过审、编辑重置留史、`resetReviewParams` 开关、内容可见性（浏览=已通过/我的=全部）、`/image/options`、主页图墙、审核工作台、就地审核弹窗 |
  | 追加 | URL 传图（SSRF 四道防线+魔数反推格式）、批量抓图（Openverse，合规选源）、图片下载、标签 JSON 规范化、时间显示/裂图两 bug 修复 |
- **未做（requirements 里 📋）**：按颜色搜索、图片分享短链、以图搜图、M5 空间模块、M6 智能化（AI 审核）。
- **挂着的真问题**：① **COS 密钥泄漏待轮换**（历史会话打印了配置内容；git 历史里还有旧密钥，push 曾被 GH013 拦）；② 约 10 张测试抓取留下的 COS 孤儿图。
- **假设**：用户偏好手写校验、不轻易引框架（Bean Validation/权限注解均拒绝）；额度敏感，方案宁小勿大。

## Architecture / proposed approach

三段式：**先上线**（轮换密钥 + 清孤儿图 + 上线自检清单），**再优化**（每个优化独立成卡，TDD，低风险优先），**最后写蓝图**（`docs/rewrite-blueprint.md`，把「从零重写会怎么做」沉淀成可执行的架构决策记录，供日后重写时直接照抄）。不新增依赖，不重构现有工作代码——优化只做「有测试兜底、收益明确」的。

---

## Part 0：模块盘点（交付物，写给用户看，非代码任务）

写入 `docs/module-inventory.md`（或直接作为本计划回复），内容按下表，每格注明代表文件/提交：

| 模块 | 功能 | 关键代码 |
|---|---|---|
| 用户 | 注册/登录/登出/当前用户/管理员增删改查 | `UserController.java`、`UserServiceImpl.java` |
| 图片 CRUD | 上传/详情/列表/编辑/删除/搜索/下载 | `ImageController.java`、`ImageServiceImpl.java` |
| 图片处理 | 压缩/WebP/缩略图（原图保留、URL 推导派生文件） | `ImageUtils.java`、`CosManager.java`（`hasDerivatives`/`buildThumbnailKey`） |
| 审核 | 先过后审、自动过审、编辑重置、可见性、工作台 | `ImageServiceImpl.reviewImage`/`resetReviewParams`、前端 `ImageReviewModal` |
| 传图扩展 | URL 传图（SSRF 防线）、批量抓图（Openverse） | `UrlPictureUtils.java`、`ImageCrawlServiceImpl.java`、`OutboundHttpClient.java` |
| 前端 | 主页图墙/创建（3 种方式）/图片管理/审核工作台/用户管理 | `views/image/*`、`views/user/*` |

## Part A：上线收尾（按顺序，A1 不做 A2 不许开始）

### A1. 轮换腾讯云 COS 密钥（用户手动，最长一步）

- [ ] 用户登录腾讯云控制台 → CAM → 子账号（COS 专用那个）→ 禁用旧密钥 → 新建密钥
- [ ] 用户自己把新密钥填进本地 `application-local.yml`（或 `.env`，**密钥值永远不进对话**——由用户手填，我只确认键存在）
- 验证（我只跑这一条，不读值）：
  ```bash
  cd /e/project/yu-picture-cloud && grep -c "cos" src/main/resources/application-local.yml
  ```
  预期输出：非 0。然后前端传一张图，后端日志出现 `上传成功` 且详情页可访问。
- ⚠️ git 历史里的旧密钥是死数据（密钥已作废即无害），**不重写历史**（过滤历史风险远大于收益，记录决策即可）。

### A2. 清理 COS 孤儿图（写一个一次性脚本）

- 文件：`scripts/cleanup_orphans.py`（Python，列 COS 全量 → 与 MySQL `image` 表 url 求差 → 打印待删清单 → 加 `--apply` 才真删）。
- 预期：打印出约 10 张 `抓取测试` 相关对象；先 dry-run 给用户确认，再 `--apply`。
- 验证：脚本输出 `deleted=N, remaining orphans=0`。

### A3. 上线自检清单 + 文档

- 在 `docs/requirements.md` 里程碑表追加一行 `M7 上线 ✅`，内容含：
  - [ ] 后端打 jar（`mvn -DskipTests package`，git-bash 用 classworlds 方式）并在 IDEA 外跑通
  - [ ] 前端 `npm run build` 成功，`dist/` 可本地静态预览
  - [ ] 104 用例全绿复跑一次留档
  - [ ] README 补「如何跑起来」三段：数据库初始化（`sql/`）、后端启动、前端启动

## Part B：优化清单（每项独立提交，按性价比排序；标注「做/不做」由用户勾选）

### B1.【做】批量抓图幂等根治：加 `sourceUrl` 列 + 唯一防重（已知局限，变更记录里明确欠账）

- TDD：先在 `ImageCrawlServiceImplTest` 写失败用例——同一 sourceUrl 二次抓取（不同关键词）不重复入库，返回「已存在」状态。
- 实现：`sql/05_add_source_url.sql` 给 `image` 表加 `sourceUrl varchar(1024) NULL` + 普通索引（**唯一索引对 NULL 不生效**，改为插入前按 `sourceUrl` 精确查询 + 注释说明非严格唯一；真唯一需 MySQL 函数索引/生成列，YAGNI）；实体/Mapper 加字段；抓取写入时填 sourceUrl。
- 验证：`Tests run: 105`（新增 1），`git commit -m "feat(crawl): 抓取防重补 sourceUrl"`。

### B2.【做】列表 N+1 / 深分页体检（只测不改，除非发现真问题）

- 写一个临时探针（不提交）：`mvn test` 后看 slow log 或在 dev 环境造 1000 行假数据，`/image/page` 翻到 50 页计时。
- 预期：MP 分页 + 已有索引够用（个人项目量级）；若 COUNT 慢，`PageDTO` 加 `searchCount=false` 选项即可。
- 产出：结论写进变更记录，**大概率零代码改动**——这是「防止过度优化」的示范卡。

### B3.【做】前端 `formatTime`/`parseTags` 等工具函数单测（抄过三遍的教训）

- 前端补 vitest（若未配）：`utils/datetime.test.ts`、`utils/tags.test.ts` 各 4-6 个用例（含 UTC 时区偏移用例——今天那个 bug 的回归测试）。
- 验证：`npm run test` 全绿，`git commit -m "test(frontend): 时间/标签工具回归用例"`。

### B4.【不做，记录原因】以下按 YAGNI 明确砍掉
- Redis 缓存列表接口（个人量级无收益，等真卡再说）
- 图片分享短链、按颜色搜索、以图搜图 → 移入重写蓝图（见 Part C），不在现版本做
- 任何权限注解 / Bean Validation / 枚举替换 int 常量（用户红线）

## Part C：从零重写蓝图（`docs/rewrite-blueprint.md`，只写文档不写代码）

写给「未来的自己重写时直接照抄」，结构：

1. **写一遍学到的东西（为什么值得重写一遍）**——SSRF 防线、审核状态机、派生文件 URL 推导 vs 落库的取舍、DTO 即白名单、测试红先怀疑测试。这些是重写时要刻意保留的「来之不易的结论」。
2. **重写时改掉的 5 个决策**（每条：现状 → 问题 → 重写版做法）：
   - 派生文件 URL 推导 → 改为**落库三列**（`thumbnailUrl`/`webpUrl`/`sourceUrl`），消灭 `hasDerivatives` 特判和 404 兜底的复杂度
   - 雪花 id 存 string 传导到前端 → 后端统一 `@JsonSerialize`（现版本靠约定，重写版靠框架一层兜住）
   - 时间序列化：后端直接出 `yyyy-MM-dd HH:mm` 本地时间字段（用 Jackson 全局配置），前端不再解析 ISO——根除时区类 bug
   - Controller 返回 VO 前手写脱敏 → 收口到一个 `Vos.mask()` 静态工厂，防漏
   - 抓取/URL 传图的外部 HTTP → 从第一天上 `OutboundHttpClient`（现版本是踩坑后补的）
3. **技术选型保持不变的**（SB3/JDK21/MP/Sa-Token/COS/Vue3——重写练的是架构不是新框架）。
4. **重写建议节奏**：MVP 顺序照旧（用户→图片→处理→审核），每里程碑先写 requirements 再动手；预计净时长约为首版的 1/3（无探索成本）。

## Step-by-step task list（实施顺序）

| # | 任务 | 文件/命令 | 验证 | 预计 |
|---|---|---|---|---|
| 1 | 用户轮换 COS 密钥并自填配置 | 控制台操作（不进对话） | 上传图片成功 | 10min |
| 2 | 写孤儿图清理脚本（dry-run） | `scripts/cleanup_orphans.py` | 打印待删清单，用户确认 | 15min |
| 3 | `--apply` 真删 | 同上 | `orphans=0` | 2min |
| 4 | 上线自检：打包+构建+复跑测试 | 见 A3 命令 | jar 可跑 / dist 产出 / 104 绿 | 15min |
| 5 | B1 sourceUrl（先失败测试再实现） | `sql/05_*.sql`、`Image.java`、`ImageCrawlServiceImpl.java` | 105 用例全绿 | 30min |
| 6 | B2 深分页探针 | 临时脚本不提交 | 结论入变更记录 | 10min |
| 7 | B3 前端工具单测 | `src/utils/*.test.ts` | `npm run test` 绿 | 15min |
| 8 | 写 `docs/module-inventory.md` | 见 Part 0 | 用户过目 | 10min |
| 9 | 写 `docs/rewrite-blueprint.md` | 见 Part C | 用户过目 | 20min |

## Tests / validation

- 后端：每任务 TDD；跑测试命令（git-bash，Maven 用 classworlds 直启）：
  ```bash
  cd /e/project/yu-picture-cloud && "/c/Program Files/Java/jdk-21.0.10/bin/java.exe" -classpath "D:/apache-maven-3.9.16/boot/plexus-classworlds-2.11.0.jar" "-Dclassworlds.conf=D:/apache-maven-3.9.16/bin/m2.conf" "-Dmaven.home=D:/apache-maven-3.9.16" "-Dmaven.multiModuleProjectDirectory=E:/project/yu-picture-cloud" org.codehaus.plexus.classworlds.launcher.Launcher -o test 2>&1 | grep -E "Tests run:|BUILD"
  ```
  预期末行 `BUILD SUCCESS`，`Tests run: 104`（B1 后 105）。
- 前端：`cd /e/project/yu-picture-cloud-frontend && npm run test`（B3 后）与 `npm run build`（A3）。
- 每个 commit 前确认 `git status` 干净、只含本任务文件。

## Risks / tradeoffs / open questions

1. **密钥轮换必须最先做**——在完成前，任何「上线」宣称都不成立。风险：用户换密钥期间服务短暂不可上传（可接受，个人项目）。
2. **B1 的防重不是严格唯一**（NULL 不受唯一索引约束，并发下仍可能双插）——个人项目可接受，注释里写明；要严格唯一需生成列方案，YAGNI。
3. **孤儿图删除不可逆**——脚本必须 dry-run + 用户逐条确认后才 `--apply`。
4. **重写蓝图只是文档**——本计划刻意不写任何新业务代码；「自己再写一遍」是对用户学习价值最高的一步，留到上线之后。
5. **开放问题（执行前问一次）**：B2/B3 是否要做（默认做）；前端是否已配 vitest（没有则 B3 加一步配置，+10min）。

---

*计划生成于 2026-10-12 21:30，待用户确认后执行（可由子代理或本会话直接实施）。*
