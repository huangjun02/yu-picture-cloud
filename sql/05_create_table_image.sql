-- ============================================================
-- 云图库 yu-picture-cloud · 图片表（M3）
-- 执行前提：01_create_database.sql 已执行（库 yu_picture_cloud 已存在）
-- 执行方式：IDEA 右侧 Database 工具 / Navicat / MySQL Workbench
-- 幂等性：CREATE TABLE IF NOT EXISTS —— 重复执行不报错、不覆盖已有数据
-- ============================================================

USE yu_picture_cloud;

-- 05. 图片表
-- 设计要点：
--   ① 元信息全在这里（名称/简介/分类/标签/尺寸/格式/大小），文件本体在 COS —— 数据库不存二进制
--   ② userId 是「归属校验」的依据：编辑 / 删除接口靠它判断"这张图是不是你的"
--   ③ 逻辑删除：删图时数据库标记 + 同步删 COS 上的文件（顺序见 Service 注释）
CREATE TABLE IF NOT EXISTS `image`
(
    id           BIGINT AUTO_INCREMENT COMMENT 'id',
    url          VARCHAR(1024) NOT NULL COMMENT '图片访问地址（COS 公网 URL）',
    name         VARCHAR(256)  NULL COMMENT '图片名称',
    introduction VARCHAR(512)  NULL COMMENT '简介',
    category     VARCHAR(128)  NULL COMMENT '分类',
    tags         VARCHAR(512)  NULL COMMENT '标签（JSON 数组字符串，如 ["风景","城市"]）',
    picSize      BIGINT        NULL COMMENT '文件大小（字节）',
    picWidth     INT           NULL COMMENT '宽度（px）',
    picHeight    INT           NULL COMMENT '高度（px）',
    picScale     DOUBLE        NULL COMMENT '宽高比（宽 / 高），列表页按比例预留位置、防抖动',
    picFormat    VARCHAR(32)   NULL COMMENT '格式：jpg / png / webp ...',
    -- 与 sql/07 / 08 / 10 / 12 四条迁移同步（picColor 主色、picColorFamily 色系、picHash 感知哈希、md5 秒传指纹）
    picHash      CHAR(16)      NULL COMMENT '感知哈希（64 位十六进制，小写；以图搜图用）',
    md5          CHAR(32)      NULL COMMENT '内容 MD5 指纹（32 位小写十六进制，秒传去重键）',
    reviewStatus  TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核，1-通过，2-拒绝',
    reviewMessage VARCHAR(512)  NULL COMMENT '审核意见（拒绝时必填原因）',
    reviewerId    BIGINT       NULL COMMENT '审核人 id（管理员）',
    reviewTime    DATETIME      NULL COMMENT '审核时间',
    userId       BIGINT        NOT NULL COMMENT '上传用户 id（归属校验的依据）',
    createTime   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否删除：0-正常，1-已删除（逻辑删除）',
    shareCode    VARCHAR(16)   NULL COMMENT '分享短链码（随机 Base62，未分享为 NULL）',
    PRIMARY KEY (id),
    -- 分享短链：按码查图走它；同时防生成时撞码
    UNIQUE KEY uk_shareCode (shareCode),
    -- 秒传：按指纹等值查重走它（普通索引 —— 多归属下同指纹多条记录合法，见 sql/12）
    KEY idx_md5 (md5),
    -- 「我的图片」列表按 userId 过滤，必建
    KEY idx_userId (userId),
    -- 按名称搜索、按分类筛选
    KEY idx_name (name),
    KEY idx_category (category),
    -- 管理员待审核列表按状态筛（WHERE reviewStatus = 0 AND isDelete = 0）
    KEY idx_reviewStatus (reviewStatus),
    -- 列表默认按时间倒序
    KEY idx_createTime (createTime)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '图片';

-- 索引备注（将来数据量上来再改，现在不必）：
--   列表查询是 WHERE userId = ? AND isDelete = 0 ORDER BY createTime DESC
--   单列索引在数据量大时仍需回表排序，那时改成复合索引更优：
--     KEY idx_userId_createTime (userId, createTime)
--   现在几千条数据用不上，过早优化只会让表结构变复杂。
