package com.huang.yupicture.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.dto.image.ImageDownloadResult;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.vo.ImageOptionsVO;
import com.huang.yupicture.model.vo.ImageVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片业务接口。
 *
 * <p>⚠️ 与 user 模块的一处关键差别：这里是<b>「本人或管理员」</b>的两级权限。
 * user 模块的管理员接口是"只有管理员能用"（{@code checkAdminUser()}）；而图片的编辑 /
 * 删除是"自己的能改、别人的管理员才能改" —— 所以校验函数长成
 * {@code checkImageOwner(image, loginUser)}，多一个"要校验哪个资源"的参数。
 *
 * <p>这就是 requirements 里的「权限铁律」：
 * <blockquote>任何"操作某个资源"的接口都必须做归属校验 —— 只能操作自己名下的资源，
 * 管理员例外。这类越权漏洞不能靠前端隐藏按钮来防。</blockquote>
 */
public interface ImageService extends IService<Image> {

    /**
     * 按 id 查询图片详情。
     *
     * <p><b>可见性</b>：本人看自己的全部状态；管理员看全部；其他人只能看已通过的
     * （主页图墙语义）。别人未通过的图报 40400「图片不存在」—— 连"这张图存在"都不该暴露。
     *
     * @param id 图片 id（字符串形式）
     * @return 脱敏后的图片信息
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40000）／无权查看他人的图（40101）
     */
    ImageVO getImageById(String id);

    /**
     * URL 传图：后端拉取远端图片，处理链与文件上传一致（压缩 / 缩略图 / webp / 落库）。
     *
     * @param fileUrl 图片地址（仅公网 http/https，SSRF 校验在 {@code UrlPictureUtils}）
     * @param name    图片名称（可选；不传用 URL 路径尾段）
     * @return 新图片的脱敏信息
     */
    ImageVO uploadImageByUrl(String fileUrl, String name);

    /**
     * 审核图片（管理员）：通过 / 拒绝。
     *
     * @param id            图片 id
     * @param reviewStatus  审核结果：1-通过 / 2-拒绝（其它值报参数错误）
     * @param reviewMessage     审核意见（拒绝时必填 —— 没有理由的拒绝比拒绝本身更伤人）
     * @param resetReviewParams 是否重置全部审核参数（审核人 / 时间 / 意见）；
     *                          false 表示只改状态，上一次的审核信息留作历史参考
     * @return 是否成功
     */
    boolean reviewImage(String id, Integer reviewStatus, String reviewMessage, Boolean resetReviewParams);

    /**
     * 下载图片文件（本人或管理员）。
     *
     * <p>与查询接口同一套归属校验 —— 「能看就能下载」，反之「看不了也下不了」：
     * 下载本质上是一次"读"，权限模型与 getImageById 完全一致，不另设规则。
     *
     * @param id 图片 id（字符串形式的雪花 id）
     * @return 文件字节 + 建议的保存文件名 + Content-Type（Controller 拼响应头用）
     */
    ImageDownloadResult downloadImage(String id);

    /**
     * 分页查询图片。
     *
     * <p><b>普通用户两种视角</b>：
     * <ul>
     *   <li>传自己的 userId（「我的图片」页）：自己的全部状态，能看见待审核 / 被拒</li>
     *   <li>不传 userId（主页浏览）：全站<b>已通过</b>的图</li>
     * </ul>
     * 管理员：默认查全部，可指定 userId，也可按 reviewStatus 筛待办。
     *
     * @param imageQueryRequest 分页 + 查询条件
     * @return 分页结果（每页条数已被夹到上限内）
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）
     */
    Page<ImageVO> listImageByPage(ImageQueryRequest imageQueryRequest);

    /**
     * 编辑图片信息（名称 / 简介 / 分类 / 标签）—— 本人或管理员。
     *
     * @param imageUpdateRequest 编辑请求（为 null 的字段表示不改）
     * @return 是否成功
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40000）／无权操作他人的图（40101）
     */
    boolean updateImage(ImageUpdateRequest imageUpdateRequest);

    /**
     * 删除图片（逻辑删除）—— 本人或管理员。
     *
     * <p>⚠️ 待补：存储上的文件也要一起删（否则 COS 里会留下永远没人引用的孤儿文件，
     * 白占容量还收费）。上传能力就位后一起补上。
     *
     * @param id 图片 id（字符串形式）
     * @return 是否成功
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40000）／无权删除他人的图（40101）
     */
    boolean deleteImage(String id);

    /**
     * 上传图片（本人）。
     *
     * <p>校验顺序（任何一步不过都不消耗存储）：
     * <ol>
     *   <li>文件非空、不超过 5MB</li>
     *   <li>后缀在白名单内（jpg / jpeg / png / webp）</li>
     *   <li><b>读文件头魔数，确认内容与后缀一致</b> —— 防"改名伪装"（evil.exe → girl.jpg）</li>
     * </ol>
     * 校验通过后再上传对象存储、最后落库。<b>注意顺序：先存储后数据库</b>，
     * 这样"数据库里有的图"一定真实存在；反过来先写数据库，上传失败就会留下指向空气的记录。
     *
     * @param file 上传的文件
     * @param name 图片名称（可选；不传则取原始文件名去掉后缀）
     * @return 新图片的脱敏信息
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／参数不合法（40000）／上传失败（50000）
     */
    ImageVO uploadImage(MultipartFile file, String name);

    /**
     * 实体 → 脱敏 VO。
     *
     * @param image 图片实体，可为 null
     * @return 脱敏 VO，入参 null 时返回 null
     */
    ImageVO toImageVO(Image image);

    /**
     * 主页筛选项：当前可见范围内出现过的分类与标签（去重）。
     *
     * <p>为什么不固定写死一组分类（官方前台是写死的 demo 列表）：图库的分类/标签
     * 来自用户自己上传时填的内容，写死的话用户填的分类在主页筛不出来 ——
     * 筛选项必须从真实数据来，否则"能筛"是假的。
     */
    ImageOptionsVO listOptions();
}
