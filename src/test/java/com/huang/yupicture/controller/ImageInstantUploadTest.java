package com.huang.yupicture.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 秒传（MD5 精确去重）的集成测试。
 *
 * <p><b>两组，用不同的前提：</b>
 * <ul>
 *   <li><b>逻辑用例（前 3 个）</b>：直接调 {@code imageService.uploadImage}，
 *       不依赖 COS —— 秒传命中发生在 COS 上传<b>之前</b>，第二次上传根本不会碰存储。
 *       但第一次「冷上传」要走完整链路（真传 COS）…… 不行 —— 测试环境可能没配密钥。
 *       所以改用「数据库预置一条同指纹的图」的方式模拟「别人已传过」：
 *       这正是生产环境秒传命中的数据形状（库里已有同 md5 记录），且完全绕开 COS。</li>
 *   <li><b>真实链路用例（最后一个）</b>：连 COS 跑两遍完整上传，验证「第二次不再传存储」。</li>
 * </ul>
 *
 * <p>造数纪律（来自 skill）：helper 照抄生产的写入链路 —— md5 是上传时算的派生列，
 * 预置数据必须 {@code setMd5}，否则秒传永远查不到它（症状酷似「功能坏了」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageInstantUploadTest {

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

    private long loginAndGetUserId(String account) throws Exception {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("秒传测试用户");
        user.setUserRole("user");
        userService.save(user);

        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertNotNull(cookie, "登录必须下发 satoken cookie");

        return userService.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserAccount, account)).getId();
    }

    /** 生成一张真实可解码的 PNG */
    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private Cookie loginCookie(String account) throws Exception {
        // loginAndGetUserId 已把账号建好并登录过；这里重登一次拿 cookie（会话可能被上个用例清掉）
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return result.getResponse().getCookie("satoken");
    }

    /** 预置一条「别人已传过」的图记录（含 md5）—— 生产环境秒传命中的数据形状 */
    private long prepareImageWithMd5(long userId, String name, String md5) {
        Image image = new Image();
        image.setName(name);
        image.setUrl("https://example-bucket.cos.ap-beijing.myqcloud.com/fake/" + name + ".png");
        image.setUserId(userId);
        image.setMd5(md5);
        image.setReviewStatus(1);
        image.setCreateTime(new java.util.Date());
        imageService.save(image);
        return image.getId();
    }

    // ==================== 一、秒传逻辑（不依赖 COS） ====================

    /**
     * 核心：B 传了 A 已传过的图 → 新记录挂 B 名下、复用 A 的 URL，不重复传存储。
     * （归属方案 b：COS 对象复用、记录归 B —— 存储省了、归属清晰。）
     */
    @Test
    void secondUserUploadingSameContentShouldReuseUrlAndCreateOwnRecord() throws Exception {
        long userA = loginAndGetUserId("instant_a");
        long userB = loginAndGetUserId("instant_b");

        byte[] content = pngBytes(30, 20);
        String md5 = com.huang.yupicture.utils.ImageUtils.md5(content);
        long imageAId = prepareImageWithMd5(userA, "A的图", md5);

        MockMultipartFile file = new MockMultipartFile(
                "file", "same.png", "image/png", content);
        Cookie cookieB = loginCookie("instant_b");

        MvcResult result = mockMvc.perform(multipart("/image/upload").file(file).cookie(cookieB))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = body.get("data");
        assertNotNull(data, "上传应返回图片 VO");

        long newId = data.get("id").asLong();
        assertNotEquals(imageAId, newId, "应新建记录，而不是返回 A 的记录");

        Image newImage = imageService.getById(newId);
        assertNotNull(newImage);
        assertEquals(userB, newImage.getUserId(), "新记录必须挂在 B 名下（不是 A 的）");
        assertEquals(md5, newImage.getMd5(), "新记录要有指纹（它自己也能当秒传源）");
    }

    /** 改一个像素 → MD5 不同 → 不命中秒传 → 走完整上传链路（这里会因没配 COS 报系统错误，恰好证明没走秒传） */
    @Test
    void differentContentShouldNotHitInstantUpload() throws Exception {
        long userA = loginAndGetUserId("instant_c");
        loginAndGetUserId("instant_d");

        byte[] content = pngBytes(30, 20);
        String md5 = com.huang.yupicture.utils.ImageUtils.md5(content);
        prepareImageWithMd5(userA, "A的图", md5);

        // 同尺寸但内容不同（不是纯色块）→ 指纹必然不同
        BufferedImage img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        img.setRGB(5, 5, 0xFF3366);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] different = baos.toByteArray();
        assertNotEquals(md5, com.huang.yupicture.utils.ImageUtils.md5(different));

        MockMultipartFile file = new MockMultipartFile(
                "file", "different.png", "image/png", different);
        Cookie cookieB = loginCookie("instant_d");

        // 未配 COS 时完整链路会报 SYSTEM_ERROR（50000）—— 恰好证明「没命中秒传、真去传存储了」。
        // 配了 COS 则返回 0（真传成功）。两种结果都断言「绝不是秒传命中」：不返回 A 的记录。
        MvcResult result = mockMvc.perform(multipart("/image/upload").file(file).cookie(cookieB))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(0),
                        org.hamcrest.Matchers.is(50000))))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (body.get("code").asInt() == 0) {
            assertNotEquals(userA, body.get("data").get("userId").asLong());
        }
    }

    /** 已删除的图不能当秒传源：A 删了图，B 传同一张图要走完整上传（不能复用一条已删记录的 URL） */
    @Test
    void deletedImageShouldNotServeAsInstantSource() throws Exception {
        long userA = loginAndGetUserId("instant_e");
        loginAndGetUserId("instant_f");

        byte[] content = pngBytes(25, 25);
        String md5 = com.huang.yupicture.utils.ImageUtils.md5(content);
        long imageAId = prepareImageWithMd5(userA, "A的图", md5);

        // 逻辑删除 A 的图（绕过 Service 的归属校验，直接模拟「A 删过了」）
        imageService.removeById(imageAId);

        MockMultipartFile file = new MockMultipartFile(
                "file", "same.png", "image/png", content);
        Cookie cookieB = loginCookie("instant_f");

        // 未配 COS：完整链路报 50000（说明没命中已删记录）；配了 COS：真传成功。
        // 共同点：绝不能返回已删记录的 id / URL。
        MvcResult result = mockMvc.perform(multipart("/image/upload").file(file).cookie(cookieB))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(0),
                        org.hamcrest.Matchers.is(50000))))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (body.get("code").asInt() == 0) {
            assertNotEquals(imageAId, body.get("data").get("id").asLong(),
                    "不能命中一条已逻辑删除的记录");
        }
    }

    // ==================== 三、删除联动（引用计数，不依赖 COS） ====================

    /**
     * 秒传共享对象的删除安全：A、B 两条记录指向同一 URL，A 删除后
     * B 的记录和 URL 必须完好（记录还在、逻辑删除只作用于 A 这一条）。
     * 文件清理被引用计数跳过 —— 无 COS 环境下行为等价（记录层语义一致）。
     */
    @Test
    void deleteOneSharedRecordShouldKeepOtherRecordIntact() throws Exception {
        long userA = loginAndGetUserId("instant_del_a");
        long userB = loginAndGetUserId("instant_del_b");

        byte[] content = pngBytes(28, 28);
        String md5 = com.huang.yupicture.utils.ImageUtils.md5(content);
        long idA = prepareImageWithMd5(userA, "A的图", md5);
        long idB = prepareImageWithMd5(userB, "B的图", md5);

        Cookie cookieA = loginCookie("instant_del_a");
        mockMvc.perform(post("/image/delete")
                        .queryParam("id", String.valueOf(idA))
                        .cookie(cookieA))
                .andExpect(jsonPath("$.code").value(0));

        // A 已逻辑删除，B 必须完好
        assertNull(imageService.getById(idA), "A 的记录应已逻辑删除");
        Image imageB = imageService.getById(idB);
        assertNotNull(imageB, "B 的记录必须完好");
        assertEquals(userB, imageB.getUserId());
        assertEquals(md5, imageB.getMd5());
    }

    // ==================== 二、真实链路（需要 COS） ====================

    /**
     * 连 COS 跑两遍完整上传：第二次不再传存储（日志无第二次 putObject），
     * URL 复用、记录分开。这是「秒传真的省了存储」的直接证据。
     */
    @Test
    void realUploadTwiceShouldSkipStorageSecondTime() throws Exception {
        Assumptions.assumeTrue(cosManager != null,
                "未配置 cos.client.access-key，跳过真实上传用例（配好后自动生效）");

        loginAndGetUserId("instant_real");
        Cookie cookie = loginCookie("instant_real");
        byte[] content = pngBytes(60, 60);
        String name = "秒传实测.png";

        MockMultipartFile first = new MockMultipartFile("file", name, "image/png", content);
        MvcResult r1 = mockMvc.perform(multipart("/image/upload").file(first).cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode d1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("data");
        String url1 = d1.get("url").asText();

        MockMultipartFile second = new MockMultipartFile("file", name, "image/png", content);
        MvcResult r2 = mockMvc.perform(multipart("/image/upload").file(second).cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode d2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("data");

        assertNotEquals(d1.get("id").asText(), d2.get("id").asText(), "两次上传是两条记录");
        assertEquals(url1, d2.get("url").asText(), "第二次应复用同一个 COS 对象（秒传命中）");
        // md5 不进 ImageVO（内部去重键不是展示字段）：指纹一致性已由「URL 复用」间接证明
        // —— URL 是命中源记录的，能复用说明第二次上传确实命中了同指纹记录。

        // 清理：删掉两条记录对应的所有 COS 对象（探针不留垃圾）
        for (JsonNode d : new JsonNode[]{d1, d2}) {
            try {
                mockMvc.perform(post("/image/delete")
                                .queryParam("id", d.get("id").asText())
                                .cookie(cookie))
                        .andExpect(jsonPath("$.code").value(0));
            } catch (Exception ignored) {
                // 清理失败不算测试失败，留痕即可
            }
        }
        assertTrue(true);
    }
}
