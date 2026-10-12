package com.huang.yupicture.manager;

import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.config.COSClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * 对象存储（COS）操作封装。
 *
 * <p><b>为什么单独一层 manager，而不是在 Service 里直接调 COS SDK：</b>
 * Service 的职责是"业务规则"（谁能传、传多大、存什么元信息），
 * 而"怎么跟 COS 打交道"是另一件事 —— 将来换成阿里云 OSS、MinIO 或自建存储，
 * 要改的只有这个类，Service 一行都不用动。<b>隔离的是变化点，不是代码量。</b>
 *
 * <p>本类只负责"把文件放上去 / 删掉 / 拼地址"，不做任何权限和参数校验 —— 那是 Service 的事。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cos.client", name = "access-key")
public class CosManager {

    /**
     * 对象键统一前缀。
     *
     * <p>相当于"云端目录"。加上前缀的好处：将来要往同一个桶里放别的东西
     * （比如用户头像、导出的报表），可以在前缀上区分，删错目录的风险小很多。
     */
    private static final String KEY_PREFIX = "public";

    @Resource
    private COSClient cosClient;

    @Resource
    private COSClientConfig cosClientConfig;

    /**
     * 上传文件，返回对象键（key）。
     *
     * @param file   上传的文件
     * @param suffix 文件后缀（已由上层校验过白名单，不含点号）
     * @return 对象键，如 {@code public/3f2a...c9.jpg}
     */
    public String upload(MultipartFile file, String suffix) {
        try {
            // 委托给 byte[] 版本：上传逻辑只存在一份（原文件读成字节后走同一条路）
            return upload(file.getBytes(), suffix, file.getContentType());
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        }
    }

    /**
     * 上传「已经过业务处理的内容」（如压缩后的图片字节），返回对象键。
     *
     * <p>与 {@link #upload(MultipartFile, String)} 的区别：内容以 byte[] 给定，
     * 不再从 MultipartFile 现读 —— <b>大小与内容类型必须由调用方如实传入</b>，
     * 因为压缩后的字节和原始请求里的文件已经不是同一份东西
     * （拿 file.getSize() 当 Content-Length 会把 COS 上的对象截断/补零）。
     *
     * @param bytes        要上传的内容（最终版本，压缩后）
     * @param suffix       文件后缀（小写，已校验）
     * @param contentType  浏览器访问时的渲染类型，如 {@code image/jpeg}；可为 null
     * @return 对象键，如 {@code public/3f2a...c9.jpg}
     */
    public String upload(byte[] bytes, String suffix, String contentType) {
        // 随机生成 key，委托给"带 key"的核心版本：上传逻辑只存在一份
        return upload(bytes, suffix, contentType, buildKey(suffix));
    }

