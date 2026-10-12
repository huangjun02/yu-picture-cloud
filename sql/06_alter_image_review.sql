-- ============================================================
-- 云图库 yu-picture-cloud · 图片表加审核字段（M3 补：审核流）
-- 执行前提：05_create_table_image.sql 已执行
-- 幂等性：⚠️ MySQL 的 ADD COLUMN 没有 IF NOT EXISTS（8.0 仍不支持），
--          重复执行会报 Duplicate column —— 执行前先看列在不在。
-- ============================================================

USE yu_picture_cloud;

-- 审核流（先过后审）：上传/URL 传图默认 reviewStatus=0（待审核），
-- 管理员通过(1)/拒绝(2)。不通过拦截查询 —— 图片照常可看可用，
-- 审核是"标注 + 事后管理"，不是"准入门槛"（个人项目节奏，requirements 已拍板）。
ALTER TABLE `image`
    ADD COLUMN reviewStatus  TINYINT       NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核，1-通过，2-拒绝' AFTER picFormat,
    ADD COLUMN reviewMessage VARCHAR(512)  NULL COMMENT '审核意见（拒绝时必填原因）' AFTER reviewStatus,
    ADD COLUMN reviewerId    BIGINT        NULL COMMENT '审核人 id（管理员）' AFTER reviewMessage,
    ADD COLUMN reviewTime    DATETIME      NULL COMMENT '审核时间' AFTER reviewerId;

-- 管理员按审核状态筛选待办（WHERE reviewStatus=0 AND isDelete=0）
ALTER TABLE `image`
    ADD KEY idx_reviewStatus (reviewStatus);
