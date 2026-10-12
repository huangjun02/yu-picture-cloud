package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.manager.ImageCrawlClient;
import com.huang.yupicture.manager.ImageCrawlClient.CrawlCandidate;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.ImageService;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 批量抓图接口测试。
 *
 * <p>检索客户端被替换成桩（{@link StubCrawlConfig}）：**测试不该依赖外部站点**——
 * 源站改版、限流、断网都会让测试变红，那种红是噪音不是信号。真实检索留给人跑一次端到端验证。
 *
 * <p>用例覆盖的是"抓取这个新入口有没有绕过既有规则"和"失败会不会连坐"：
 * ① 非管理员不能抓（新入口最容易漏权限校验）
 * ② 关键词为空要挡住
 * ③ 数量收敛到上限（用户传 999 不能真的往源站要 999 条）
 * ④ 单张失败不影响其余（外链失效是常态）
 * ⑤ 同名图片跳过 = 幂等（同一个关键词抓两次不会翻倍）
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageCrawlControllerTest {

    private static final String RAW_PASSWORD = "12345678";

    /** 当次测试要返回的候选（桩从这里取） */
    private static volatile List<CrawlCandidate> stubbedCandidates = List.of();

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

    /**
     * 检索客户端换成桩。
     *
     * <p><b>为什么必须用 {@code @MockitoBean} 而不是自己写个 @Primary 的 @Bean：</b>
     * 业务代码里的注入是 {@code @Resource}，而 **@Resource 先按字段名匹配 bean 名字**，
     * 名字对上了就不会再走"按类型 + @Primary"那条路 —— 结果桩根本没被用上，
     * 测试真的去打了源站（这个坑实测踩到过：跑了 42 秒、还真的传了 10 张图进 COS）。
     * {@code @MockitoBean} 是在上下文层面**按类型替换** bean 定义，与注入方式无关，才治得住。
     */
    @MockitoBean
    private ImageCrawlClient imageCrawlClient;

    @BeforeEach
    void stubSearch() {
        when(imageCrawlClient.search(anyString(), anyInt()))
                .thenAnswer(inv -> new ArrayList<>(stubbedCandidates));
    }

    @AfterEach
    void clearState() {
        stubbedCandidates = List.of();
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ==================== 用例 ====================

    /** 普通用户不能抓：抓取会往库里灌数据，是管理员操作 */
    @Test
    void crawlShouldRejectNonAdmin() throws Exception {
        prepareUser("crawl_t_user1", "user");
        Cookie cookie = loginAndGetCookie("crawl_t_user1");
        stubbedCandidates = List.of(candidate("http://127.0.0.1:9/a.jpg", "图A"));

        MvcResult result = mockMvc.perform(post("/image/crawl").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"风景\"}")).andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(40101, body.path("code").asInt(), "非管理员调抓取必须是 40101，实际响应：" + body);
    }

    @Test
    void crawlShouldRejectBlankQuery() throws Exception {
        prepareUser("crawl_t_admin1", "admin");
        Cookie cookie = loginAndGetCookie("crawl_t_admin1");

        mockMvc.perform(post("/image/crawl").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(40000));
    }

    /**
     * 逐条状态 + 失败不连坐 + 幂等跳过 + 抓取路径不绕开 SSRF 防御。
     *
     * <p>四条候选覆盖三个分支：地址不可用（FAIL ×2）、本次结果里地址重复（SKIP）、
     * 库里已有同名图（SKIP）。任何一个分支把循环打断，后面的统计就对不上。
     *
     * <p>用回环地址（{@code http://127.0.0.1:9/...}）当"拉不动的地址"是有意的：
     * ① 它在读 URL 阶段就被拒，测试不打网络、跑得飞快（之前用真实域名，一次要等 10~40 秒）；
     * ② 它同时证明了抓取复用了 URL 传图的 SSRF 防线 —— 抓来的地址是"外部可影响输入"，正需要这道防线。
     */
    @Test
    void crawlShouldReportPerItemAndNotAbortOnFailure() throws Exception {
        long adminId = prepareUser("crawl_t_admin2", "admin");
        Cookie cookie = loginAndGetCookie("crawl_t_admin2");

        String dupUrl = "http://127.0.0.1:9/dup.jpg";
        String existsUrl = "http://127.0.0.1:9/exists.jpg";
        // 先塞一张"已经抓过"的图：名字与抓取算法一致（标题_源地址短哈希）
        String existingName = expectedName("已经抓过的图", existsUrl);
        prepareImage(adminId, existingName);

        stubbedCandidates = List.of(
                candidate("http://127.0.0.1:9/never.jpg", "新图"),
                candidate(dupUrl, "重复地址"),
                candidate(dupUrl, "重复地址"),
                candidate(existsUrl, "已经抓过的图"));

        MvcResult result = mockMvc.perform(post("/image/crawl").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"风景\",\"count\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertEquals(4, data.path("candidateCount").asInt(), "四条候选都要被记录（成功/跳过/失败各占一行）");
        assertEquals(4, data.path("items").size(), "每条候选都要有明细，只报总数的话用户不知道少的那几张怎么了");
        // 两条地址都是回环地址（SSRF 判定直接拒），所以有 2 条 FAIL：
        // 第一条"新地址"和第二条"重复地址的首次出现"都会尝试下载 —— 只有第三次
        // （同地址再出现）才走"本次结果里地址重复"的跳过分支
        assertEquals(2, data.path("failCount").asInt(), "两条拉不动的地址都要记 FAIL");

        // 幂等跳过两条：一条本次重复、一条库里同名
        assertEquals(2, data.path("skipCount").asInt(), "重复地址 + 库里同名，都要跳过");
        boolean hasIdempotentSkip = false;
        for (JsonNode item : data.path("items")) {
            if ("SKIP".equals(item.path("status").asText())) {
                assertTrue(item.path("reason").asText().contains("已存在")
                                || item.path("reason").asText().contains("重复"),
                        "跳过原因要说清是哪种跳过，实际：" + item.path("reason").asText());
                if (item.path("name").asText().equals(existingName)) {
                    hasIdempotentSkip = true;
                }
            }
            if ("FAIL".equals(item.path("status").asText())) {
                String reason = item.path("reason").asText();
                assertTrue(!reason.isBlank(), "失败必须带原因");
                // 钉住一件事：抓取是**新入口但不是新通道** —— URL 传图那套 SSRF 防御照样生效。
                // 这条断言的价值在于，将来若有人图省事在抓取里自己写一段下载逻辑，它会立刻变红
                assertTrue(reason.contains("内网地址"),
                        "抓取路径必须复用 URL 传图的 SSRF 校验，实际原因：" + reason);
            }
        }
        assertTrue(hasIdempotentSkip, "库里已有同名图时必须跳过（同一关键词抓两次不能翻倍）");
    }

    /** 数量收敛：传 999 不能真往源站要 999 条，按 maxCount 夹住 */
    @Test
    void crawlShouldClampCountToUpperBound() throws Exception {
        prepareUser("crawl_t_admin3", "admin");
        Cookie cookie = loginAndGetCookie("crawl_t_admin3");

        List<CrawlCandidate> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(candidate("http://127.0.0.1:9/many-" + i + ".jpg", "批量图" + i));
        }
        stubbedCandidates = many;

        MvcResult result = mockMvc.perform(post("/image/crawl").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"猫\",\"count\":999}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertTrue(data.path("items").size() <= 20,
                "处理条数要被 maxCount 夹住，实际处理了 " + data.path("items").size() + " 条");
    }

    // ==================== 辅助 ====================

    private CrawlCandidate candidate(String url, String title) {
        return new CrawlCandidate(url, title, "作者", "by 2.0", "署名信息", List.of("tag"));
    }

    /**
     * 复刻抓取服务里的命名规则（标题_源地址 SHA-256 前 6 位十六进制）。
     *
     * <p>测试里重复实现一遍是有意的：这是在钉住一个**对外契约**（名字决定幂等），
     * 名字规则变了测试必须跟着变红 —— 否则"改坏幂等"会没人发现。
     */
    private String expectedName(String title, String url) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return title + "_" + sb;
    }

    /** 造一个可登录的用户，返回 id（造"已抓过"的图时要用它的 userId） */
    private long prepareUser(String account, String role) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试用户");
        user.setUserRole(role);
        userService.save(user);
        return user.getId();
    }

    private Cookie loginAndGetCookie(String account) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertTrue(cookie != null, "登录必须下发 satoken cookie");
        return cookie;
    }

    private void prepareImage(long userId, String name) {
        Image image = new Image();
        image.setUrl("https://test-bucket.cos.ap-shanghai.myqcloud.com/test/" + name + ".jpg");
        image.setName(name);
        image.setCategory("测试");
        image.setTags("[\"测试\"]");
        image.setPicSize(2048L);
        image.setPicWidth(800);
        image.setPicHeight(600);
        image.setPicScale(800.0 / 600.0);
        image.setPicFormat("jpg");
        image.setUserId(userId);
        image.setReviewStatus(1);
        imageService.save(image);
    }
}
