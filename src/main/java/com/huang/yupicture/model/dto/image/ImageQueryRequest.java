package com.huang.yupicture.model.dto.image;

import com.huang.yupicture.model.dto.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 图片分页查询请求。
 *
 * <p><b>一个 DTO 服务两种人，靠 Service 里的权限判断分流：</b>
 * <ul>
 *   <li><b>普通用户</b>：Service 强制追加 {@code userId = 当前登录用户}，只能看到自己的图</li>
 *   <li><b>管理员</b>：不加这个条件，能看所有人的图（也可以主动传 userId 查某个人的）</li>
 * </ul>
 *
 * <p><b>前端传的 userId 不能直接信：</b>普通用户如果传了别人的 userId 想偷看，
 * Service 会把它覆盖成自己的 id —— "以谁的身份查"由登录态决定，不由入参决定。
 * 这是归属校验里最容易被忽略的一环：查询接口同样能造成越权（能看见别人的数据）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ImageQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /** 图片 id（精确匹配） */
    private String id;

    /** 图片名称（模糊匹配） */
    private String name;

    /** 简介（模糊匹配） */
    private String introduction;

    /** 分类（精确匹配） */
    private String category;

    /**
     * 标签筛选。两种传法：
     * <ul>
     *   <li><b>单标签</b>：直接传标签文字（如 {@code 风景}），模糊匹配</li>
     *   <li><b>多选（AND 语义）</b>：传 JSON 数组字符串（如 {@code ["风景","城市"]}），
     *       每个标签单独 like，必须全部命中 —— 图库场景里用户勾了两个标签，
     *       预期就是"同时带这两个标签"的图</li>
     * </ul>
     *
     * <p>判断依据：以 {@code [} 开头且能解析成 JSON 数组 → 按多选处理，否则按单标签模糊查。
     * 多选的每个标签都要单独校验非空 —— {@code ["", "风景"]} 里的空串会生成
     * {@code like '""'} 把结果集搅乱。
     */
    private String tags;

    /**
     * 要查哪个用户的图片（字符串形式的雪花 id）。
     * <p>只有管理员生效；普通用户传了也会被 Service 覆盖成自己的 id。
     */
    private String userId;

    /** 格式（精确匹配：jpg / png / webp） */
    private String picFormat;

    /**
     * 审核状态（精确匹配：0-待审核，1-通过，2-拒绝）。
     *
     * <p>给管理员的审核工作台用 —— 没有这个筛选，前端只能拿当前页数据自己过一遍，
     * 结果就是「第 1 页显示 3 条待审核，第 2 页又冒出 5 条」，跟真实待办数对不上。
     * 翻页语义必须由数据库这层保证。
     */
    private Integer reviewStatus;
}
