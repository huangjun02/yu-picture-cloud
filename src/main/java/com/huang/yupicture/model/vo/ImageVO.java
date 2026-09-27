package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 脱敏后的图片信息 —— 图片模块对外暴露的视图对象。
 *
 * <p><b>image 表里没有敏感字段（不像 user 有密码），为什么还要 VO？</b>
 * 三个理由，跟"有没有密码"无关：
 * <ol>
 *   <li><b>id 必须字符串化</b>：实体里是 19 位 {@code Long}，直出会让 JS 丢精度
 *       （末尾几位变 0，之后拿它查数据只会"莫名查不到"）</li>
 *   <li><b>对外清单必须显式</b>：实体是"数据库有什么"，VO 是"前端能看什么"。
 *       明天 image 表加一个 {@code reviewRemark}（内部审核备注）、{@code storageKey}（COS 对象键），
 *       直接返回实体就会把这些一起漏出去，而且没有任何提示</li>
 *   <li><b>前端契约要独立于表结构</b>：表结构随业务演进（改字段、拆表），
 *       前端不该被牵动。VO 就是那层缓冲</li>
 * </ol>
 */
@Data
public class ImageVO implements Serializable {

    /** 图片 id —— String，理由见类注释第 1 条 */
    private String id;

    /** 图片访问地址（COS 公网 URL，前端直接丢给 img 标签） */
    private String url;

    /** 图片名称 */
    private String name;

    /** 简介 */
    private String introduction;

    /** 分类 */
    private String category;

    /** 标签（JSON 数组字符串，前端自行解析） */
    private String tags;

    /** 文件大小（字节） */
    private Long picSize;

    /** 宽度（px） */
    private Integer picWidth;

    /** 高度（px） */
    private Integer picHeight;

    /** 宽高比（宽 / 高），前端用它预留位置防布局抖动 */
    private Double picScale;

    /** 格式：jpg / png / webp */
    private String picFormat;

    /** 上传用户 id —— 同样是 String（雪花 id 字符串化是铁律，不因为是"别人的 id"就打折） */
    private String userId;

    /** 上传时间 */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
