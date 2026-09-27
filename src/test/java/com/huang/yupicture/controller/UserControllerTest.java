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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
     * 只用来验证"逻辑删除"这件事。
     *
     * <p>MyBatis-Plus 的 {@code @TableLogic} 会给所有查询自动补上 {@code isDelete = 0}，
     * 所以走 Service / Mapper 永远看不见被删的行 —— 想证明"行还在、只是标记变了"，
     * 只能绕过 MP 直接发 SQL。
     */
    @Resource
    private JdbcTemplate jdbcTemplate;

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

    /** 造一个管理员账号，返回账号 */
    private String prepareAdmin(String account) {
        User user = new User();
        user.setUserAccount(account);
        user.setUserPassword(PasswordUtils.encrypt(RAW_PASSWORD));
        user.setUserName("测试管理员");
        user.setUserRole("admin");
        userService.save(user);
        return account;
    }

    /** 登录并返回 satoken cookie —— 管理员用例都得先"变成某个人" */
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

    /** 拼新增用户请求体；userName / role 传 null 表示不带这个字段（测默认值） */
    private String addBody(String account, String password, String userName, String role) {
        StringBuilder json = new StringBuilder("{\"userAccount\":\"").append(account)
                .append("\",\"userPassword\":\"").append(password).append("\"");
        if (userName != null) {
            json.append(",\"userName\":\"").append(userName).append("\"");
        }
        if (role != null) {
            json.append(",\"userRole\":\"").append(role).append("\"");
        }
        return json.append("}").toString();
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

    // ==================== 全局登录拦截（SaTokenConfig） ====================

    /**
     * 证明拦截器真的在拦。
     *
     * <p>为什么拿 /user/logout 当证据：{@code userLogout()} 是幂等的（内部 StpUtil.logout()
     * 不校验登录态），所以<b>没有拦截器时未登录调它照样返回 code=0</b>。
     * 这里能拿到 40100，只可能来自拦截器 —— 用例失败即说明拦截器没生效。
     */
    @Test
    void interceptorShouldBlockProtectedEndpointWhenNotLoggedIn() throws Exception {
        mockMvc.perform(post("/user/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /**
     * 证明白名单真的放行：未登录调注册接口应走到业务校验（40000 参数错误），
     * 而不是被拦截器挡成 40100 —— 否则新用户根本注册不了。
     */
    @Test
    void whitelistShouldLetRegisterThroughWhenNotLoggedIn() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("abc", RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号长度必须在 4 到 20 个字符之间"));
    }

    // ==================== 管理员接口 ====================

    @Test
    @Transactional
    void adminAddUserShouldSucceedAndStoreHashedPassword() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_add"));
        String newAccount = "ut_added_by_admin";

        mockMvc.perform(post("/user/add")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(newAccount, RAW_PASSWORD, "被管理员建的人", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 雪花 id 必须字符串化（19 位 long 给 JS 会丢精度）
                .andExpect(jsonPath("$.data").isString());

        User saved = findByAccount(newAccount);
        assertNotNull(saved, "新增成功后库里必须有记录");
        assertNotEquals(RAW_PASSWORD, saved.getUserPassword(), "库里存的绝不是明文");
        assertTrue(PasswordUtils.matches(RAW_PASSWORD, saved.getUserPassword()),
                "存进去的密文必须能被同一个工具类校验回来 —— 否则这个号登录不了");
        assertTrue("被管理员建的人".equals(saved.getUserName()));
    }

    @Test
    @Transactional
    void adminAddWithAdminRoleAndNoNicknameShouldApplyDefaults() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_add2"));
        String newAccount = "ut_added_admin";

        mockMvc.perform(post("/user/add")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(newAccount, RAW_PASSWORD, null, "admin")))
                .andExpect(jsonPath("$.code").value(0));

        User saved = findByAccount(newAccount);
        assertTrue("admin".equals(saved.getUserRole()), "指定 admin 角色应生效");
        assertTrue(newAccount.equals(saved.getUserName()), "未传昵称时默认取账号（与注册一致）");
    }

    @Test
    @Transactional
    void adminAddWithIllegalRoleShouldFail() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_add3"));

        // 角色白名单：不能造出 "superadmin" 这种谁都不认识的脏角色
        mockMvc.perform(post("/user/add")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody("ut_bad_role", RAW_PASSWORD, null, "superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("角色只能是 user 或 admin"));

        assertNull(findByAccount("ut_bad_role"), "校验失败的请求不得产生数据");
    }

    /**
     * 普通用户调每一个管理员接口都必须被拒。
     *
     * <p>为什么五个接口全测一遍：{@code checkAdminUser()} 是逐个方法手动调的，
     * 漏掉某一个方法是很现实的疏忽，而漏掉的那个就是一条越权通道。
     * 这条用例的价值在于"逐个点名"，不是"测一个代表"。
     */
    @Test
    @Transactional
    void normalUserCallingAdminApiShouldBeForbidden() throws Exception {
        Cookie userCookie = loginAndGetCookie(prepareUser("ut_normal_guy"));

        mockMvc.perform(post("/user/add").cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody("ut_should_not_exist", RAW_PASSWORD, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/user/update").cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"userName\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/user/delete").cookie(userCookie).param("id", "1"))
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/user/get").cookie(userCookie).param("id", "1"))
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/user/list/page").cookie(userCookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(40101));

        // 光看返回码不够：越权请求必须"什么都没做"，不能是"做完了再报错"
        assertNull(findByAccount("ut_should_not_exist"), "被拒的越权请求不得产生任何数据");
    }

    @Test
    void notLoggedInCallingAdminApiShouldBeRejected() throws Exception {
        // 未登录 → 拦截器先挡（40100），压根走不到 checkAdminUser 的 40101
        mockMvc.perform(post("/user/list/page")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @Transactional
    void adminUpdateShouldOnlyChangeProvidedFields() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_upd"));
        String account = prepareUser("ut_upd_target");
        User before = findByAccount(account);
        // 先给它放上头像和简介，用来验证"没传的字段不会被清空"
        before.setUserAvatar("http://example.com/a.png");
        before.setUserProfile("原简介");
        userService.updateById(before);

        mockMvc.perform(post("/user/update")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + before.getId() + "\",\"userName\":\"改过的昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        User after = userService.getById(before.getId());
        assertTrue("改过的昵称".equals(after.getUserName()), "昵称应被改掉");
        // 这是"局部更新"和"整体覆盖"的分界线：没传的字段必须原封不动
        assertTrue("http://example.com/a.png".equals(after.getUserAvatar()), "没传的字段不该被清空");
        assertTrue("原简介".equals(after.getUserProfile()), "没传的字段不该被清空");
    }

    @Test
    @Transactional
    void adminShouldNotDowngradeSelfButCanRenameSelf() throws Exception {
        String adminAccount = prepareAdmin("ut_admin_self");
        Cookie adminCookie = loginAndGetCookie(adminAccount);
        String selfId = String.valueOf(findByAccount(adminAccount).getId());

        mockMvc.perform(post("/user/update")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + selfId + "\",\"userRole\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("不能取消自己的管理员身份"));

        // 但改自己的昵称是允许的 —— 拦的是"降级"，不是"改自己"
        mockMvc.perform(post("/user/update")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + selfId + "\",\"userName\":\"管理员的新昵称\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertTrue("管理员的新昵称".equals(userService.getById(Long.parseLong(selfId)).getUserName()));
    }

    @Test
    @Transactional
    void adminShouldNotDeleteSelf() throws Exception {
        String adminAccount = prepareAdmin("ut_admin_del_self");
        Cookie adminCookie = loginAndGetCookie(adminAccount);
        String selfId = String.valueOf(findByAccount(adminAccount).getId());

        mockMvc.perform(post("/user/delete").cookie(adminCookie).param("id", selfId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("不能删除自己"));
    }

    @Test
    @Transactional
    void adminDeleteShouldBeLogicalNotPhysical() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_del"));
        String account = prepareUser("ut_del_target");
        long targetId = findByAccount(account).getId();

        mockMvc.perform(post("/user/delete").cookie(adminCookie).param("id", String.valueOf(targetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 应用侧查不到了（@TableLogic 自动补 isDelete = 0）
        assertNull(userService.getById(targetId), "删除后 Service 侧应查不到");
        mockMvc.perform(post("/user/get").cookie(adminCookie).param("id", String.valueOf(targetId)))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("用户不存在"));

        // 但库里那行还在 —— 绕过 MP 直接发 SQL 才能看到真相
        Integer isDelete = jdbcTemplate.queryForObject(
                "SELECT isDelete FROM `user` WHERE id = ?", Integer.class, targetId);
        assertNotNull(isDelete, "逻辑删除不该物理删行：库里必须还能查到这一行");
        assertEquals(1, isDelete.intValue(), "逻辑删除的标记必须是 isDelete = 1");
    }

    @Test
    @Transactional
    void listUserByPageShouldCapPageSizeAndNotLeakPassword() throws Exception {
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_list"));
        // 造 25 个同前缀用户，用来验证 pageSize 真的被夹住了（25 > 上限 20）
        for (int i = 0; i < 25; i++) {
            prepareUser("ut_list_" + i);
        }

        MvcResult result = mockMvc.perform(post("/user/list/page")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        // 故意传一个荒唐的 pageSize：Service 必须夹到 20，不能让数据库真去查 9999 条
                        .content("{\"pageNum\":1,\"pageSize\":9999,\"userAccount\":\"ut_list_\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 脱敏：列表里绝不能出现密码字段
                .andExpect(jsonPath("$.data.records[0].userPassword").doesNotExist())
                // 雪花 id 字符串化
                .andExpect(jsonPath("$.data.records[0].id").isString())
                .andReturn();

        int size = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.data.records.length()");
        assertEquals(20, size, "pageSize 9999 必须被夹到上限 20");
    }

    /**
     * 账号被逻辑删除后，同一个账号必须能重新注册。
     *
     * <p>这条用例依赖 {@code sql/04_alter_user_unique_index.sql} 里的复合唯一索引
     * {@code (userAccount, isDelete)}。如果索引还是单列 {@code uk_userAccount}，
     * 这里会拿到 40000「账号已存在」—— 用例失败即说明迁移脚本没执行。
     */
    @Test
    @Transactional
    void deletedAccountCanRegisterAgain() throws Exception {
        String account = "ut_re_register";
        Cookie adminCookie = loginAndGetCookie(prepareAdmin("ut_admin_rereg"));

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(jsonPath("$.code").value(0));

        String targetId = String.valueOf(findByAccount(account).getId());
        mockMvc.perform(post("/user/delete").cookie(adminCookie).param("id", targetId))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(account, RAW_PASSWORD, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
