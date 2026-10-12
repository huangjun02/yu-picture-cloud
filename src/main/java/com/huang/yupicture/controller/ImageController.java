package com.huang.yupicture.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huang.yupicture.common.BaseResponse;
import com.huang.yupicture.common.ResultUtils;
import com.huang.yupicture.model.dto.image.ImageDownloadResult;
import com.huang.yupicture.model.dto.image.ImageCrawlRequest;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageReviewRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.vo.ImageCrawlResultVO;
import com.huang.yupicture.model.vo.ImageOptionsVO;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageCrawlService;
import com.huang.yupicture.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片接口。
 *
 * <p><b>Controller 里不放业务逻辑</b>：这里只有"收参数 → 调 Service → 包成统一响应"。
 * 一个 {@code if} 都不应该有 —— 权限判断、参数校验、分页加固全在 Service。
 * 好处是可测：Service 的方法能脱离 HTTP 直接单测，而 Controller 只有一层搬运工。
 *
 * <p><b>注意这些接口在拦截器白名单之外</b>（{@code SaTokenConfig} 的白名单里只有登录 / 注册 /
 * 健康检查 / 接口文档），所以每一个都要求登录态 —— 未登录会由拦截器直接拦成 40100，
 * 根本走不到这里的方法体。
 *
 * <p><b>参数位置的小规矩</b>（与 user 模块一致）：单字段走 URL 参数，
 * 多字段走请求体。
 * <ul>
 *   <li>URL：{@code /image/get?id=xxx}、{@code /image/delete?id=xxx}</li>
 *   <li>Body：{@code /image/list/page}、{@code /image/update}</li>
 * </ul>
 *
 * <p><b>权限说明</b>：本模块没有"只有管理员能进"的接口，只有"本人 or 管理员"（Service 里的
 * {@code checkImageOwner}）。所以 {@code @Operation} 的 summary 里标的是"本人或管理员"，
 * 而不是像 user 模块那样用"管理员 xxx"前缀。
 */
@RestController
@RequestMapping("/image")
@Tag(name = "图片接口")
public class ImageController {

    @Resource
    private ImageService imageService;


    @Resource

    private ImageCrawlService imageCrawlService;

    /**
     * 上传图片（本人）。
     *
     * <p>请求类型是 {@code multipart/form-data}，两个字段：
     * <ul>
     *   <li>{@code file}（必填）—— 文件本体</li>
     *   <li>{@code name}（可选）—— 图片名称，不传就用原始文件名去掉后缀</li>
     * </ul>
     *
     * <p>用 {@code @RequestPart} 而不是 {@code @RequestParam} 接文件：
     * 前者按 part 的名称取，后者是表单字段语义 —— multipart 请求里用 {@code @RequestPart}
     * 才能正确拿到文件的原始文件名和 Content-Type。
     */
    @PostMapping("/upload")
    @Operation(summary = "上传图片")
    public BaseResponse<ImageVO> uploadImage(@RequestPart("file") MultipartFile file,
                                             @RequestParam(value = "name", required = false) String name) {
        return ResultUtils.success(imageService.uploadImage(file, name));
    }

    /**
     * 查询图片详情（本人或管理员）。
     *
     * @param id 图片 id（字符串形式的雪花 id；前端必须传字符串，不能传数字）
     */
    @PostMapping("/get")
    @Operation(summary = "查询图片详情（本人或管理员）")
    public BaseResponse<ImageVO> getImageById(@RequestParam String id) {
        return ResultUtils.success(imageService.getImageById(id));
    }

    /**
     * 分页查询图片。
     *
     * <p>普通用户只能查到自己的图（Service 强制加 userId 条件）；
     * 管理员能查全部，也能主动传 userId 查某个人。
     */
    @PostMapping("/list/page")
    @Operation(summary = "分页查询图片（普通用户只返回自己的）")
    public BaseResponse<Page<ImageVO>> listImageByPage(@RequestBody ImageQueryRequest imageQueryRequest) {
        return ResultUtils.success(imageService.listImageByPage(imageQueryRequest));
    }

    /**
     * 下载图片文件（本人或管理员）。
     *
     * <p><b>统一响应的唯一例外</b>：不走 {@code BaseResponse} JSON 包装，直接回二进制 ——
     * 图片塞进 JSON 会被 base64 化，白白多 33% 体积还多一次前端解码。下载语义就是
     * 「给我文件本身」，HTTP 原生就是干这个的。
     *
     * <p>权限与查询一致（能看就能下载），校验在 Service。文件名经
     * {@code ContentDisposition} 正确编码 —— 中文名直接塞 header 会乱码。
     */
    @PostMapping("/download")
    @Operation(summary = "下载图片文件（本人或管理员）")
    public ResponseEntity<byte[]> downloadImage(@RequestParam String id) {
        ImageDownloadResult result = imageService.downloadImage(id);
        // filename* 按 RFC 5987 编码中文；ascii 兜底给不支持 filename* 的老客户端
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(result.getBytes().length)
                .body(result.getBytes());
    }

