-- ============================================================
-- 云图库 yu-picture-cloud · 用户表索引调整（04）
-- 执行前提：02_create_table_user.sql 已执行（表 user 已存在）
-- 执行方式：IDEA 右侧 Database 工具 / Navicat / MySQL Workbench
--           命令行：mysql -uroot -p --default-character-set=utf8mb4 < 本文件
-- 幂等性：非幂等（MySQL 的 ALTER 不支持"改索引 IF EXISTS"）。
--         重复执行会报 "Can't DROP ... check that column/key exists"，
--         报错即说明已经改过，忽略即可。
-- ============================================================

USE yu_picture_cloud;

-- 目的：让"被逻辑删除的账号可以被重新注册"。
--
-- 原索引 uk_userAccount(userAccount) 是单列唯一，它有个副作用：
-- 逻辑删除只把 isDelete 置 1，那一行仍然占着账号 —— 用户删号后想重新注册，
-- 会被数据库拦下并报"账号已存在"，可用户根本不知道自己的旧账号还"活着"。
--
-- 改成 (userAccount, isDelete) 复合唯一之后：
--   同一个账号 → 最多一条"活着"的记录(isDelete=0) + 最多一条"已删除"的记录(isDelete=1)
--   于是「删除 → 重新注册」这条路通了。
--
-- ⚠️ 已知局限（现阶段接受，先说清楚）：
--   同一账号若是「删除 → 重新注册 → 再删除」，第二次删除会把第二条也置成 isDelete=1，
--   与第一条已删除记录组成重复的 (账号, 1) → 撞唯一索引，删除失败。
--   触发条件苛刻（同一账号得被删两次），且 Service 层已 catch 该异常给出明确提示。
--   将来真遇到，两条路线：
--     ① 删除时把 isDelete 写成"删除时刻的唯一值"（如该行 id），复合索引天然不冲突；
--     ② 删除时把 userAccount 改名（如追加 #deleted#<id>）释放账号，单列唯一也够用。
--
-- 执行前自查：若库里已存在"同一账号的多条已删除记录"，ADD UNIQUE 会失败。
--   先跑这句，确认返回 0 行：
--     SELECT userAccount, isDelete, COUNT(*) FROM `user`
--     GROUP BY userAccount, isDelete HAVING COUNT(*) > 1;

ALTER TABLE `user` DROP INDEX uk_userAccount;
ALTER TABLE `user` ADD UNIQUE KEY uk_userAccount_isDelete (userAccount, isDelete);
