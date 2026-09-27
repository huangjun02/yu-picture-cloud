package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.manager.CosManager;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 上传接口的测试。
 *
 * <p><b>分两组，用不同的前提：</b>
 * <ul>
 *   <li><b>校验用例（前 5 个）</b>：全部在上传 COS **之前**就被拒绝，所以不需要 COS 配置，
 *       任何环境都能跑。这组才是防线的证明 —— 攻击面从来不在"正常上传"上</li>
 *   <li><b>成功用例（最后一个）</b>：需要真的连 COS，用 {@code Assumptions} 跳过 ——
 *       没配密钥时测试不会红，配好后自动开始真跑</li>
 * </ul>
 *
 * <p>注意 MockMvc 下的一个局限：它绕过了 Servlet 容器的 multipart 解析，
 * 所以 {@code spring.servlet.multipart.max-file-size} 那道框架层防线在这里测不到，
 * 测到的是业务层的 5MB 校验（{@code ImageUtils}）。两道防线都真实存在，只是测试手段够不着前一道。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageUploadTest {

    private static final String RAW_PASSWORD = "12345678";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private ImageService imageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /** 没配 cos.client.* 时这个 bean 不存在，注入得到 null */
    @Autowired(required = false)
    private CosManager cosManager;

    @AfterEach
    void clearSaTokenSessions() {
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private Cookie loginAs(String account) throws Exception {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("上传测试用户");
        user.setUserRole("user");
        userService.save(user);

        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertNotNull(cookie, "登录必须下发 satoken cookie");
        return cookie;
    }

    /** 生成一张真实可解码的 PNG（指定宽高，用来验证宽高比计算） */
    private MockMultipartFile realPng(String filename, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return new MockMultipartFile("file", filename, "image/png", baos.toByteArray());
    }

    // ==================== 一、校验防线（不依赖 COS） ====================

    @Test
    void uploadShouldRequireLogin() throws Exception {
        MockMultipartFile file = realPng("a.png", 1, 1);
        // 不带 cookie：被拦截器拦成 40100，根本进不到上传逻辑
        mockMvc.perform(multipart("/image/upload").file(file))
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void uploadShouldRejectEmptyFile() throws Exception {
        Cookie cookie = loginAs("img_u_empty");
        MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/image/upload").file(empty).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void uploadShouldRejectUnsupportedSuffix() throws Exception {
        Cookie cookie = loginAs("img_u_suffix");
        MockMultipartFile txt = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world, definitely not an image".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/image/upload").file(txt).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void uploadShouldRejectFileWithoutSuffix() throws Exception {
        Cookie cookie = loginAs("img_u_nosuffix");
        MockMultipartFile noSuffix = new MockMultipartFile("file", "noextension", "image/png",
                "123456789012".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/image/upload").file(noSuffix).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    /**
     * <b>本类最重要的一条：内容与后缀不一致必须被拒。</b>
     * 文件名改成 .jpg 是零成本的，只信后缀等于没有校验。
     */
    @Test
    void uploadShouldRejectFakeImageByContent() throws Exception {
        Cookie cookie = loginAs("img_u_fake");
        // 内容是 DOS/Windows 可执行文件头 "MZ"，名字却叫 girl.jpg
        byte[] fakeContent = "MZ\u0090\u0000this is actually an executable, not an image at all"
                .getBytes(StandardCharsets.ISO_8859_1);
        MockMultipartFile fake = new MockMultipartFile("file", "girl.jpg", "image/jpeg", fakeContent);

        mockMvc.perform(multipart("/image/upload").file(fake).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void uploadShouldRejectOversizedFile() throws Exception {
        Cookie cookie = loginAs("img_u_big");
        // 6MB > 5MB 上限：内容是不是真图片无所谓，大小校验在内容校验之前
        byte[] big = new byte[6 * 1024 * 1024];
        MockMultipartFile huge = new MockMultipartFile("file", "big.png", "image/png", big);

        mockMvc.perform(multipart("/image/upload").file(huge).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    // ==================== 二、成功链路（需要 COS） ====================

    @Test
    void uploadShouldSucceedAndRecordMetadata() throws Exception {
        Assumptions.assumeTrue(cosManager != null,
                "未配置 cos.client.access-key，跳过真实上传用例（配好后自动生效）");

        Cookie cookie = loginAs("img_u_ok");
        long userId = userService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUserAccount, "img_u_ok")).getId();

        MockMultipartFile file = realPng("西湖日落.png", 80, 40);

        String uploadedKey = null;
        try {
            MvcResult result = mockMvc.perform(multipart("/image/upload").file(file).cookie(cookie))
                    .andExpect(jsonPath("$.code").value(0))
                    .andReturn();
            JsonNode data = objectMapper.readTree(
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");

            // 校验落库的元信息：名称去掉了后缀、格式是小写、宽高比算对了
            assertEquals("西湖日落", data.path("name").asText(), "不传 name 时应取原始文件名去后缀");
            assertEquals("png", data.path("picFormat").asText());
            assertEquals(80, data.path("picWidth").asInt());
            assertEquals(40, data.path("picHeight").asInt());
            assertEquals(2.0, data.path("picScale").asDouble(), 0.0001, "宽高比 = 80/40");
            assertEquals(String.valueOf(userId), data.path("userId").asText(), "归属必须是上传者本人");

            // url 必须能公网访问（这一步同时证明了桶的「公有读」配置正确）
            String url = data.path("url").asText();
            assertTrue(url.startsWith("https://"), "应返回 https 地址");
            uploadedKey = cosManager.getKeyFromUrl(url);

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "上传后的图片应能直接通过 URL 访问");

            // 数据库里确实有这条记录
            String imageId = data.path("id").asText();
            Image saved = imageService.getById(Long.parseLong(imageId));
            assertNotNull(saved, "数据库里应有对应记录");
        } finally {
            // @Transactional 能回滚数据库，但回滚不了已经传到 COS 的文件 —— 必须手动清掉
            if (uploadedKey != null) {
                try {
                    cosManager.delete(uploadedKey);
                } catch (Exception ignore) {
                    // 清理失败不掩盖测试结果
                }
            }
        }
    }

    /**
     * 删除图片时，<b>存储上的文件也必须一起清掉</b>。
     *
     * <p>只删数据库记录是个很隐蔽的 bug：功能"看起来正常"（列表里没了、点不进详情），
     * 但 COS 里那些文件永远留着占容量、持续计费，而且没有任何告警 ——
     * 属于那种"半年后看账单才发现"的问题。
     */
    @Test
    void deleteShouldAlsoRemoveCosFile() throws Exception {
        Assumptions.assumeTrue(cosManager != null,
                "未配置 cos.client.access-key，跳过（配好后自动生效）");

        Cookie cookie = loginAs("img_u_del");
        MockMultipartFile file = realPng("待删的图.png", 4, 4);

        // 先上传一张，拿到 id 和 url
        MvcResult uploadResult = mockMvc.perform(multipart("/image/upload").file(file).cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(
                uploadResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        String imageId = data.path("id").asText();
        String url = data.path("url").asText();

        // 上传后应该能访问到
        assertEquals(200, httpGetStatus(url), "上传后图片应可公网访问");

        // 删除
        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", imageId))
                .andExpect(jsonPath("$.code").value(0));

        // ① 数据库记录没了（逻辑删除，走 MP 查不到）
        assertNull(imageService.getById(Long.parseLong(imageId)));

        // ② 本用例的重点：存储上的文件也没了
        assertNotEquals(200, httpGetStatus(url), "删除图片后，存储上的文件也必须被清掉");
    }

    /** 匿名 GET 一个 URL，返回状态码（用于验证图片是否还能被公网访问） */
    private int httpGetStatus(String url) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
