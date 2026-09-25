-- ============================================================
-- 云图库 yu-picture-cloud · 数据库初始化
-- 执行方式：IDEA 右侧 Database 工具 / Navicat / MySQL Workbench
-- 执行顺序：文件编号顺序（01 → 02 → 03 ...）
-- ============================================================

-- 01. 创建数据库
-- 为什么是 utf8mb4 而不是 utf8：
--   MySQL 的 "utf8" 其实是个残缺实现，最多 3 字节，
--   存不了 emoji 和部分生僻字；utf8mb4 才是真正的 UTF-8（4 字节）。
--   用户昵称、图片描述里一旦出现 emoji，用 utf8 就会直接报错或截断。
CREATE DATABASE IF NOT EXISTS yu_picture_cloud
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
