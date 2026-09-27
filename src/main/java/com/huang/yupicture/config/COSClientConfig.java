package com.huang.yupicture.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 腾讯云 COS 客户端配置。
 *
 * <p>配置项从 {@code application-local.yml} 读（该文件已被 git 忽略，密钥不会进版本库）：
 * <pre>
 * cos:
 *   client:
 *     access-key: ...
 *     secret-key: ...
 *     region: ap-shanghai
 *     bucket: yu-picture-cloud-1300000000
 * </pre>
 *
 * <p><b>{@code @ConditionalOnProperty} 是刻意的：</b>没配 access-key 时，这个类的
 * {@code cosClient} bean 根本不会被创建 —— 好处是<b>本地还没配 COS 密钥时，整个应用照常启动、
 * 其他模块的测试照常跑</b>；代价是"上传图片"这个能力在那时不可用（用到的地方会因注入失败而报错）。
 * 对学习阶段这是划算的取舍：内部服务不通不该让整个项目瘫掉。
 *
 * <p><b>密钥不落日志：</b>本类不打印任何字段值，也不重写 toString（{@code @Data} 生成的
 * toString 会把密钥拼进去 —— 所以<b>不要对本类的对象调用 toString / 打日志</b>）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@ConditionalOnProperty(prefix = "cos.client", name = "access-key")
public class COSClientConfig {

    /** SecretId（不是敏感信息，但也没必要外传） */
    private String accessKey;

    /** SecretKey —— 敏感信息，绝不出现在日志 / 响应 / 代码里 */
    private String secretKey;

    /** 地域，如 ap-shanghai。必须与建桶时选的地域一致，否则报 NoSuchBucket */
    private String region;

    /** 桶名完整形式：{名字}-{APPID}，如 yu-picture-cloud-1300000000 */
    private String bucket;

    /**
     * COS 客户端（单例）。
     *
     * <p><b>为什么不每次用完 new 一个：</b>COSClient 内部维护 HTTP 连接池，
     * 每次新建等于每次重建连接池 —— 频繁上传 / 删除时连接建立的开销会盖过业务本身。
     * 交给 Spring 管理成单例（默认 scope），随应用生命周期存在。
     *
     * <p>{@code destroyMethod = "shutdown"}：应用关闭时归还连接池资源。
     * 不写这个，进程退出时可能留下未释放的 HTTP 连接（本地开发感觉不到，压测时才会咬人）。
     */
    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        COSCredentials credentials = new BasicCOSCredentials(accessKey, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        return new COSClient(credentials, clientConfig);
    }
}
