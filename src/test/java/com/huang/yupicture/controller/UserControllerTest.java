package com.huang.yupicture.controller;

import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.service.UserService;
import com.huang.yupicture.utils.PasswordUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

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
}
