package com.huang.yupicture.manager;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COS 连通性测试 —— 一次跑通就能确认「SecretId / SecretKey / region / bucket」四项配置全对。
 *
 * <p><b>为什么先写这个测试：</b>密钥配错的表现是"上传接口报 50000"，而从错误码里看不出
 * 是密钥错、地域错还是桶名错。这个测试把"配置能不能用"单独拎出来验证，
 * 后面写上传接口时就不用一边调业务一边猜配置。
 *
 * <p><b>没配密钥时自动跳过</b>（{@code Assumptions.assumeTrue}）：
 * 这样在你还没配好 COS 的阶段，整套测试依然是绿的，不会因为一个未完成的配置全红。
 * 配好之后它会自动开始真跑。
 *
 * <p><b>它会真的上传和删除一个 1x1 的 PNG</b>（几百字节，费用可忽略）——
 * 只有真的走一遍网络，才能证明密钥有效、桶存在、权限对。
 * 测试自己生成图片，所以不依赖任何本地文件，换台机器也能跑。
 */
@SpringBootTest
class CosManagerTest {

    /** 没配 cos.client.access-key 时，CosManager 这个 bean 根本不会创建，注入得到 null */
    @Autowired(required = false)
    private CosManager cosManager;

    @Test
    void cosConfigShouldBeUsable() throws Exception {
        Assumptions.assumeTrue(cosManager != null,
                "未配置 cos.client.access-key，跳过 COS 连通性测试（配好 application-local.yml 后自动生效）");

        // ---------- 1. 造一张 1x1 的 PNG ----------
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] pngBytes = baos.toByteArray();
        MockMultipartFile file = new MockMultipartFile(
                "file", "connectivity-test.png", "image/png", pngBytes);

        // ---------- 2. 上传 ----------
        String key = cosManager.upload(file, "png");
        assertNotNull(key, "上传必须返回对象键");
        assertTrue(key.startsWith("public/"), "对象键应带 public/ 前缀");

        String url = cosManager.buildUrl(key);
        assertTrue(url.startsWith("https://"), "访问地址必须是 https");

        String deletedKey = null;
        try {
            // ---------- 3. 真去公网拉一次 ----------
            // 这一步是"桶配置对不对"的唯一硬证据：只有桶名、地域、访问权限（公有读）全对，
            // 这个不带任何签名的 GET 才会返回 200
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(),
                    "公网访问失败：检查桶的地域 / 名称是否与配置一致，以及访问权限是否设为「公有读私有写」。URL=" + url);

            // ---------- 4. 从 URL 反查 key，验证解析逻辑 ----------
            assertEquals(key, cosManager.getKeyFromUrl(url), "从 url 反查对象键应能还原");

            // ---------- 5. 删除 ----------
            cosManager.delete(key);
            deletedKey = key;

            // ---------- 6. 确认真的删掉了 ----------
            HttpResponse<String> afterDelete = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, afterDelete.statusCode(), "删除后不应再能访问到该对象");
        } finally {
            // 兜底清理：断言失败时也别在桶里留垃圾文件（delete 幂等，重复删不报错）
            if (deletedKey == null) {
                try {
                    cosManager.delete(key);
                } catch (Exception ignore) {
                    // 清理失败不掩盖真正的测试失败
                }
            }
        }
    }
}
