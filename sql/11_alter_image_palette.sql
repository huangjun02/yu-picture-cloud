-- ============================================================
-- 云图库 yu-picture-cloud · 调色板字段（按颜色搜索三期：多色调色板）
-- 执行前提：07、08_alter_image_color_family.sql 已执行
--
-- 背景：二期用「单主色 → 色系」解决了「连续值 vs 离散色板」对不上的问题，
--       但单主色 = 面积最大的颜色 —— 白底 + 彩色主体（动漫壁纸 / 商品图 / 截图）
--       的图，主色永远是那片背景白，主体色被盖住：点「蓝色」搜不到白底蓝主体的图。
--
-- 本期改为「调色板」：存 top-3 主色（含占比与色系），筛选条件变成
--       「任意一条主色落在该色系」（JSON_CONTAINS）。
--       picColor（主色 = 调色板第一名，详情页展示用）保持不变。
--       picColorFamily 被调色板内的 family 取代，本脚本一并删除（不留死列）。
--
-- ⚠️ 幂等性：重复执行会报 Duplicate column / Can't DROP，执行前先看列在不在。
-- ⚠️ 执行后必须跑回填（见文末），否则筛选查的是空列、一张都搜不出来。
-- ============================================================

USE yu_picture_cloud;

-- ① 调色板：JSON 数组，元素形如 {"color":"#FDF9FB","ratio":76,"family":"white"}
--    展示用（详情页色条）+ 筛选用（JSON_CONTAINS 取 family）。
--    之所以存 JSON 而不是另建一张 image_palette 表：本项目图片的派生结构
--    （tags 也是 JSON 数组字符串）都走行内列，多一张子表就要多一套级联清理，
--    而调色板永远只有 1~3 条、只跟图片一起读写。
ALTER TABLE `image`
    ADD COLUMN picPalette VARCHAR(300) NULL
        COMMENT '调色板（JSON: color/ratio/family，top-3；筛选按其中任一 family 命中）'
        AFTER picColorFamily;

-- ② 删掉二期的单值色系列（含它的索引）：筛选已改由 picPalette 里的 family 承担，
--    留着就是「同一件事两个列」，早晚对不上。数据可由回填任务从图片重新算出。
ALTER TABLE `image`
    DROP KEY idx_picColorFamily;

ALTER TABLE `image`
    DROP COLUMN picColorFamily;

-- 回填存量图（picColor + picPalette）：一次性维护任务
-- src/test/java/com/huang/yupicture/tmp/PaletteBackfillTest.java
-- 跑法：PALETTE_BACKFILL=1 mvn -Dtest=PaletteBackfillTest test
-- 它拉 COS 原图重算调色板后 UPDATE 两列（MySQL 没有现成的取色 / 归类函数，
-- 只能由 Java 算完再回写）。
