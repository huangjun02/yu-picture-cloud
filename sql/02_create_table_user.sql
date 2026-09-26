-- ============================================================
-- 云图库 yu-picture-cloud · 用户表
-- 执行前提：01_create_database.sql 已执行（库 yu_picture_cloud 已存在）
-- 执行方式：IDEA 右侧 Database 工具 / Navicat / MySQL Workbench
--           命令行：mysql -uroot -p --default-character-set=utf8mb4 < 本文件
-- 幂等性：CREATE TABLE IF NOT EXISTS —— 重复执行不报错、不覆盖已有数据
-- ============================================================

-- 先切到目标库，否则会报 "No database selected"
USE yu_picture_cloud;

-- 02. 用户表
-- 设计三原则：① 账号唯一由数据库兜底 ② 密码只存密文 ③ 删除用逻辑删除（不真删）
CREATE TABLE IF NOT EXISTS `user`
(
    id           BIGINT AUTO_INCREMENT COMMENT 'id',
    userAccount  VARCHAR(256)  NOT NULL COMMENT '账号',
    userPassword VARCHAR(512)  NOT NULL COMMENT '密码（加密后的密文，绝不存明文）',
    userName     VARCHAR(256)  NULL COMMENT '用户昵称',
    userAvatar   VARCHAR(1024) NULL COMMENT '用户头像（图片 URL，可能很长）',
    userProfile  VARCHAR(512)  NULL COMMENT '用户简介',
    userRole     VARCHAR(256)  NOT NULL DEFAULT 'user' COMMENT '用户角色：user / admin',
    createTime   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否删除：0-正常，1-已删除（逻辑删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_userAccount (userAccount),
    KEY idx_userName (userName)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '用户';
