package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.ImageUtils;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 以图搜图接口的集成测试（{@code POST /image/search/similar}）。
 *
 * <p>被测的是「上传一张图 → 返回图库里最相似的图」这条链路，重点在三处：
 * ① 真的按相似度排序（同一张图排第一）；② 可见性（未通过的图不参与）；③ 异常输入不炸。
 *
 * <p>本类自带 helper（与 {@code ImageControllerTest} 同名方法重复）—— 这是本项目既有做法：
 * 每个测试类自带 helper，不共享基类（改基类会影响全部用例）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageSimilarSearchTest {

    private static final String RAW_PASSWORD = "12345678";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private ImageService imageService;

    /** 图片查询多级缓存（测试直接插库绕过写路径，测试前必须清，理由同 ImageControllerTest） */
    @Resource
    private com.huang.yupicture.manager.ImageCacheManager imageCacheManager;

    @org.junit.jupiter.api.BeforeEach
    void clearImageCacheBeforeTest() {
        imageCacheManager.clearForTest();
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        // 造图代码跑在被测方法之前，那时 ImageUtils 的静态块还没触发，必须自己再关一次
        ImageIO.setUseCache(false);
    }

    @AfterEach
    void clearSaTokenSessions() {
        // Redis 不参与 JDBC 事务，@Transactional 回滚不掉 token
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ==================== 测试数据准备 ====================

    private long prepareUser(String account) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试用户");
        user.setUserRole("user");
        userService.save(user);
        return user.getId();
    }

    private Cookie loginAndGetCookie(String account) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertNotNull(cookie, "登录必须下发 satoken cookie");
        return cookie;
    }

    /** 造一张图记录，picHash 显式给定（不走上传接口 —— 上传要连 COS，测试不依赖外部服务） */
    private long prepareImage(long userId, String name, String picHash, int reviewStatus) {
        Image image = new Image();
        image.setUrl("https://test-bucket.cos.ap-shanghai.myqcloud.com/test/" + name + ".jpg");
        image.setName(name);
        image.setTags("[\"测试\"]");
        image.setPicSize(2048L);
        image.setPicFormat("jpg");
        image.setPicHash(picHash);
        image.setUserId(userId);
        image.setReviewStatus(reviewStatus);
        imageService.save(image);
        imageCacheManager.bumpVersion(); // 直接插库 = 模拟外部写，必须失效缓存（见 ImageControllerTest）
        return image.getId();
    }

    /** 调以图搜图接口，返回响应体根节点（断言 code=0） */
    private JsonNode searchSimilar(Cookie cookie, byte[] queryBytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "query.jpg", "image/jpeg", queryBytes);
        MvcResult result = mockMvc.perform(multipart("/image/search/similar").file(file).cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> idsOf(JsonNode root) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            ids.add(node.path("id").asText());
        }
        return ids;
    }

    // ==================== 用例 ====================

    /** 核心：上传的图与库里的图是同一张 → 必须命中，且排第一 */
    @Test
    void sameImageShouldBeFoundFirst() throws Exception {
        long userId = prepareUser("similar_u1");
        Cookie cookie = loginAndGetCookie("similar_u1");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        assertNotNull(hash, "查询图必须能算出哈希（算不出说明造图代码有问题）");

        long targetId = prepareImage(userId, "target", hash, ImageConstant.REVIEW_STATUS_PASS);
        prepareImage(userId, "noise", ImageUtils.perceptualHash(noiseImage()),
                ImageConstant.REVIEW_STATUS_PASS);

        JsonNode root = searchSimilar(cookie, query);
        List<String> ids = idsOf(root);
        assertTrue(ids.contains(String.valueOf(targetId)), "同一张图必须出现在结果里，实际返回=" + ids);
        assertTrue(String.valueOf(targetId).equals(ids.get(0)), "最像的必须排第一，实际返回=" + ids);
    }

    /** picHash 为 null 的图（老图未回填）不能参与比对，更不能让整个接口 NPE */
    @Test
    void imageWithoutHashShouldBeSkipped() throws Exception {
        long userId = prepareUser("similar_u2");
        Cookie cookie = loginAndGetCookie("similar_u2");

        long legacyId = prepareImage(userId, "legacy", null, ImageConstant.REVIEW_STATUS_PASS);

        byte[] query = photoishImage();
        long targetId = prepareImage(userId, "target", ImageUtils.perceptualHash(query),
                ImageConstant.REVIEW_STATUS_PASS);

        List<String> ids = idsOf(searchSimilar(cookie, query));
        assertTrue(ids.contains(String.valueOf(targetId)));
        assertFalse(ids.contains(String.valueOf(legacyId)), "没有哈希的图不该出现在结果里");
    }

    /** 库里没有相似的图 → 返回空列表（不是报错、也不是把无关图凑数返回） */
    @Test
    void dissimilarImagesShouldBeExcluded() throws Exception {
        long userId = prepareUser("similar_u3");
        Cookie cookie = loginAndGetCookie("similar_u3");

        long noiseId = prepareImage(userId, "noise", ImageUtils.perceptualHash(noiseImage()),
                ImageConstant.REVIEW_STATUS_PASS);

        JsonNode root = searchSimilar(cookie, gradientImage());
        List<String> ids = idsOf(root);
        assertFalse(ids.contains(String.valueOf(noiseId)), "无关图不该出现，实际返回=" + ids);
    }

    /** 未通过的图不参与「以图搜图」（与主页图墙同一套可见性语义） */
    @Test
    void pendingImageShouldNotBeSearched() throws Exception {
        long userId = prepareUser("similar_u4");
        Cookie cookie = loginAndGetCookie("similar_u4");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        long pendingId = prepareImage(userId, "pending", hash, ImageConstant.REVIEW_STATUS_PENDING);

        JsonNode root = searchSimilar(cookie, query);
        assertFalse(idsOf(root).contains(String.valueOf(pendingId)),
                "待审核的图不该出现在以图搜图结果里");
    }

    /** 结果条数不得超过 SIMILAR_MAX_RESULT */
    @Test
    void resultCountShouldBeCapped() throws Exception {
        long userId = prepareUser("similar_u5");
        Cookie cookie = loginAndGetCookie("similar_u5");

        byte[] query = photoishImage();
        String hash = ImageUtils.perceptualHash(query);
        for (int i = 0; i < ImageConstant.SIMILAR_MAX_RESULT + 3; i++) {
            prepareImage(userId, "same" + i, hash, ImageConstant.REVIEW_STATUS_PASS);
        }

        JsonNode root = searchSimilar(cookie, query);
        assertTrue(root.path("data").size() <= ImageConstant.SIMILAR_MAX_RESULT,
                "实际返回条数=" + root.path("data").size());
    }

    /** 非图片文件 → 40000（复用上传那套「后缀白名单 + 魔数校验」） */
    @Test
    void invalidFileShouldBeRejected() throws Exception {
        long userId = prepareUser("similar_u6");
        Cookie cookie = loginAndGetCookie("similar_u6");

        MockMultipartFile file = new MockMultipartFile("file", "q.jpg", "image/jpeg",
                "not an image at all".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/image/search/similar").file(file).cookie(cookie))
                .andExpect(jsonPath("$.code").value(40000));
    }

    /** 未登录 → 40100（新接口默认受 Sa-Token 拦截器保护，不加白名单） */
    @Test
    void notLoggedInShouldBeRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "q.jpg", "image/jpeg", photoishImage());
        mockMvc.perform(multipart("/image/search/similar").file(file))
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 造图 helper ====================

    private static byte[] gradientImage() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = (x + y) / 2;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img);
    }

    private static byte[] photoishImage() throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = (x + y) / 2;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(200, 120, 40));
        g.fillOval(60, 60, 130, 130);
        g.setColor(new java.awt.Color(40, 90, 180));
        g.fillRect(10, 200, 110, 50);
        g.dispose();
        return toBytes(img);
    }

    private static byte[] noiseImage() throws Exception {
        java.util.Random random = new java.util.Random(42);
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int v = random.nextInt(256);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return toBytes(img);
    }

    private static byte[] toBytes(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
