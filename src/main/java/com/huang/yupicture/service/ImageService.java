package com.huang.yupicture.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.dto.image.ImageBatchDeleteRequest;
import com.huang.yupicture.model.dto.image.ImageBatchReviewRequest;
import com.huang.yupicture.model.dto.image.ImageBatchUpdateRequest;
import com.huang.yupicture.model.dto.image.ImageDownloadResult;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.vo.ImageBatchResultVO;
import com.huang.yupicture.model.vo.ImageOptionsVO;
import com.huang.yupicture.model.vo.ImageVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
     * 生成（或复用）图片的分享短链码 —— 本人或管理员。
     *
     * <p><b>幂等</b>：已分享过就直接返回原码，重复点「分享」不会产生一串不同链接。
     *
     * @param id 图片 id（字符串形式的雪花 id）
     * @return 分享短链码（随机 Base62；前端用 {@code origin + "/share/" + code} 拼完整链接）
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40400）／无权分享他人的图（40101）
     */
    String shareImage(String id);

    /**
     * 按分享码查看图片（<b>免登录</b>，已加入 SaTokenConfig 白名单）。
     *
     * <p>可见性与 {@code getImageById} 完全一致：已通过任何人可看；未通过只有本人/管理员可看，
     * 其他人（含游客）报 40400「图片不存在」—— 连存在性都不暴露。
     * 已逻辑删除的图查询不到 → 短链自然"失效"。
     *
     * @param shareCode 分享码
     * @return 脱敏后的图片信息
     * @throws com.huang.yupicture.common.BusinessException 分享码为空（40000）／图不存在或已失效（40400）
     */
    ImageVO getSharedImage(String shareCode);

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
     * 以图搜图：上传一张图，返回图库里与它最相似的图（按相似度从高到低）。
     *
     * <p><b>搜索范围 = 主页图墙语义</b>：只搜「已通过」的图，所有登录用户看到的是同一批数据。
     * 选这个口径的理由：以图搜图的入口在主页（浏览场景），而且图库量级下「找出图库里的
     * 相似图」才是有意义的用法。范围钉死在服务端、不接受入参控制 ——
     * 接口的入参一旦能决定「能看谁的数据」，越权就从这里长出来（同 applyUserScope 的立场）。
     *
     * <p>相似度用感知哈希的汉明距离衡量（见 {@code ImageUtils.perceptualHash}）：
     * 距离 ≤ {@code ImageConstant.SIMILAR_MAX_DISTANCE} 才算相似，最多返回
     * {@code SIMILAR_MAX_RESULT} 张。没有相似图时返回空列表（不是报错）。
     *
     * @param file 查询图（走与上传同一套校验：非空 / 大小 / 后缀白名单 / 魔数）
     * @return 相似的图片列表（最像的在前）；无匹配时为空列表
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／不是合法图片（40000）
     */
    List<ImageVO> searchSimilarImage(MultipartFile file);

    /**
     * 主页筛选项：当前可见范围内出现过的分类与标签（去重）。
     *
     * <p>为什么不固定写死一组分类（官方前台是写死的 demo 列表）：图库的分类/标签
     * 来自用户自己上传时填的内容，写死的话用户填的分类在主页筛不出来 ——
     * 筛选项必须从真实数据来，否则"能筛"是假的。
     */
    ImageOptionsVO listOptions();

    // ==================== 批量操作 ====================

    /**
     * 批量操作的单次上限。
     *
     * <p>同步接口不做异步任务队列：列表分页每页 ≤ 20，勾满几页就到顶了；
     * 上限是防呆（前端 bug 传来一万条把请求拖死），不是真实业务约束。
     * 超限整体报参数错误，不截断 —— 「你选了 120 张却只删了 100 张」的静默丢行为
     * 比明确拒绝危险得多。
     */
    int BATCH_MAX_SIZE = 100;

    /**
     * 批量删除图片（本人或管理员，逐条归属校验）。
     *
     * <p>逐条复用 {@link #deleteImage(String)}：COS 清理顺序（先文件后记录、
     * 缩略图/WebP 尽力而为）与单条删除完全一致，不另写一份。
     *
     * <p><b>部分成功语义</b>：一条失败（不存在 / 越权 / id 非法）不中断整批，
     * 失败原因逐条带回。不开事务回滚 —— COS 文件删除无法回滚，「回滚」只会制造
     * 记录还在、文件没了的更糟状态。
     *
     * @return 成功 / 失败计数与失败明细
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）；
     *                                                      列表为空或超过 {@link #BATCH_MAX_SIZE}（40000）
     */
    ImageBatchResultVO batchDeleteImages(ImageBatchDeleteRequest request);

    /**
     * 批量编辑图片的分类 / 追加标签（本人或管理员，逐条归属校验）。
     *
     * <p>category 是「设置」语义（覆盖），appendTags 是「追加」语义
     * （并入现有标签后整体去重，不清掉原有的）。与单条编辑一致，
     * 编辑会让审核状态重新走一遍（管理员改 → 自动过审；用户改 → 退回待审核）。
     *
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）；
     *                                                      列表为空 / 超限 / category 与 appendTags 都没传 /
     *                                                      appendTags 含空白标签（40000）
     */
    ImageBatchResultVO batchUpdateImages(ImageBatchUpdateRequest request);

    /**
     * 批量审核图片（管理员）。
     *
     * <p>逐条复用 {@link #reviewImage}（状态机 / 重置开关 / 审核参数补齐一字不差）。
     * 「拒绝必填原因」在进循环之前整批校验 —— 原因缺失报参数错误，
     * 不该审到一半才发现白跑一趟。
     *
     * @throws com.huang.yupicture.common.BusinessException 非管理员（40101）；未登录（40100）；
     *                                                      列表为空 / 超限 / 状态非法 / 拒绝缺原因（40000）
     */
    ImageBatchResultVO batchReviewImages(ImageBatchReviewRequest request);
}
