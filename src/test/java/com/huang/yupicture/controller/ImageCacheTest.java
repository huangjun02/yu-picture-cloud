package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 图片查询多级缓存（图片优化第二期）的集成测试。
 *
 * <p><b>怎么证明「命中了缓存」：</b>不打桩、不数 SQL —— 走真实链路后<b>直接改数据库</b>，
 * 再查一次：图墙结果若纹丝不动（旧数据），说明读的是缓存；「我的图片」视角能看到新数据，
 * 说明那个视角没被缓存。比 mock 掉 Mapper 更接近真实（Redis 读写、版本号、序列化全在链路里）。
 *
 * <p><b>怎么证明「失效生效」：</b>先缓存住「图墙看不见这张图」→ 审核通过（走 Service 写路径，
 * 版本号 bump）→ 再查：图墙立刻出现新图。失效若缺失，这里仍会返回旧缓存的「不可见」。
 *
 * <p><b>用例全部用独立分类「缓存测试」隔离：</b>库里有历史数据（手工测出来的 27 张），
 * 不隔离的话分页第一页混入别人的图，断言「列表里有没有」就变成碰运气。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageCacheTest {

    private static final String RAW_PASSWORD = "12345678";

    /** 本测试专属分类：所有用例的查询都带它，与库里的历史数据彻底隔离 */
    private static final String TEST_CATEGORY = "缓存测试";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearSaTokenSessions() {
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        // 缓存 key 与版本号落了 Redis，@Transactional 回滚不掉，显式清理防串测试
        Set<String> cacheKeys = stringRedisTemplate.keys("image:cache:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            stringRedisTemplate.delete(cacheKeys);
        }
    }

    // ==================== 测试数据准备（与 ImageControllerTest 同一套写法） ====================

    private long prepareUser(String account, String role) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName(account);
        user.setUserRole(role);
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

    /** 直接往库里塞一张图（绕过 COS；分类固定 TEST_CATEGORY 保证查询隔离） */
    private long prepareImage(long userId, String name, int reviewStatus) {
        jdbcTemplate.update("""
                        INSERT INTO image (url, name, introduction, category, tags, picSize, picWidth,
                                           picHeight, picScale, picFormat, userId, reviewStatus, isDelete, createTime, updateTime)
                        VALUES (?, ?, '缓存测试用图', ?, '["测试"]', 2048, 800, 600, 1.333, 'jpg', ?, ?, 0, NOW(), NOW())
                        """,
                "https://test-bucket.cos.ap-shanghai.myqcloud.com/cache-test/" + name + ".jpg",
                name, TEST_CATEGORY, userId, reviewStatus);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM image WHERE name = ? ORDER BY id DESC LIMIT 1", Long.class, name);
        assertNotNull(id, "测试图应已入库");
        return id;
    }

    /** 查图墙（浏览视角），响应根节点 */
    private JsonNode postList(Cookie cookie, String extraJson) throws Exception {
        String body = extraJson == null || extraJson.isBlank()
                ? "{\"pageNum\":1,\"pageSize\":10,\"category\":\"" + TEST_CATEGORY + "\"}"
                : "{\"pageNum\":1,\"pageSize\":10,\"category\":\"" + TEST_CATEGORY + "\"," + extraJson + "}";
        MvcResult result = mockMvc.perform(post("/image/list/page")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 管理员审核通过（走真实写路径 → 触发缓存失效） */
    private JsonNode reviewPass(Cookie adminCookie, long imageId) throws Exception {
        MvcResult result = mockMvc.perform(post("/image/review")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":" + ImageConstant.REVIEW_STATUS_PASS + "}"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ==================== 用例 ====================

    @Test
    void browseViewShouldBeCachedWhileMineViewIsNot() throws Exception {
        long userId = prepareUser("cache_user1", "user");
        Cookie cookie = loginAndGetCookie("cache_user1");
        long imageId = prepareImage(userId, "cache_browse_1", ImageConstant.REVIEW_STATUS_PASS);

        // 第一次查询：回源（同时写入缓存）
        JsonNode first = postList(cookie, null);
        assertEquals(0, first.path("code").asInt());
        assertEquals(imageId, findIdByName(first, "cache_browse_1"), "图墙第一次查询应看到这张图");

        // 直接改库（绕过 Service 写路径，不触发失效）：模拟「缓存不知道的数据变更」
        jdbcTemplate.update("UPDATE image SET name = 'cache_browse_1_RENAMED' WHERE id = ?", imageId);

        // 第二次同条件查询：浏览视角拿到的还是旧名字 → 命中缓存
        JsonNode second = postList(cookie, null);
        assertEquals("cache_browse_1", findNameById(second, imageId),
                "浏览视角第二次查询应命中缓存（名字还是旧的）");

        // 「我的图片」视角（显式传自己的 userId）：能看到新名字 → 没走缓存
        JsonNode mine = postList(cookie, "\"userId\":\"" + userId + "\"");
        assertEquals("cache_browse_1_RENAMED", findNameById(mine, imageId),
                "我的图片视角不缓存，应读到最新数据");
    }

    @Test
    void writeOperationShouldInvalidateBrowseCache() throws Exception {
        long ownerId = prepareUser("cache_owner2", "user");
        prepareUser("cache_admin2", "admin");
        Cookie viewerCookie = loginAndGetCookie("cache_owner2");
        Cookie adminCookie = loginAndGetCookie("cache_admin2");

        // 一张待审核图：图墙看不见
        long imageId = prepareImage(ownerId, "cache_review_2", ImageConstant.REVIEW_STATUS_PENDING);
        JsonNode before = postList(viewerCookie, null);
        assertEquals(0, before.path("code").asInt());
        assertNull(findNameById(before, imageId), "待审核图不应出现在图墙");

        // 管理员审核通过 → 走 Service 写路径 → 版本号 bump → 旧缓存全作废
        JsonNode reviewResp = reviewPass(adminCookie, imageId);
        assertEquals(0, reviewResp.path("code").asInt(), "审核应成功");

        // 再查图墙：立即可见 → 失效生效（失效缺失的话，这里仍会返回旧缓存的「不可见」）
        JsonNode after = postList(viewerCookie, null);
        assertEquals("cache_review_2", findNameById(after, imageId),
                "审核通过后图墙应立即可见（缓存已失效）");
    }

    @Test
    void adminViewShouldNotBeCached() throws Exception {
        long ownerId = prepareUser("cache_owner3", "user");
        prepareUser("cache_admin3", "admin");
        Cookie adminCookie = loginAndGetCookie("cache_admin3");
        long imageId = prepareImage(ownerId, "cache_admin_3", ImageConstant.REVIEW_STATUS_PASS);

        // 管理员视角查询两遍，中间直接改库
        JsonNode first = postList(adminCookie, null);
        assertEquals(0, first.path("code").asInt());
        jdbcTemplate.update("UPDATE image SET name = 'cache_admin_3_RENAMED' WHERE id = ?", imageId);
        JsonNode second = postList(adminCookie, null);
        assertEquals("cache_admin_3_RENAMED", findNameById(second, imageId),
                "管理员视角不缓存，第二次查询应读到库里的最新数据");
    }

    // ==================== 断言辅助 ====================

    /** 在分页结果里按 name 找记录，返回其 id（找不到返回 null） */
    private Long findIdByName(JsonNode pageResp, String name) {
        for (JsonNode record : pageResp.path("data").path("records")) {
            if (name.equals(record.path("name").asText(null))) {
                return record.path("id").asLong();
            }
        }
        return null;
    }

    /** 在分页结果里按 id 找记录，返回其 name（找不到返回 null） */
    private String findNameById(JsonNode pageResp, long imageId) {
        for (JsonNode record : pageResp.path("data").path("records")) {
            String recordId = record.path("id").asText();
            if (recordId.equals(String.valueOf(imageId))) {
                return record.path("name").asText(null);
            }
        }
        return null;
    }
}
