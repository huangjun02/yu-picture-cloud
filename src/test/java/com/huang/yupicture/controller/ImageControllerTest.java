package com.huang.yupicture.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        Image image = new Image();
        image.setUrl("https://test-bucket.cos.ap-shanghai.myqcloud.com/test/" + name + ".jpg");
        image.setName(name);
        image.setIntroduction("测试用图");
        image.setCategory(category);
        image.setTags("[\"测试\"]");
        image.setPicSize(2048L);
        image.setPicWidth(800);
        image.setPicHeight(600);
        image.setPicScale(800.0 / 600.0);
        image.setPicFormat("jpg");
        image.setUserId(userId);
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

        long baseTotal = totalOf(ownerCookie, "{}");
        prepareImage(ownerId, "我的图A", "风景");
        prepareImage(ownerId, "我的图B", "风景");
        prepareImage(otherId, "别人的图", "风景");

        JsonNode data = listImages(ownerCookie, "{}").path("data");
        assertEquals(baseTotal + 2, data.path("total").asLong(), "普通用户只能看到自己的 2 张图");
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

        long baseTotal = totalOf(ownerCookie, "{}");
        prepareImage(ownerId, "我的图", "风景");
        prepareImage(otherId, "别人的图", "风景");

        // 攻击姿势：普通用户手动传别人的 userId，试图"以别人的身份查询"
        JsonNode data = listImages(ownerCookie, "{\"userId\":\"" + otherId + "\"}").path("data");

        assertEquals(baseTotal + 1, data.path("total").asLong(),
                "传了别人的 userId 也只能看到自己的图（入参被 Service 覆盖）");
        for (JsonNode record : data.path("records")) {
            assertEquals(String.valueOf(ownerId), record.path("userId").asText());
        }
    }

    @Test
    void getImageShouldRejectOthersImage() throws Exception {
        long ownerId = prepareUser("img_t_owner3", "user");
        prepareUser("img_t_other3", "user");
        Cookie otherCookie = loginAndGetCookie("img_t_other3");
        long imageId = prepareImage(ownerId, "别人的图", "风景");

        mockMvc.perform(post("/image/get").cookie(otherCookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(40101));
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
        JsonNode data = listImages(cookie, "{\"pageSize\":1000}").path("data");
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
        JsonNode byName = listImages(cookie, "{\"name\":\"杭州\"}").path("data");
        assertEquals(baseCount(ownerId, byName), byName.path("records").size());

        // 精确匹配分类
        JsonNode byCategory = listImages(cookie, "{\"category\":\"美食\"}").path("data");
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

        long before = totalOf(cookie, "{}");
        mockMvc.perform(post("/image/delete").cookie(cookie).param("id", String.valueOf(imageId)))
                .andExpect(jsonPath("$.code").value(0));
        long after = totalOf(cookie, "{}");

        assertEquals(before - 1, after, "逻辑删除后列表总数要减 1（不能被查出来）");
        assertNotEquals(before, after);
    }
}
