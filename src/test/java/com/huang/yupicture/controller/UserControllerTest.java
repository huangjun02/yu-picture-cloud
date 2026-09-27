package com.huang.yupicture.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户登录接口的集成测试。
 *
 * <p>用 MockMvc 而不是「真起端口 + HTTP 客户端」，是因为要保证<b>测试造的数据能被请求看到</b>：
 * MockMvc 的请求和测试方法跑在同一个线程/同一个事务里，插入的用户可见；
 * 换成真 HTTP 请求就跨线程了，读不到未提交的数据（会被隔离）。代价是没走真实的网络栈。
 *
 * <p>每个测试方法带 @Transactional → 结束自动回滚，不污染数据库（种子用户不受影响）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    private static final String RAW_PASSWORD = "12345678";

    @Resource
    private MockMvc mockMvc;

    @Resource
    private UserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每个测试方法结束后清掉 Sa-Token 会话。
     *
     * <p><b>为什么必须单独清：</b>Sa-Token 的会话存在 Redis，而 Redis 不参与 JDBC 事务 ——
     * {@code @Transactional} 只回滚数据库，回滚不掉登录产生的 token / session。
     * 不清的话每跑一次测试就攒几个 {@code satoken:*} key，越积越多（之前已经攒了 6 个）。
     *
     * <p><b>为什么不用 {@code SaManager.getSaTokenDao().clear()}：</b>那个实现是 flushDb，
     * 会把整个 Redis 库清空 —— 将来 Redis 里放了缓存 / 计数器就会被误伤。
     * 这里只删 satoken 命名空间下的 key，精准且意图明确。
     * （{@code keys()} 是 O(N) 扫描，生产环境慎用；测试库很小，无所谓。）
     *
     * <p>⚠️ 前缀 {@code satoken:} 与 {@code application.yml} 里的 sa-token 配置一致，
     * 若将来改了 token-name / key 前缀，这里要同步改。
     */
    @AfterEach
    void clearSaTokenSessions() {
        Set<String> keys = stringRedisTemplate.keys("satoken:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /** 造一个可登录的用户，返回账号 */
    private String prepareUser(String account) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试用户");
        user.setUserRole("user");
        userService.save(user);
        return account;
    }

    @Test
    @Transactional
    void loginSuccessShouldReturnVoAndSetCookie() throws Exception {
        String account = "ut_login_ok";

        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + prepareUser(account) + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userAccount").value(account))
                // 雪花 id 必须是字符串：19 位 long 直接给 JS 会丢精度
                .andExpect(jsonPath("$.data.id").isString())
                // 明文/密文密码都绝不能出现在响应里
                .andExpect(jsonPath("$.data.userPassword").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("satoken");
        assertNotNull(cookie, "登录成功必须下发名为 satoken 的 cookie（登录态载体）");
        assertNotNull(cookie.getValue());
    }

    @Test
    @Transactional
    void loginThenGetLoginUserShouldReportSameUser() throws Exception {
        String account = "ut_login_then_me";

        MvcResult loginResult = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + prepareUser(account) + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie cookie = loginResult.getResponse().getCookie("satoken");
        assertNotNull(cookie);

        // 带着登录拿到的 cookie 再问"我是谁" —— 这是登录态真正生效的证据
        mockMvc.perform(get("/user/get/login").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userAccount").value(account));
    }

    @Test
    void wrongPasswordShouldFail() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"huangjun\",\"userPassword\":\"wrong-password-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                // 文案必须是"账号或密码错误"，不能暴露"账号存在但密码错"（否则成了账号枚举器）
                .andExpect(jsonPath("$.message").value("账号或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void notExistAccountShouldFailWithSameMessage() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"no_such_account_xyz\",\"userPassword\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    void tooShortPasswordShouldFailAsParamError() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"huangjun\",\"userPassword\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("密码长度不能少于 8 位"));
    }

    @Test
    @Transactional
    void logoutShouldInvalidateSession() throws Exception {
        String account = "ut_logout";

        MvcResult loginResult = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + prepareUser(account) + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie cookie = loginResult.getResponse().getCookie("satoken");
        assertNotNull(cookie);

        mockMvc.perform(post("/user/logout").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 拿退出前的旧 cookie 再问「我是谁」→ 服务端会话已注销，必须被拒
        mockMvc.perform(get("/user/get/login").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void getLoginUserWithoutCookieShouldBeRejected() throws Exception {
        // 计划里的验收点：不带头/不带 cookie 必须被拦
        mockMvc.perform(get("/user/get/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @Transactional
    void passwordInDbMustBeHashedNotPlainText() throws Exception {
        String account = prepareUser("ut_pwd_hashed");
        User saved = userService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUserAccount, account));

        assertNotNull(saved);
        assertNotEquals(RAW_PASSWORD, saved.getUserPassword(), "库里存的绝不是明文");
        assertTrue(PasswordUtils.matches(RAW_PASSWORD, saved.getUserPassword()),
                "存进去的密文必须能被同一个工具类校验回来");
    }

    // ==================== 用户注册 ====================

    /** 拼注册请求体（三个字段） */
    private String registerBody(String account, String password, String checkPassword) {
        return "{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + password
                + "\",\"checkPassword\":\"" + checkPassword + "\"}";
    }

    /** 按账号查库（注册断言用） */
    private User findByAccount(String account) {
        return userService.getOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUserAccount, account));
    }

    @Test
    @Transactional
    void registerSuccessShouldReturnIdStringAndStoreHashedPassword() throws Exception {
        String account = "ut_reg_ok";

        MvcResult result = mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 雪花 id 必须字符串化：19 位 long 直接给 JS 会丢精度
                .andExpect(jsonPath("$.data").isString())
                // 请求体里那两个密码字段，一个都不许回显
                .andExpect(jsonPath("$.data.userPassword").doesNotExist())
                .andExpect(jsonPath("$.data.checkPassword").doesNotExist())
                .andReturn();

        User saved = findByAccount(account);
        assertNotNull(saved, "注册成功后库里必须有这条记录");
        assertNotEquals(RAW_PASSWORD, saved.getUserPassword(), "库里存的绝不是明文");
        assertTrue(PasswordUtils.matches(RAW_PASSWORD, saved.getUserPassword()),
                "存进去的密文必须能被同一个工具类校验回来");
        assertTrue("user".equals(saved.getUserRole()), "新用户角色必须是 user");
        assertTrue(account.equals(saved.getUserName()), "未传昵称时默认取账号");

        // 响应里的 data 必须就是库里那条记录的 id（且是字符串形式）
        String returnedId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data");
        assertTrue(returnedId.equals(String.valueOf(saved.getId())),
                "响应里的 id 必须与库里一致");
    }

    @Test
    @Transactional
    void registerThenLoginShouldSucceed() throws Exception {
        String account = "ut_reg_login";

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(jsonPath("$.code").value(0));

        // 端到端：注册写进去的密文，必须能被登录链路校验通过
        MvcResult loginResult = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userAccount").value(account))
                .andReturn();
        assertNotNull(loginResult.getResponse().getCookie("satoken"), "注册后应能用该账号登录成功");
    }

    @Test
    @Transactional
    void registerDuplicateAccountShouldFail() throws Exception {
        String account = "ut_reg_dup";

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(jsonPath("$.code").value(0));

        // 同名再注册 —— 查重必须拦住
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号已存在"));
    }

    @Test
    @Transactional
    void registerWithMismatchedCheckPasswordShouldFail() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("ut_reg_mismatch", RAW_PASSWORD, "87654321")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("两次输入的密码不一致"));
    }

    @Test
    @Transactional
    void registerWithTooShortAccountShouldFail() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("abc", RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号长度必须在 4 到 20 个字符之间"));
    }

    @Test
    @Transactional
    void registerWithIllegalAccountShouldFail() throws Exception {
        // 账号含空格 —— 字符白名单必须拦住（否则中文/emoji/空格都能进库）
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("reg bad", RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号格式不合法"));
    }

    @Test
    @Transactional
    void registerWithTooShortPasswordShouldFail() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("ut_reg_pwd", "1234567", "1234567")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("密码长度不能少于 8 位"));
    }
}
