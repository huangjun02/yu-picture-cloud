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

    /**
     * 审核状态：0-待审核，1-通过，2-拒绝。
     *
     * <p>先过后审（requirements 拍板）：图照常可看可用，审核是事后管理。
     * 进 VO 是给管理员工作台看的 —— 审核状态不算敏感字段，列出来利大于弊
     * （普通用户看到"待审核"还能知道自己的图为什么被管理员找上门）。
     */
    private Integer reviewStatus;

    /** 审核意见（拒绝时后端必填；管理员工作台展示用） */
    private String reviewMessage;

    /** 图片访问地址（COS 公网 URL，前端直接丢给 img 标签） */
    private String url;

    /**
     * 缩略图地址（200×200 以内的等比小图，列表页用它，详情页才用 {@link #url}）。
     *
     * <p>webp 图或「原图本身就比 200 小」时为 <b>null</b> —— 前端拿到 null 回退用 {@code url}。
     * 另注意：功能上线前的存量图没有缩略图文件，此时该地址会 404，
     * 前端 img 用 @error 事件回退原图即可。
     */
    private String thumbnailUrl;

    /**
     * WebP 副本地址（与原图同尺寸、体积更小的新一代格式，详情页大图浏览用它省带宽）。
     *
     * <p>webp 源图（已是 webp，不重复转）或「转换无收益」时为 <b>null</b>。
     * 原图永远保留（{@link #url} 不变），webp 只是多一份派生副本 —— 可逆、老链接不失效。
     * 存量旧图同样会 404，前端按需回退。
     */
    private String webpUrl;

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
