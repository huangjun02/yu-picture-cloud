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

    /** 格式：jpg / png / webp ... */
    private String picFormat;

    /** 上传用户 id（归属校验的依据） */
    private Long userId;

    /** 创建时间（DDL 默认 CURRENT_TIMESTAMP 兜底） */
    private Date createTime;

    /** 更新时间（DDL 的 ON UPDATE 自动刷新） */
    private Date updateTime;

    /** 是否删除：0-正常，1-已删除（逻辑删除） */
    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
