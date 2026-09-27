package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 脱敏后的用户信息 —— 管理员场景（用户列表 / 用户详情）对外暴露的视图对象。
 *
 * <p><b>为什么已经有 {@link LoginUserVO}，还要再建一个：</b>
 * 两者字段此刻完全一样，但语义不同：
 * <ul>
 *   <li>{@link LoginUserVO} 描述<b>当前登录者</b>——将来会长出"本次会话权限 / 登录时间"</li>
 *   <li>{@code UserVO} 描述<b>被展示的某个用户</b>——将来会长出"是否在线 / 上传图片数"</li>
 * </ul>
 * 合成一个的代价是：管理员列表为了拿一个和登录无关的字段，要改动登录接口的契约。
 * 让两个 VO 各自演化，比让一个 VO 同时服务两种语义便宜。
 *
 * <p>字段暂时相同是有意的，不是没写完 —— 别急着"消除重复"。
 *
 * <p>和所有对外 VO 一样：没有 {@code userPassword} 字段。脱敏不是"记得别返回"，
 * 而是"这个类里压根没有这个字段"——泄漏需要一个明确的编辑动作才能发生。
 */
@Data
public class UserVO implements Serializable {

    /**
     * 用户 id —— 和 LoginUserVO 一样<b>故意用 String</b>。
     * 雪花 id 是 19 位 long，超出 JS 的 Number.MAX_SAFE_INTEGER（2^53-1），
     * 序列化成数字后 JS 会静默丢精度，拿截断的 id 查数据只会"莫名查不到"。
     */
    private String id;

    /** 账号 */
    private String userAccount;

    /** 昵称 */
    private String userName;

    /** 头像 URL */
    private String userAvatar;

    /** 简介 */
    private String userProfile;

    /** 角色：user / admin */
    private String userRole;

    /** 注册时间（管理员排查看"这个人什么时候来的"时有用，登录 VO 不需要） */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
