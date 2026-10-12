package com.huang.yupicture.utils;

import com.huang.yupicture.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UrlPictureUtils#checkUrl(String)} 的单元测试 —— URL 传图的 SSRF 防线。
 *
 * <p>这个类是 URL 传图功能里唯一能脱离网络直测的部分（拉取要走真 HTTP），
 * 所以 SSRF 的所有拒绝分支都在这里覆盖。每个用例对应一种真实攻击形态，
 * 不是凑数的"格式错误"——见各方法注释里的攻击说明。
 */
class UrlPictureUtilsTest {

    @Test
    void publicHttpUrlShouldPass() {
        // 正常路径：公网 http/https 都放行（后面还有魔数校验兜内容）。
        // ⚠️ 必须用真实可解析的公网域名 —— checkUrl 会做 DNS 解析（查所有记录防混拼），
        // cdn.example.com 这类不存在的子域在"解析失败"分支就被拒了，走不到放行分支
        assertDoesNotThrow(() -> UrlPictureUtils.checkUrl("https://example.com/pic.jpg"));
        assertDoesNotThrow(() -> UrlPictureUtils.checkUrl("http://example.com/a/b/c.png?token=x"));
    }

    @Test
    void loopbackShouldBeRejected() {
        // 攻击形态①：打自己 —— localhost 上跑着自己的后端（8123）和 Redis（6379），
        // 服务端视角的"localhost"是攻击者的跳板
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://localhost:8123/api/image/delete?id=1"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://127.0.0.1:6379/"));
    }

    @Test
    void privateRangeShouldBeRejected() {
        // 攻击形态②：探内网 —— 192.168/10./172.16 段的服务通常"默认只有内网能访问"
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://192.168.1.1/admin"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://10.0.0.5:8080/actuator/env"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://172.16.0.9/"));
    }

    @Test
    void linkLocalShouldBeRejected() {
        // 攻击形态③：云元数据 —— 云主机的临时凭据都在 169.254.169.254，
        // 拿到 = 整台机器失守。这条是云上部署的保命检查
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void nonHttpSchemeShouldBeRejected() {
        // 攻击形态④：协议走私 —— file: 读服务器本地文件，ftp/gopher 是历史漏洞通道
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("file:///etc/passwd"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("ftp://example.com/pic.jpg"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("gopher://127.0.0.1:6379/_INFO"));
    }

    @Test
    void malformedUrlShouldBeRejected() {
        // 输入卫生：不是 URL 的字符串、没有主机名的、解析不了的域名
        assertThrows(BusinessException.class, () -> UrlPictureUtils.checkUrl(""));
        assertThrows(BusinessException.class, () -> UrlPictureUtils.checkUrl("   "));
        assertThrows(BusinessException.class, () -> UrlPictureUtils.checkUrl("not-a-url"));
        assertThrows(BusinessException.class, () -> UrlPictureUtils.checkUrl("https://"));
        assertThrows(BusinessException.class,
                () -> UrlPictureUtils.checkUrl("https://this-domain-definitely-not-exist-abcxyz.invalid/a.jpg"));
    }

    @Test
    void returnedUriShouldBeNormalized() {
        // 返回的 URI 是后续拉取的输入 —— 必须保留完整路径和参数（token 型图床靠它鉴权）
        URI uri = UrlPictureUtils.checkUrl("https://example.com/p/abc.jpg?token=xyz");
        assertTrue(uri.getPath().endsWith("/abc.jpg"));
        assertTrue(uri.getQuery().contains("token=xyz"));
    }
}
