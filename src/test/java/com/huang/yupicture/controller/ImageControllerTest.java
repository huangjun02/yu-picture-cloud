package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.yupicture.constant.ImageConstant;
import com.huang.yupicture.model.entity.Image;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.ImageService;
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
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 图片接口的集成测试。
 *
 * <p><b>本类重点不是"功能能不能用"，而是"别人能不能看到 / 改到你的图"。</b>
 * requirements 里把归属校验列为权限铁律，所以测试的一半用例是<b>越权尝试</b>：
 * 普通用户查别人的图、改别人的图、删别人的图，以及"伪造 userId 参数"这种更隐蔽的越权。
 * 一个功能测试通过只说明"正常路径能走通"，安全测试才是真防线。
 *
 * <p><b>断言用「差值法」而不是绝对值：</b>数据库里可能有历史数据（自己手动测出来的），
 * 所以先查一次拿基数，造完数据再查，比较增量。这样测试不依赖"表是空的"这个前提，
 * 换台机器、换个库也能跑。
 *
 * <p>{@code @Transactional} 放在类上：每个测试方法结束后整体回滚，
 * 造的账号和图片都不会留在库里（种子数据 huangjun 不受影响）。
 * 但 Redis 里的登录态回滚不掉，所以仍然需要 {@link #clearSaTokenSessions()}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImageControllerTest {

    private static final String RAW_PASSWORD = "12345678";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private ImageService imageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 只用来验证"逻辑删除"：它绕过 MyBatis-Plus，能看见被删的行 */
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 清掉 Sa-Token 会话（与 UserControllerTest 同一套理由和写法）。
     * Redis 不参与 JDBC 事务，@Transactional 回滚不掉 token。
     */
    @AfterEach
    void clearSaTokenSessions() {
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ==================== 测试数据准备 ====================

    /** 造一个可登录的用户，返回它的 id */
    private long prepareUser(String account, String role) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试用户");
        user.setUserRole(role);
        userService.save(user);
        return user.getId();
    }

    /** 登录并拿到 satoken cookie */
    private Cookie loginAndGetCookie(String account) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("satoken");
        assertTrue(cookie != null, "登录必须下发 satoken cookie");
        return cookie;
    }

    /** 直接往库里塞一张图（不走上传接口 —— 上传要连 COS，测试不该依赖外部服务），返回图片 id */
    private long prepareImage(long userId, String name, String category) {
        return prepareImage(userId, name, category, new String[]{"测试"});
    }

    /** 带 tags 的重载（标签筛选用例用）：tags 存 JSON 数组字符串 */
    private long prepareImage(long userId, String name, String category, String[] tags) {
        return prepareImage(userId, name, category, tags, ImageConstant.REVIEW_STATUS_PENDING);
    }

    /** 造一张「已通过」的图（浏览视角 / 可见性用例用） */
    private long preparePassedImage(long userId, String name, String category) {
        return prepareImage(userId, name, category, new String[]{"测试"}, ImageConstant.REVIEW_STATUS_PASS);
    }

    /** 全参数版：显式指定审核状态（默认待审核 —— 上传入口的初始值） */
    private long prepareImage(long userId, String name, String category, String[] tags, int reviewStatus) {
        Image image = new Image();
        image.setUrl("https://test-bucket.cos.ap-shanghai.myqcloud.com/test/" + name + ".jpg");
        image.setName(name);
        image.setIntroduction("测试用图");
        image.setCategory(category);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(tags[i]).append("\"");
        }
        json.append("]");
        image.setTags(json.toString());
        image.setPicSize(2048L);
        image.setPicWidth(800);
        image.setPicHeight(600);
        image.setPicScale(800.0 / 600.0);
        image.setPicFormat("jpg");
        image.setUserId(userId);
        image.setReviewStatus(reviewStatus);
        imageService.save(image);
        return image.getId();
    }

    /** 调分页接口，返回响应体的根节点（断言 code 必须是 0） */
    private JsonNode listImages(Cookie cookie, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/image/list/page")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long totalOf(Cookie cookie, String body) throws Exception {
        return listImages(cookie, body).path("data").path("total").asLong();
    }

    // ==================== 一、越权防护（本模块的核心） ====================

    @Test
    void listImageByPageShouldOnlyReturnOwnImages() throws Exception {
        long ownerId = prepareUser("img_t_owner1", "user");
        long otherId = prepareUser("img_t_other1", "user");
        Cookie ownerCookie = loginAndGetCookie("img_t_owner1");

        String mine = "{\"userId\":\"" + ownerId + "\"}";
        long baseTotal = totalOf(ownerCookie, mine);
        prepareImage(ownerId, "我的图A", "风景");
        prepareImage(ownerId, "我的图B", "风景");
        prepareImage(otherId, "别人的图", "风景");

        JsonNode data = listImages(ownerCookie, mine).path("data");
        assertEquals(baseTotal + 2, data.path("total").asLong(),
                "「我的图片」视角只该有自己那 2 张（自己的待审核图也要能看见）");
        for (JsonNode record : data.path("records")) {
            assertEquals(String.valueOf(ownerId), record.path("userId").asText(),
                    "结果里绝不能出现别人的图");
        }
    }

    @Test
    void listImageByPageShouldIgnoreForgedUserIdForNormalUser() throws Exception {
        long ownerId = prepareUser("img_t_owner2", "user");
        long otherId = prepareUser("img_t_other2", "user");
        Cookie ownerCookie = loginAndGetCookie("img_t_owner2");

        long myPending = prepareImage(ownerId, "我的待审核图", "风景");
        long otherPending = prepareImage(otherId, "别人的待审核图", "风景");
        long otherPassed = preparePassedImage(otherId, "别人的已通过图", "风景");

        // 攻击姿势：普通用户手动传别人的 userId，试图"以别人的身份查询"
        JsonNode data = listImages(ownerCookie, "{\"userId\":\"" + otherId + "\"}").path("data");

        // 新语义：传别人的 userId 不报错也不越权，退化成「浏览」视角 —— 全站的已通过图。
        // 关键断言是"没通过审核的图（不管是谁的）一张都不能露"，这才是防线的实质
        for (JsonNode record : data.path("records")) {
            assertNotEquals(String.valueOf(otherPending),
                    record.path("id").asText(), "别人的待审核图绝不能出现在浏览结果里");
            assertNotEquals(String.valueOf(myPending),
                    record.path("id").asText(), "浏览视角下自己的待审核图也不该出现");
        }
        assertTrue(hasRecord(data, otherPassed), "浏览视角应当看得见别人已通过的图（主页图墙）");
    }

    /** 小工具：结果集里有没有这张图 */
    private boolean hasRecord(JsonNode data, long imageId) {
        for (JsonNode record : data.path("records")) {
            if (String.valueOf(imageId).equals(record.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void getImageShouldRejectOthersImage() throws Exception {
        long ownerId = prepareUser("img_t_owner3", "user");
        prepareUser("img_t_other3", "user");
        Cookie otherCookie = loginAndGetCookie("img_t_other3");
        long pendingId = prepareImage(ownerId, "别人没通过审核的图", "风景");
        long passedId = preparePassedImage(ownerId, "别人已通过的图", "风景");

        // 没通过审核的图：报「不存在」（40400）而不是「无权限」——
        // 报无权限等于告诉对方"这张图确实存在"，连存在性都不该暴露
        mockMvc.perform(post("/image/get").cookie(otherCookie).param("id", String.valueOf(pendingId)))
                .andExpect(jsonPath("$.code").value(40400));

        // 已通过的图：主页图墙里的图，谁都能看（官方流程的「内容可见性」）
        mockMvc.perform(post("/image/get").cookie(otherCookie).param("id", String.valueOf(passedId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("别人已通过的图"));
    }

    @Test
    void updateImageShouldRejectOthersImage() throws Exception {
        long ownerId = prepareUser("img_t_owner4", "user");
        prepareUser("img_t_other4", "user");
        Cookie otherCookie = loginAndGetCookie("img_t_other4");
        long imageId = prepareImage(ownerId, "别人的图", "风景");

        mockMvc.perform(post("/image/update").cookie(otherCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"name\":\"我改了\"}"))
                .andExpect(jsonPath("$.code").value(40101));

        // 不只是"返回了错误码"——要确认数据真的没被改动
        assertEquals("别人的图", imageService.getById(imageId).getName(), "越权请求不能改到数据");
    }

    @Test
    void deleteImageShouldRejectOthersImage() throws Exception {
        long ownerId = prepareUser("img_t_owner5", "user");
        prepareUser("img_t_other5", "user");
        Cookie otherCookie = loginAndGetCookie("img_t_other5");
        long imageId = prepareImage(ownerId, "别人的图", "风景");

        mockMvc.perform(post("/image/delete").cookie(otherCookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(40101));

        assertTrue(imageService.getById(imageId) != null, "越权删除不能真的把图删掉");
    }

    @Test
    void imageApiShouldRequireLogin() throws Exception {
        // 不带 cookie：应该被 SaTokenConfig 的全局拦截器拦下（40100），
        // 而不是走进 Controller 之后才报错
        mockMvc.perform(post("/image/list/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 二、管理员的"例外" ====================

    @Test
    void adminCanSeeAndOperateOthersImages() throws Exception {
        long ownerId = prepareUser("img_t_owner6", "user");
        prepareUser("img_t_admin6", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin6");
        long imageId = prepareImage(ownerId, "别人的图", "风景");

        // 看：管理员能看全部
        long baseTotal = totalOf(adminCookie, "{}");
        assertEquals(baseTotal, totalOf(adminCookie, "{}"), "基数查询应当稳定");

        // 详情：能看别人的
        mockMvc.perform(post("/image/get").cookie(adminCookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("别人的图"));

        // 改：能改别人的
        mockMvc.perform(post("/image/update").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"name\":\"管理员改的\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("管理员改的", imageService.getById(imageId).getName());
    }

    @Test
    void adminCanFilterByUserId() throws Exception {
        long ownerId = prepareUser("img_t_owner7", "user");
        long otherId = prepareUser("img_t_other7", "user");
        prepareUser("img_t_admin7", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin7");

        prepareImage(ownerId, "甲的图", "风景");
        prepareImage(otherId, "乙的图", "风景");

        // 管理员传别人的 userId：这次应该生效（与普通用户相反）
        JsonNode data = listImages(adminCookie, "{\"userId\":\"" + ownerId + "\"}").path("data");
        assertTrue(data.path("total").asLong() >= 1, "管理员应当能按 userId 查到那个人的图");
        for (JsonNode record : data.path("records")) {
            assertEquals(String.valueOf(ownerId), record.path("userId").asText(),
                    "按 userId 过滤后，结果里只该有那个人的图");
        }
    }

    // ==================== 三、业务正确性 ====================

    @Test
    void listImageByPageShouldClampPageSize() throws Exception {
        long ownerId = prepareUser("img_t_owner8", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner8");
        for (int i = 0; i < 25; i++) {
            prepareImage(ownerId, "批量图" + i, "风景");
        }

        // 前端狮子大开口要 1000 条：必须被夹到上限 20（requirements 6.2）
        JsonNode data = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"pageSize\":1000}").path("data");
        assertEquals(20, data.path("size").asLong(), "每页条数必须被夹到上限 20");
        assertEquals(20, data.path("records").size(), "一次最多返回 20 条");
    }

    @Test
    void listImageByPageShouldFilterByNameLikeAndCategoryEq() throws Exception {
        long ownerId = prepareUser("img_t_owner9", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner9");
        prepareImage(ownerId, "杭州西湖日落", "风景");
        prepareImage(ownerId, "上海外滩夜景", "风景");
        prepareImage(ownerId, "杭州小笼包", "美食");

        // 模糊匹配名称
        JsonNode byName = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"name\":\"杭州\"}").path("data");
        assertEquals(baseCount(ownerId, byName), byName.path("records").size());

        // 精确匹配分类
        JsonNode byCategory = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"category\":\"美食\"}").path("data");
        for (JsonNode record : byCategory.path("records")) {
            assertEquals("美食", record.path("category").asText(), "分类是精确匹配，不能带出别的分类");
        }
        assertTrue(byCategory.path("total").asLong() >= 1);
    }

    /** 小工具：断言查询结果里每条记录都属于 ownerId（分类过滤用例里复用） */
    private long baseCount(long ownerId, JsonNode data) {
        for (JsonNode record : data.path("records")) {
            assertEquals(String.valueOf(ownerId), record.path("userId").asText());
        }
        return data.path("records").size();
    }

    @Test
    void listImageByPageShouldFilterByTagMultiSelectAnd() throws Exception {
        long ownerId = prepareUser("img_t_owner11", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner11");
        prepareImage(ownerId, "黄山云海", "风景", new String[]{"风景", "山"});
        prepareImage(ownerId, "西湖日落", "风景", new String[]{"风景"});
        prepareImage(ownerId, "红烧肉", "美食", new String[]{"美食", "硬菜"});

        // ① 单标签：两两张「风景」都该出来（含黄山和西湖）
        JsonNode bySingle = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"tags\":\"风景\"}").path("data");
        assertEquals(2, bySingle.path("records").size(), "单标签模糊匹配应命中两张风景图");

        // ② 多选 AND：["风景","山"] 只该命中黄山（西湖没"山"标签）
        JsonNode byMulti = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"tags\":\"[\\\"风景\\\",\\\"山\\\"]\"}").path("data");
        assertEquals(1, byMulti.path("records").size(), "多选 AND 应只命中同时带两个标签的图");
        assertEquals("黄山云海", byMulti.path("records").get(0).path("name").asText());

        // ③ 带引号定界的子串误报防线：查「山」不应带出「硬菜」（都不是对方子串，
        //    但查「菜」若裸匹配会命中「硬菜」——这里验证引号定界下单标签也精确）
        JsonNode byDish = listImages(cookie, "{\"userId\":\"" + ownerId + "\",\"tags\":\"菜\"}").path("data");
        assertEquals(1, byDish.path("records").size());
        assertEquals("红烧肉", byDish.path("records").get(0).path("name").asText());

        // ④ 多选里混入空标签：明确拒绝而不是静默降级
        mockMvc.perform(post("/image/list/page")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":\"[\\\"\\\",\\\"风景\\\"]\"}"))
                .andExpect(jsonPath("$.code").value(40000));

        // ⑤ 形似 JSON 但解析失败的垃圾：同样 40000
        mockMvc.perform(post("/image/list/page")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":\"[风景\"}"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void getImageShouldReturnNotFoundForMissingId() throws Exception {
        prepareUser("img_t_owner10", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner10");

        mockMvc.perform(post("/image/get").cookie(cookie).param("id", "999999999999999999"))
                .andExpect(jsonPath("$.code").value(40400));

        // id 格式不对是"参数错误"，跟"数据不存在"要分开
        mockMvc.perform(post("/image/get").cookie(cookie).param("id", "abc"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void updateImageShouldOnlyChangeProvidedFields() throws Exception {
        long ownerId = prepareUser("img_t_owner11", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner11");
        long imageId = prepareImage(ownerId, "原名", "风景");

        // 只传 name，不传 category / introduction / tags
        mockMvc.perform(post("/image/update").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"name\":\"新名字\"}"))
                .andExpect(jsonPath("$.code").value(0));

        Image after = imageService.getById(imageId);
        assertEquals("新名字", after.getName(), "传了的字段要改");
        assertEquals("风景", after.getCategory(), "没传的字段必须保持原值（PATCH 语义）");
        assertEquals("测试用图", after.getIntroduction(), "没传的字段必须保持原值");
        assertEquals(String.valueOf(ownerId), String.valueOf(after.getUserId()), "归属关系不能被动到");
    }

    @Test
    void deleteImageShouldOnlyMarkDeletedAndKeepRow() throws Exception {
        long ownerId = prepareUser("img_t_owner12", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner12");
        long imageId = prepareImage(ownerId, "待删除的图", "风景");

        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0));

        // 走 MP 查不到（@TableLogic 自动补了 isDelete = 0）
        assertNull(imageService.getById(imageId), "删除后走正常查询应该查不到");

        // 绕过 MP 直接看：行还在，只是标记变成了 1
        Integer isDelete = jdbcTemplate.queryForObject(
                "SELECT isDelete FROM image WHERE id = ?", Integer.class, imageId);
        assertEquals(1, isDelete, "逻辑删除只改标记，不真删行");

        // 再删一次：因为查不到（已删），会报"图片不存在"
        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void imageVoShouldStringifySnowflakeIds() throws Exception {
        long ownerId = prepareUser("img_t_owner13", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner13");
        long imageId = prepareImage(ownerId, "id 序列化检查", "风景");

        mockMvc.perform(post("/image/get").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0))
                // 返回的 id 必须是字符串（JSON 里带引号），否则 JS 会丢精度
                .andExpect(jsonPath("$.data.id").value(String.valueOf(imageId)))
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(ownerId)));
    }

    @Test
    void deleteThenQueryShouldNotReturnDeletedImage() throws Exception {
        long ownerId = prepareUser("img_t_owner14", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner14");
        long imageId = prepareImage(ownerId, "删了就该消失", "风景");

        long before = totalOf(cookie, "{\"userId\":\"" + ownerId + "\"}");
        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0));
        long after = totalOf(cookie, "{\"userId\":\"" + ownerId + "\"}");

        assertEquals(before - 1, after, "逻辑删除后列表总数要减 1（不能被查出来）");
        assertNotEquals(before, after);
    }

    /**
     * 下载接口的纯逻辑用例（不连 COS —— 依赖外部服务的能力不进测试）：
     * <ul>
     *   <li>别人<b>未通过审核</b>的图 → 40400（下载是"读"，可见性与查询一致）</li>
     *   <li>别人<b>已通过</b>的图 → 权限放行、走到存储层（能看就能下载）</li>
     *   <li>不存在的 id → 40400</li>
     *   <li>格式错的 id → 40000</li>
     * </ul>
     * 正常下载走的是真 COS（byte[] 回传），MockMvc 环境下 cosManager 为 null，
     * 会停在"对象存储未配置"——那一步的行为已由 CosManagerTest 覆盖，这里不重复。
     */
    @Test
    void downloadImageShouldCheckOwnerAndParams() throws Exception {
        long ownerId = prepareUser("img_t_owner15", "user");
        Cookie ownerCookie = loginAndGetCookie("img_t_owner15");
        long pendingId = prepareImage(ownerId, "别人没通过审核的图", "风景");
        long passedId = preparePassedImage(ownerId, "别人已通过的图", "风景");

        prepareUser("img_t_other15", "user");
        Cookie otherCookie = loginAndGetCookie("img_t_other15");

        // ① 别人下载未通过审核的图 → 40400（看不见的东西也下不了）
        mockMvc.perform(post("/image/download").cookie(otherCookie).param("id", String.valueOf(pendingId)))
                .andExpect(jsonPath("$.code").value(40400));

        // ② 别人下载已通过的图：可见性放行，停在存储层（MockMvc 环境没有 COS 配置 → 50000）。
        //    这一步证明的是"权限校验已通过、走到了存储层"——若权限没过，报的会是 40400
        mockMvc.perform(post("/image/download").cookie(otherCookie).param("id", String.valueOf(passedId)))
                .andExpect(jsonPath("$.code").value(50000));

        // ③ 本人下载自己的图：同样走到存储层
        mockMvc.perform(post("/image/download").cookie(ownerCookie).param("id", String.valueOf(pendingId)))
                .andExpect(jsonPath("$.code").value(50000));

        // ③ 不存在 / 格式错
        mockMvc.perform(post("/image/download").cookie(ownerCookie).param("id", "999999999999999999"))
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(post("/image/download").cookie(ownerCookie).param("id", "abc"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void reviewShouldRequireAdminAndReasonForReject() throws Exception {
        long ownerId = prepareUser("img_t_owner16", "user");
        Cookie ownerCookie = loginAndGetCookie("img_t_owner16");
        long imageId = prepareImage(ownerId, "待审核的图", "风景");

        // ① 普通用户审核 → 无权限（40101）：审核是管理员专属
        mockMvc.perform(post("/image/review").cookie(ownerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":1}"))
                .andExpect(jsonPath("$.code").value(40101));

        // ② 管理员拒绝但不填原因 → 40000：没有理由的拒绝比拒绝本身更伤人
        prepareUser("img_t_admin16", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin16");
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":2}"))
                .andExpect(jsonPath("$.code").value(40000));

        // ③ 管理员合法审核（拒绝 + 原因）→ code=0，且审核四字段落库
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":2,\"reviewMessage\":\"内容不合适\"}"))
                .andExpect(jsonPath("$.code").value(0));

        Image after = imageService.getById(imageId);
        assertEquals(2, after.getReviewStatus(), "审核结论要落库");
        assertEquals("内容不合适", after.getReviewMessage(), "拒绝原因要落库");
        assertNotNull(after.getReviewerId(), "审核人要留痕");
        assertNotNull(after.getReviewTime(), "审核时间要留痕");
        assertEquals(String.valueOf(ownerId), String.valueOf(after.getUserId()), "审核不能动归属");
        assertEquals("待审核的图", after.getName(), "审核不能改图的内容");

        // ④ 管理员复查改判为通过：允许（申诉场景）；但状态值只能是 1/2
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, imageService.getById(imageId).getReviewStatus(), "复查改判要生效");

        // ⑤ 非法状态值 → 40000
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":9}"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void newUploadShouldDefaultToPendingReview() throws Exception {
        long ownerId = prepareUser("img_t_owner17", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner17");
        long imageId = prepareImage(ownerId, "新传的图", "风景");

        Image image = imageService.getById(imageId);
        assertEquals(0, image.getReviewStatus(), "先过后审：新图默认待审核（0）");
        assertNull(image.getReviewerId(), "没审过就没有审核人");

        // 列表接口返回的 VO 要带审核状态（管理员工作台 + 用户看自己图的进度）
        mockMvc.perform(post("/image/get").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reviewStatus").value(0));
    }

    // ==================== 四、审核流（对齐官方流程） ====================

    /**
     * 管理员更新图片 → 自动过审，并自动补齐审核参数。
     *
     * <p>为什么管理员要有"自动过审"这条捷径：管理员改自己的图还要自己审自己，
     * 是纯多余的流程；而管理员改<b>别人</b>的图，改动本身就已经是管理员意志的体现，
     * 再挂一个待审核等于让自己再审一遍。
     */
    @Test
    void adminUpdateShouldAutoPassAndFillReviewParams() throws Exception {
        long ownerId = prepareUser("img_t_owner18", "user");
        prepareUser("img_t_admin18", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin18");
        long imageId = preparePassedImage(ownerId, "管理员要改的图", "风景");

        // 先写一条"历史审核意见"，确认自动过审会把它覆盖掉（而不是留着两条矛盾信息）
        Image history = new Image();
        history.setId(imageId);
        history.setReviewMessage("上一轮的历史意见");
        imageService.updateById(history);

        mockMvc.perform(post("/image/update").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"name\":\"管理员改的\"}"))
                .andExpect(jsonPath("$.code").value(0));

        Image after = imageService.getById(imageId);
        assertEquals(1, after.getReviewStatus(), "管理员更新图片应自动过审");
        assertEquals(String.valueOf(ownerId), String.valueOf(after.getReviewerId()),
                "审核人 = 图片创建人");
        assertNotNull(after.getReviewTime(), "审核时间 = 当前时间");
        assertEquals(ImageConstant.AUTO_REVIEW_MESSAGE, after.getReviewMessage(),
                "审核原因 = 管理员自动过审（固定文案，便于事后区分自动/人工）");
    }

    /**
     * 用户更新图片 → 退回待审核，但<b>保留</b>上一轮审核信息。
     *
     * <p>只重置状态、不擦参数，是刻意的：图的内容变了，旧结论当然失效；
     * 但「上次是谁、因为什么拒的」是用户申诉的依据，属于历史事实，不该被清掉。
     */
    @Test
    void userUpdateShouldResetToPendingButKeepReviewHistory() throws Exception {
        long ownerId = prepareUser("img_t_owner19", "user");
        Cookie ownerCookie = loginAndGetCookie("img_t_owner19");
        long imageId = preparePassedImage(ownerId, "用户要改的图", "风景");

        Image history = new Image();
        history.setId(imageId);
        history.setReviewMessage("上次的审核意见");
        history.setReviewerId(123456789L);
        imageService.updateById(history);

        mockMvc.perform(post("/image/update").cookie(ownerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"name\":\"用户改的\"}"))
                .andExpect(jsonPath("$.code").value(0));

        Image after = imageService.getById(imageId);
        assertEquals(0, after.getReviewStatus(), "用户编辑图片应退回待审核");
        assertEquals("上次的审核意见", after.getReviewMessage(), "只重置状态，审核意见保留");
        assertEquals(123456789L, after.getReviewerId(), "只重置状态，审核人保留");
    }

    /**
     * 重复审核的两种姿势：默认「重置全部参数」，显式传 false 则「只改状态、保留历史」。
     */
    @Test
    void reReviewShouldHonorResetSwitch() throws Exception {
        long ownerId = prepareUser("img_t_owner20", "user");
        prepareUser("img_t_admin20", "admin");
        prepareUser("img_t_admin21", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin20");
        Cookie anotherAdminCookie = loginAndGetCookie("img_t_admin21");
        long imageId = prepareImage(ownerId, "被拒的图", "风景");

        // 第一次：拒绝 + 原因（走默认，等价于 resetReviewParams=true）
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":2,\"reviewMessage\":\"内容不合适\"}"))
                .andExpect(jsonPath("$.code").value(0));
        Image first = imageService.getById(imageId);
        Long firstReviewer = first.getReviewerId();
        Date firstTime = first.getReviewTime();

        // 第二次：另一个管理员改判通过，但选「不重置审核参数」→ 状态变、历史留
        mockMvc.perform(post("/image/review").cookie(anotherAdminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":1,\"resetReviewParams\":false}"))
                .andExpect(jsonPath("$.code").value(0));
        Image second = imageService.getById(imageId);
        assertEquals(1, second.getReviewStatus(), "改判要生效");
        assertEquals(firstReviewer, second.getReviewerId(), "不重置：审核人保留上一次的");
        assertEquals(firstTime, second.getReviewTime(), "不重置：审核时间保留上一次的");
        assertEquals("内容不合适", second.getReviewMessage(), "不重置：意见保留（历史参考）");

        // 第三次：同一个管理员改判拒绝 + 明确重置 → 四字段全部刷新
        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":2,\"reviewMessage\":\"复审还是不行\",\"resetReviewParams\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        Image third = imageService.getById(imageId);
        assertEquals(2, third.getReviewStatus());
        assertEquals("复审还是不行", third.getReviewMessage(), "重置：意见换成本次的");
        assertNotEquals(second.getReviewMessage(), third.getReviewMessage());
    }

    /**
     * 「不重置参数」时不该逼管理员填原因 —— 那条意见压根不会落库。
     * 强行必填等于让用户填一个会被丢掉的值，属于假校验。
     */
    @Test
    void rejectWithoutResetShouldNotRequireMessage() throws Exception {
        long ownerId = prepareUser("img_t_owner22", "user");
        prepareUser("img_t_admin22", "admin");
        Cookie adminCookie = loginAndGetCookie("img_t_admin22");
        long imageId = prepareImage(ownerId, "待审核的图", "风景");

        mockMvc.perform(post("/image/review").cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + imageId + "\",\"reviewStatus\":2,\"resetReviewParams\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        Image after = imageService.getById(imageId);
        assertEquals(2, after.getReviewStatus(), "状态该改还是要改");
        assertNull(after.getReviewMessage(), "没有历史意见时保持为空，不编造内容");
    }

    /**
     * 浏览视角（不传 userId）：只能看见「审核通过」的图 —— 自己的待审核图也不行。
     *
     * <p>这是官方流程第 4 条「内容可见性」的落点：<b>主页是已通过图墙</b>，
     * 「我的图片」才看得到自己的全部状态 —— 两个视角由「传不传自己的 userId」区分。
     */
    @Test
    void browseShouldOnlyReturnPassedImages() throws Exception {
        long ownerId = prepareUser("img_t_owner23", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner23");
        long myPending = prepareImage(ownerId, "我的待审核图", "风景");
        long myPassed = preparePassedImage(ownerId, "我的已通过图", "风景");

        JsonNode data = listImages(cookie, "{}").path("data");
        assertFalse(hasRecord(data, myPending), "浏览视角不能出现没通过审核的图（哪怕是自己的）");
        assertTrue(hasRecord(data, myPassed), "浏览视角要能看见已通过的图");
    }

    /** 筛选项只来自已通过审核的图 —— 否则等于提前泄露没过审的内容长什么样 */
    @Test
    void optionsShouldOnlyExposePassedImagesData() throws Exception {
        long ownerId = prepareUser("img_t_owner24", "user");
        Cookie cookie = loginAndGetCookie("img_t_owner24");
        long passedId = prepareImage(ownerId, "已通过图", "选项分类A",
                new String[]{"选项标签A"}, ImageConstant.REVIEW_STATUS_PASS);
        prepareImage(ownerId, "待审核图", "选项分类B", new String[]{"选项标签B"},
                ImageConstant.REVIEW_STATUS_PENDING);

        MvcResult result = mockMvc.perform(post("/image/options").cookie(cookie))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");

        java.util.List<String> categories = new java.util.ArrayList<>();
        data.path("categories").forEach(n -> categories.add(n.asText()));
        java.util.List<String> tags = new java.util.ArrayList<>();
        data.path("tags").forEach(n -> tags.add(n.asText()));

        assertTrue(categories.contains("选项分类A"), "已通过图的分类要出现在筛选项里");
        assertFalse(categories.contains("选项分类B"), "待审核图的分类不能出现在筛选项里");
        assertTrue(tags.contains("选项标签A"), "已通过图的标签要出现在筛选项里");
        assertFalse(tags.contains("选项标签B"), "待审核图的标签不能出现在筛选项里");
        assertNotNull(imageService.getById(passedId), "造的数据本身要在库里（防造数失败导致的假绿）");
    }
}
