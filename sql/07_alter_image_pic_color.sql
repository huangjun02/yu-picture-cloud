-- ============================================================
-- 云图库 yu-picture-cloud · 图片表加主色字段（按颜色搜索）
-- 执行前提：05_create_table_image.sql 已执行
-- 幂等性：⚠️ MySQL 8.0 的 ADD COLUMN 不支持 IF NOT EXISTS，
--          重复执行会报 Duplicate column —— 执行前先看列在不在。
-- ============================================================

USE yu_picture_cloud;

-- 主色调（#RRGGBB，大写）。上传/URL 传图时由 ImageUtils.extractMainColor 提取；
-- 提取失败为 NULL（该图不参与颜色筛选，属正常降级，不是数据缺失）。
-- 精确匹配（eq）查询：WHERE picColor = '#RRGGBB' —— 筛选走的是前端色板给的标准色，
-- 不是自由输入，所以不需要前缀索引或 LIKE。
ALTER TABLE `image`
    ADD COLUMN picColor CHAR(7) NULL COMMENT '主色调（#RRGGBB，大写；提取失败为 NULL）' AFTER picScale;

-- 颜色筛选走精确等值查询
ALTER TABLE `image`
    ADD KEY idx_picColor (picColor);
