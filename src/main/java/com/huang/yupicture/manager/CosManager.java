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

import java.io.IOException;
import java.io.InputStream;
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
        String key = buildKey(suffix);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        // 存对 Content-Type，浏览器访问时才知道怎么渲染（否则可能被当成下载）
        metadata.setContentType(file.getContentType());

        try (InputStream inputStream = file.getInputStream()) {
            PutObjectRequest request = new PutObjectRequest(
                    cosClientConfig.getBucket(), key, inputStream, metadata);
            cosClient.putObject(request);
            log.info("文件上传成功：key={}, size={}字节", key, file.getSize());
            return key;
        } catch (IOException e) {
            // 读不到上传流：多半是请求提前中断（客户端断开 / 超时）
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        } catch (Exception e) {
            // COS SDK 抛 CosClientException（网络/凭证问题）或 CosServiceException（桶不存在/无权限）
            // 不把原始信息透给前端：里面可能带 SecretId 片段、桶路径等内部信息，
            // 但必须完整记进日志，否则线上排查时什么都看不到
            log.error("上传文件到 COS 失败：key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        }
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