    /**
     * 上传的核心实现：key 由调用方给定。
     *
     * <p>原图走随机 UUID key（见 {@link #buildKey}），缩略图走推导 key（紧跟原图）——
     * 两条路共用这里，落对象、记日志、异常翻译只写一遍。
     */
    private String upload(byte[] bytes, String suffix, String contentType, String key) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        // 存对 Content-Type，浏览器访问时才知道怎么渲染（否则可能被当成下载）
        metadata.setContentType(contentType);

        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            PutObjectRequest request = new PutObjectRequest(
                    cosClientConfig.getBucket(), key, inputStream, metadata);
            cosClient.putObject(request);
            log.info("文件上传成功：key={}, size={}字节", key, bytes.length);
            return key;
        } catch (Exception e) {
            // COS SDK 抛 CosClientException（网络/凭证问题）或 CosServiceException（桶不存在/无权限）
            // 不把原始信息透给前端：里面可能带 SecretId 片段、桶路径等内部信息，
            // 但必须完整记进日志，否则线上排查时什么都看不到
            log.error("上传文件到 COS 失败：key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        }
    }

    /**
     * 上传缩略图，对象键由原图 key 推导。
     *
     * <p><b>为什么库里不加 thumbnailUrl 列，靠推导：</b>缩略图和原图是同一次上传的产物，
     * key 规律固定为 {@code xxx.jpg -> xxx_thumb.jpg}（{@link #buildThumbnailKey}）。
     * 能推导出来的信息不落库 —— 少一列就少一份"两列对不上"的风险
     * （列会被人改、会被忘更新，推导规则不会）。代价是规则必须集中在这一处，
     * 改布局时只改这里 + VO 拼装处。
     *
     * @param thumbnailBytes 缩略图字节（调用方已判空，null 不会传到这里）
     * @param originalKey    原图的对象键
     * @return 缩略图对象键
     */
    public String uploadThumbnail(byte[] thumbnailBytes, String originalKey) {
        String thumbKey = buildThumbnailKey(originalKey);
        // 缩略图保持原图格式（png 缩略图还是 png），Content-Type 按后缀给
        String contentType = thumbKey.endsWith(".png") ? "image/png" : "image/jpeg";
        return upload(thumbnailBytes, suffixOf(thumbKey), contentType, thumbKey);
    }

    /**
     * 由原图对象键推导缩略图对象键：{@code public/abc.jpg -> public/abc_thumb.jpg}。
     * <b>包级私有</b>（key 推导是本类内部约定，不外泄）；Service 想删缩略图走
     * {@link #deleteThumbnail(String)}，想拼 URL 走 {@link #buildThumbnailUrl(String)}。
     */
    String buildThumbnailKey(String originalKey) {
        int dot = originalKey.lastIndexOf('.');
        return originalKey.substring(0, dot) + "_thumb" + originalKey.substring(dot);
    }

    /**
     * 删除某张原图对应的缩略图（删图 / 上传回滚时用）。
     *
     * <p>推导规则收敛在这一处：Service 只说"删这张图的缩略图"，
     * 不需要知道 {@code _thumb} 命名约定 —— 将来改布局（比如缩略图挪独立前缀）
     * 只动本类。
     */
    public void deleteThumbnail(String originalKey) {
        delete(buildThumbnailKey(originalKey));
    }

    /**
     * 由原图公网 URL 推导缩略图 URL（VO 拼装用），推导规则与 {@link #buildThumbnailKey} 同源。
     *
     * <p>⚠️ 已知局限：本功能上线前上传的旧图没有缩略图文件，推导出的 URL 会 404 ——
     * 前端 img 标签需要 @error 回退原图（个人项目存量图少，不值得写回溯迁移）。
     */
    public String buildThumbnailUrl(String originalUrl) {
        int dot = originalUrl.lastIndexOf('.');
        int slash = originalUrl.lastIndexOf('/');
        // 没有后缀 / 点号在路径分隔符之前（如域名里的点）→ 不像合法的图片 URL，原样返回
        if (dot < 0 || dot < slash) {
            return originalUrl;
        }
        return originalUrl.substring(0, dot) + "_thumb" + originalUrl.substring(dot);
    }

    /** 从对象键取小写后缀（不含点号）。键由本类自己生成，格式可控 */
    private static String suffixOf(String key) {
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 上传 WebP 副本，对象键由原图 key 推导（{@code public/abc.jpg -> public/abc_webp.webp}）。
     * 推导思想与缩略图相同：能确定性推导的信息不落库。
     *
     * <p>Content-Type 用 {@code image/webp}：浏览器按它渲染，写错会被当成下载。
     */
    public String uploadWebp(byte[] webpBytes, String originalKey) {
        String webpKey = buildWebpKey(originalKey);
        return upload(webpBytes, "webp", "image/webp", webpKey);
    }

    /**
     * 由原图对象键推导 WebP 副本对象键。包级私有 —— 命名约定不外泄，
     * Service 走 {@link #deleteWebp(String)}，VO 拼 URL 走 {@link #buildWebpUrl(String)}。
     */
    String buildWebpKey(String originalKey) {
        int dot = originalKey.lastIndexOf('.');
        return originalKey.substring(0, dot) + "_webp.webp";
    }

    /** 删除某张原图对应的 WebP 副本（删图 / 上传回滚时用），语义同 {@link #deleteThumbnail(String)} */
    public void deleteWebp(String originalKey) {
        delete(buildWebpKey(originalKey));
    }

    /**
     * 由原图公网 URL 推导 WebP 副本 URL（VO 拼装用）。
     *
     * <p>⚠️ 与缩略图同样的已知局限：功能上线前的存量图没有 webp 副本，会 404 ——
     * 前端按需加载、失败回退原图即可。
     */
    public String buildWebpUrl(String originalUrl) {
        int dot = originalUrl.lastIndexOf('.');
        int slash = originalUrl.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return originalUrl;
        }
        return originalUrl.substring(0, dot) + "_webp.webp";
    }

    /**
     * 生成对象键。
     *
     * <p><b>为什么用 UUID 而不是原始文件名：</b>
     * <ol>
     *   <li><b>防重名覆盖</b>：两个用户都传 {@code 自拍.jpg}，后者会覆盖前者</li>
     *   <li><b>防路径穿越</b>：恶意文件名形如 {@code ../../other.jpg}（"../" 在对象键里是
     *       合法的路径字符，在有些存储实现上能跳出当前"目录"）</li>
     *   <li><b>防 URL 事故</b>：中文 / 空格 / 特殊字符在 URL 里要转义，转义不一致就会出现
     *       "数据库存的地址和实际对象对不上"</li>
     * </ol>
     * 原始文件名照样有用 —— 存进数据库的 {@code name} 字段，展示给用户看。
     * <b>"给用户看的名字"和"存储路径"本来就是两回事。</b>
     */
    private String buildKey(String suffix) {
        return KEY_PREFIX + "/" + UUID.randomUUID().toString().replace("-", "") + "." + suffix;
    }

    /**
     * 拼出公网访问地址。
     *
     * <p>格式固定为 {@code https://{bucket}.cos.{region}.myqcloud.com/{key}}，
     * 这是腾讯云 COS 的默认访问域名规则。<b>要求桶的访问权限是"公有读私有写"</b> ——
     * 读走公网不需要签名（所以 {@code <img src>} 能直接打开），写必须带密钥。
     */
    public String buildUrl(String key) {
        return "https://" + cosClientConfig.getBucket()
                + ".cos." + cosClientConfig.getRegion()
                + ".myqcloud.com/" + key;
    }

    /**
     * 从公网地址反查对象键（删文件时用）。
     *
     * <p><b>⚠️ 这里有个已知的脆弱点：</b>数据库只存了 url，删文件时得从 url 里把 key 抠出来。
     * 一旦访问域名规则变了（换自定义域名、换存储商），这个解析就失效。
     * 更稳的做法是数据库额外存一列 {@code storageKey}，让"地址"和"存储位置"解耦 ——
     * 现在先跟课程保持一致用解析，等真换域名时再补这一列。
     */
    public String getKeyFromUrl(String url) {
        if (url == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片地址为空，无法定位存储文件");
        }
        int index = url.indexOf(".myqcloud.com/");
        if (index < 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片地址格式不正确，无法定位存储文件");
        }
        return url.substring(index + ".myqcloud.com/".length());
    }

    /**
     * 下载对象内容。
     *
     * <p><b>为什么需要这个方法（图明明是公有读的）：</b>浏览器里「打开 URL」是预览，
     * <b>{@code <a download>} 对跨域链接无效</b>（COS 域名 ≠ 站点域名，浏览器会忽略
     * download 属性直接内联显示）。要触发「保存到磁盘」，响应必须从同源带
     * {@code Content-Disposition: attachment} 回来 —— 所以需要后端读 COS 转发一遍。
     *
     * <p>单图 ≤ 5MB（上传侧的上限），整读进内存没有压力，与 compress / thumbnail
     * 的内存风格一致。
     *
     * @param key 对象键
     * @return 文件字节
     */
    public byte[] download(String key) {
        try (InputStream in = cosClient.getObject(cosClientConfig.getBucket(), key).getObjectContent();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            // 与 upload/delete 同一处置：细节进日志（可能是 key 不存在 / 无权限 / 网络断），
            // 对前端只说"下载失败"，不透 COS 内部信息
            log.error("从 COS 下载文件失败：key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片下载失败");
        }
    }

    /**
     * 删除对象。
     *
     * <p><b>COS 删除不存在的对象不会报错</b>（幂等，跟 S3 一样）。
     * 这一点对"先删文件、再删数据库记录"的顺序很重要：
     * 万一流程中断重试，第二次删同一个 key 依然成功，不会卡死。
     */
    public void delete(String key) {
        try {
            cosClient.deleteObject(cosClientConfig.getBucket(), key);
            log.info("文件删除成功：key={}", key);
        } catch (Exception e) {
            log.error("删除 COS 文件失败：key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片文件删除失败");
        }
    }
}
