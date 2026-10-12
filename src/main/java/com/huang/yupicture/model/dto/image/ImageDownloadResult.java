package com.huang.yupicture.model.dto.image;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * 图片下载结果 —— Service 返回"字节 + 怎么保存"，Controller 负责拼成 HTTP 响应。
 *
 * <p><b>为什么不用 Map / 三个出参：</b>三个相关值（字节、文件名、Content-Type）是
 * 同一次下载的产物，捆成一个对象语义才完整 —— 和"上传返回 ImageVO"是同一个思想。
 *
 * <p><b>为什么不直接返回 {@code ResponseEntity}：</b>那是 HTTP 层的类型，
 * Service 返回它就等于 Service 开始拼 HTTP 响应 —— 越层了。Service 说"业务上
 * 该下载什么"，Controller 决定"HTTP 上怎么表达"。
 */
@Data
@AllArgsConstructor
public class ImageDownloadResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件字节（COS 原样内容，未做任何转换） */
    private byte[] bytes;

    /**
     * 建议的保存文件名（Content-Disposition 里用）。
     * 取图片名称补上真实后缀 —— 用户看到的是「西湖日落.jpg」而不是一串 UUID。
     */
    private String fileName;

    /** Content-Type（按存储时的格式给，浏览器才知道这是什么文件） */
    private String contentType;
}
