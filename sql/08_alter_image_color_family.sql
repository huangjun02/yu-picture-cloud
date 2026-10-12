-- ============================================================
-- 云图库 yu-picture-cloud · 图片表加色系字段（按颜色搜索二期）
-- 执行前提：07_alter_image_pic_color.sql 已执行
-- 背景：主色是连续空间的具体值（#FDF9FB「近似白」），色板是离散标准色
--       （#FFFFFF）——精确匹配永远对不上。加 picColorFamily（色系）列，
--       上传时由 picColor 归类，筛选按色系等值匹配。
-- 幂等性：⚠️ 重复执行会报 Duplicate column，执行前先看列在不在。
-- ============================================================

USE yu_picture_cloud;

-- 色系（white/black/red/orange/yellow/green/cyan/blue/purple/brown）。
-- 由 ImageUtils.classifyColorFamily(picColor) 归类；picColor 为 NULL 时同为 NULL。
ALTER TABLE `image`
    ADD COLUMN picColorFamily VARCHAR(8) NULL COMMENT '色系（筛选入口；由 picColor 归类）' AFTER picColor;

ALTER TABLE `image`
    ADD KEY idx_picColorFamily (picColorFamily);

-- 回填存量图（含「上线前没主色的图」）：用一次性维护任务
-- src/test/java/com/huang/yupicture/tmp/ColorFamilyBackfillTest.java
-- 跑法：COLOR_FAMILY_BACKFILL=1 mvn -Dtest=ColorFamilyBackfillTest test
-- 它拉 COS 原图重算主色 + 归类后 UPDATE 两列（MySQL 没有现成 HSV 函数，
-- 归类只能由 Java 做完再回写）。