    /**
     * URL 传图（本人）。
     *
     * <p>服务器替用户拉取远端图片，后续处理与文件上传一致。
     * 参数走 URL 而不是请求体：与 delete/get 同款 ——
     * 单字段走 URL 参数是本项目的既定约定（fileUrl 可能带特殊字符，前端要 encodeURIComponent）。
     */
    /**
     * 批量抓图（管理员）：按关键词从开放许可图库检索并入库。
     *
     * <p>同步接口：一次最多 20 张，每张约 1~2 秒（下载 + 压缩 + 缩略图 + 上传 COS），
     * 十几秒内能返回。再大就该上异步任务了，但那是另一个量级的问题。
     */
    @PostMapping("/crawl")
    public BaseResponse<ImageCrawlResultVO> crawlImages(@RequestBody ImageCrawlRequest request) {
        return ResultUtils.success(imageCrawlService.crawl(request));
    }

    @PostMapping("/upload/url")
    @Operation(summary = "URL 传图（拉取远端图片，本人）")
    public BaseResponse<ImageVO> uploadImageByUrl(@RequestParam String fileUrl,
                                                  @RequestParam(value = "name", required = false) String name) {
        return ResultUtils.success(imageService.uploadImageByUrl(fileUrl, name));
    }

    /**
     * 审核图片（管理员）。
     *
     * <p>三个参数都在请求体：审核结果是"一次提交"（id + 结论 + 意见同属一次动作），
     * 与 update 同款的多字段语义。
     */
    @PostMapping("/review")
    @Operation(summary = "审核图片（管理员）")
    public BaseResponse<Boolean> reviewImage(@RequestBody ImageReviewRequest reviewRequest) {
        return ResultUtils.success(imageService.reviewImage(
                reviewRequest.getId(), reviewRequest.getReviewStatus(), reviewRequest.getReviewMessage(),
                reviewRequest.getResetReviewParams()));
    }

    /**
     * 主页筛选项：分类与标签候选（登录即可访问）。
     *
     * <p>无参数、无分页 —— 筛选项就是"当前图库里有哪几类、哪些标签"，是一个全集。
     */
    @PostMapping("/options")
    @Operation(summary = "主页筛选项：分类与标签候选（登录用户）")
    public BaseResponse<ImageOptionsVO> listOptions() {
        return ResultUtils.success(imageService.listOptions());
    }

    /**
     * 编辑图片（名称 / 简介 / 分类 / 标签）—— 本人或管理员。
     *
     * <p>id 放在请求体里：因为这是"多字段更新"，id 和要改的字段同属一次提交。
     */
    @PostMapping("/update")
    @Operation(summary = "编辑图片（本人或管理员）")
    public BaseResponse<Boolean> updateImage(@RequestBody ImageUpdateRequest imageUpdateRequest) {
        return ResultUtils.success(imageService.updateImage(imageUpdateRequest));
    }

    /**
     * 删除图片（逻辑删除）—— 本人或管理员。
     *
     * <p>id 放在 URL：只删一条、只认一个 id，没有别的参数。
     */
    @PostMapping("/delete")
    @Operation(summary = "删除图片（本人或管理员）")
    public BaseResponse<Boolean> deleteImage(@RequestParam String id) {
        return ResultUtils.success(imageService.deleteImage(id));
    }

    /**
     * 生成分享短链（本人或管理员）。
     *
     * <p>只返回分享码；完整短链由前端拼（{@code origin + "/share/" + code}）——
     * 链接格式天然属于前端的路由知识，放后端反而要多一份 base-url 配置。
     */
    @PostMapping("/share")
    @Operation(summary = "生成分享短链（本人或管理员）")
    public BaseResponse<String> shareImage(@RequestParam String id) {
        return ResultUtils.success(imageService.shareImage(id));
    }

    /**
     * 按分享码查看图片（<b>免登录</b> —— 已加入 SaTokenConfig 白名单）。
     *
     * <p>这是本模块第二个"仅登录可选"的入口：游客凭码看已通过的图；带登录态时本人/管理员
     * 还能看自己未通过的图。
     */
    @PostMapping("/share/view")
    @Operation(summary = "按分享码查看图片（免登录）")
    public BaseResponse<ImageVO> getSharedImage(@RequestParam String shareCode) {
        return ResultUtils.success(imageService.getSharedImage(shareCode));
    }
}
