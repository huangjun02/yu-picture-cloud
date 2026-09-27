package com.huang.yupicture.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huang.yupicture.common.BaseResponse;
import com.huang.yupicture.common.ResultUtils;
import com.huang.yupicture.model.dto.image.ImageQueryRequest;
import com.huang.yupicture.model.dto.image.ImageUpdateRequest;
import com.huang.yupicture.model.vo.ImageVO;
import com.huang.yupicture.service.ImageService;
import jakarta.annotation.Resource;
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
 */
@RestController
@RequestMapping("/image")
public class ImageController {

    @Resource
    private ImageService imageService;

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
    public BaseResponse<Page<ImageVO>> listImageByPage(@RequestBody ImageQueryRequest imageQueryRequest) {
        return ResultUtils.success(imageService.listImageByPage(imageQueryRequest));
    }

    /**
     * 编辑图片（名称 / 简介 / 分类 / 标签）—— 本人或管理员。
     *
     * <p>id 放在请求体里：因为这是"多字段更新"，id 和要改的字段同属一次提交。
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateImage(@RequestBody ImageUpdateRequest imageUpdateRequest) {
        return ResultUtils.success(imageService.updateImage(imageUpdateRequest));
    }

    /**
     * 删除图片（逻辑删除）—— 本人或管理员。
     *
     * <p>id 放在 URL：只删一条、只认一个 id，没有别的参数。
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteImage(@RequestParam String id) {
        return ResultUtils.success(imageService.deleteImage(id));
    }
}
