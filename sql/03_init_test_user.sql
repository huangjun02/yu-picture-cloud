-- ============================================================
-- 03_init_test_user.sql —— 本地联调用的种子用户
-- ============================================================
-- 账号：huangjun
-- 密码：12345678（明文；库里只存密文）
-- 密文 = PasswordUtils.encrypt("12345678") = sha256BySalt(明文, "huang_yupicture")
--
-- ⚠️ 密文与「盐值」强绑定：一旦改 UserConstant.PASSWORD_SALT 或换算法，
--    这个密文立刻作废，必须重新生成（用 PasswordUtils 跑一遍即可）。
--
-- 为什么显式写 id 而不是让自增分配：
--   项目用雪花算法生成 id（@TableId(type = ASSIGN_ID)），手工种子用户也照这个格式来，
--   免得库里出现"1 和 2103879065756463105 混着"的两种风格。
--   代价：会把 AUTO_INCREMENT 计数器顶高（无害，应用侧本来也不用自增）。
--
-- 幂等：重复执行只会刷新密码，不会报错、不会插重复用户（靠 uk_userAccount 唯一索引）。
-- ============================================================

USE `yu_picture_cloud`;

INSERT INTO `user` (`id`, `userAccount`, `userPassword`, `userName`, `userProfile`, `userRole`)
VALUES (2103879065756463105,
        'huangjun',
        'e10549ba4f8d6adf7f0228753cd676c120ac7f49c30f2ca3f85623e37c9be8ff',
        '测试用户',
        '本地联调用的种子账号',
        'user')
ON DUPLICATE KEY UPDATE `userPassword` = 'e10549ba4f8d6adf7f0228753cd676c120ac7f49c30f2ca3f85623e37c9be8ff';

SELECT `id`, `userAccount`, `userName`, `userRole` FROM `user` WHERE `userAccount` = 'huangjun';
