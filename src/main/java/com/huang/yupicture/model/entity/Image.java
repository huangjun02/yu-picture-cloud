package com.huang.yupicture.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片实体，对应表 image。
 *
 * <p><b>为什么数据库里不存图片本体：</b>把二进制塞进 MySQL（BLOB）会让备份、迁移、
 * 主从同步全部变慢，而且数据库连接是稀缺资源 —— 一张 5 MB 的图走 JDBC 传输，
 * 连接被占用几百毫秒。所以文件放对象存储（COS），数据库只存**元信息 + 访问地址**。
 *
 * <p>{@code userId} 是「归属校验」的依据：编辑 / 删除接口靠它判断「这张图是不是你的」，
 * 这是 requirements 里写死的权限铁律（不能靠前端隐藏按钮来防越权）。
 *
 * <p>{@code @TableLogic} 与 user 表同款：删除是打标记，不是真删行 ——
 * 这样万一用户误删还能救回来，也留下了审计线索。
 */
@TableName("image")
@Data
public class Image implements Serializable {

    /** id（雪花算法，19 位；对外返回时必须字符串化） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 图片访问地址（COS 公网 URL）。
     *
     * <p>长度给到 1024：COS 的 URL 里含 bucket、地域、对象 key、可能还有签名参数，
     * 比一般的 URL 长不少。
     */
    private String url;

    /** 图片名称（用户可改） */
    private String name;

    /** 简介 */
    private String introduction;

    /** 分类 */
    private String category;

    /**
     * 标签（JSON 数组字符串，如 {@code ["风景","城市"]}）。
     *
     * <p>用 VARCHAR 存 JSON 而不是 MySQL 的 JSON 类型：JSON 类型要配 MyBatis-Plus 的
     * TypeHandler 才能映射成对象，而现阶段标签只是"存下去、取出来给前端"，还没到需要
     * 按标签检索的程度（requirements 里按标签筛选是「后续」）。等真要做标签检索时再演进。
     */
    private String tags;

    /** 文件大小（字节） */
    private Long picSize;

    /** 宽度（px） */
    private Integer picWidth;

    /** 高度（px） */
    private Integer picHeight;

    /**
     * 宽高比（宽 / 高）。
     *
     * <p>存下来是为了前端列表页能在图片加载完成前就按比例把位置占好，
     * 避免图片陆续加载时页面高度反复跳动（布局抖动）。
     */
    private Double picScale;

    /**
     * 主色调（{@code #RRGGBB}，大写；提取失败为 {@code null}）。
     *
     * <p>按颜色搜索的检索维度，语义是「<b>调色板第一名</b>」（面积最大的颜色）。
     * 上传 / URL 传图时由 {@code ImageUtils.extractPalette} 提取。
     * {@code null} = 这张图不参与颜色筛选（派生信息提取失败属正常降级，
     * 与缩略图 / WebP 同一条纪律），不是数据缺失。
     */
    private String picColor;

    /**
     * 调色板（JSON 数组字符串，形如
     * {@code [{"color":"#FDF9FB","ratio":76,"family":"white"},…]}；提取失败为 {@code null}）。
     *
     * <p>按颜色搜索的<b>筛选入口</b>。单主色只认面积最大的颜色，遇到「白底 + 彩色主体」
     * （壁纸 / 商品图 / 截图）时主体色被背景盖住；调色板把背景 / 主体 / 点缀都收进来，
     * 筛选条件随之变成「<b>任意一条落在该色系</b>」（SQL 侧用
     * {@code JSON_CONTAINS(picPalette, '"blue"', '$[*].family')}）。
     * 同时供详情页画色条（色值 + 占比），前端按 tags 那套「宽容解析」处理历史脏值。
     */
    private String picPalette;

    /**
     * 感知哈希（pHash，64 位 → 16 位十六进制，小写；提取失败为 {@code null}）。
     *
     * <p>以图搜图的检索键。上传 / URL 传图时由 {@code ImageUtils.perceptualHash} 计算。
     * {@code null} = 这张图不参与以图搜图（老图未回填、或解码失败），不是数据缺失。
     *
     * <p>为什么它必须落库（「可推导信息不落库」的又一处例外）：哈希是<b>像素的统计量</b>，
     * 不是 URL 的函数 —— 要现算就得把图从 COS 拉回来解码，一次搜索拉上千张图。
     * 与 picColor 同一条理由。
     */
    private String picHash;

    /**
     * 内容 MD5 指纹（32 位小写十六进制；秒传功能上线前的存量图为 {@code null}）。
     *
     * <p><b>秒传的精确去重键</b>，对「压缩后最终落库的内容」计算 —— 同一份字节必然
     * 压缩出同一结果，指纹也必然相同；改一个像素 MD5 就完全不同（这正是与
     * {@link #picHash 感知哈希} 的分工：MD5 管精确去重，pHash 管相似搜索）。
     *
     * <p>普通索引 {@code idx_md5}：秒传查询走索引等值命中。刻意<b>不是唯一索引</b> ——
     * 归属语义是「B 传 A 已有图 → 新记录挂 B 名下、复用 COS 对象」，同指纹多条记录
     * 是模型的一部分，唯一索引会直接禁止它。并发查-插竞态的最坏结果只是多传一份存储，
     * 无错误无丢数据。
     */
    private String md5;

    /** 格式：jpg / png / webp ... */
    private String picFormat;

    // ==================== 审核流（先过后审，requirements 已拍板） ====================

    /** 审核状态：0-待审核，1-通过，2-拒绝 */
    private Integer reviewStatus;

    /** 审核意见（拒绝时必填原因 —— 没有理由的拒绝比拒绝本身更伤人） */
    private String reviewMessage;

    /** 审核人 id（管理员） */
    private Long reviewerId;

    /** 审核时间 */
    private Date reviewTime;

    /** 上传用户 id（归属校验的依据） */
    private Long userId;

    /** 创建时间（DDL 默认 CURRENT_TIMESTAMP 兜底） */
    private Date createTime;

    /** 更新时间（DDL 的 ON UPDATE 自动刷新） */
    private Date updateTime;

    /** 是否删除：0-正常，1-已删除（逻辑删除） */
    @TableLogic
    private Integer isDelete;

    /**
     * 分享短链码（随机 Base62 8 位；未分享为 null）。
     *
     * <p><b>为什么是随机码：</b>它是"凭码即可查看"的持有凭证 —— 用自增 / 时间戳这类
     * 可预测的值，别人能枚举出未公开的图。用 {@link java.security.SecureRandom} 生成。
     *
     * <p>唯一性由数据库唯一索引 {@code uk_shareCode} 兜底（代码里的"先查再插"防不住并发）。
     */
    private String shareCode;

    private static final long serialVersionUID = 1L;
}
