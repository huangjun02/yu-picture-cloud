package com.huang.yupicture.controller;

import com.huang.yupicture.common.BaseResponse;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.common.ResultUtils;
import com.huang.yupicture.model.dto.user.UserLoginRequest;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 *
 * <p>Controller 只做三件事：收参数 → 调 Service → 包统一返回。
 * 业务规则（校验、加密、会话）一律不下沉到这里，否则换个入口（比如小程序 API）就得重写一遍。
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户接口")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户登录。
     *
     * <p>成功时响应里会带上 Set-Cookie: satoken=...，浏览器自动保存，
     * 后续请求（同源或带 credentials 的跨域）都会带上它。
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest) {
        if (userLoginRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        LoginUserVO loginUserVO = userService.userLogin(
                userLoginRequest.getUserAccount(),
                userLoginRequest.getUserPassword());
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 退出登录。
     *
     * <p>Sa-Token 注销服务端会话，并在响应里把 satoken cookie 置空；
     * 前端再清掉本地缓存的用户信息，整条链路就干净了。
     */
    @PostMapping("/logout")
    @Operation(summary = "用户退出登录")
    public BaseResponse<Boolean> userLogout() {
        userService.userLogout();
        return ResultUtils.success(true);
    }

    /**
     * 获取当前登录用户。
     *
     * <p>不带 cookie 请求它会得到 {@code {"code":40100,...,"message":"未登录"}} ——
     * 这是验证"登录态真的生效了"的关键接口：前端刷新页面后靠它恢复用户信息。
     */
    @GetMapping("/get/login")
    @Operation(summary = "获取当前登录用户")
    public BaseResponse<LoginUserVO> getLoginUser() {
        User user = userService.getLoginUser();
        return ResultUtils.success(userService.toLoginUserVO(user));
    }
}
