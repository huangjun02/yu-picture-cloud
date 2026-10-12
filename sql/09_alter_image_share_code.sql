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
