package com.huang.yupicture.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.entity.Image;
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
     * 按 id 查询图片详情（本人或管理员）。
     *
     * @param id 图片 id（字符串形式）
     * @return 脱敏后的图片信息
     * @throws com.huang.yupicture.common.BusinessException 未登录（40100）／图片不存在（40000）／无权查看他人的图（40101）
     */
    ImageVO getImageById(String id);

    /**
     * 分页查询图片。
     *
     * <p><b>普通用户只能查到自己的图</b>（Service 强制追加 {@code userId} 条件），
     * 管理员可以查全部、也可以指定 userId 查某个人。
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
}
