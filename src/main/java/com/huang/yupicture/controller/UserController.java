package com.huang.yupicture.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.huang.yupicture.common.BaseResponse;
import com.huang.yupicture.common.BusinessException;
import com.huang.yupicture.common.ErrorCode;
import com.huang.yupicture.common.ResultUtils;
import com.huang.yupicture.model.dto.user.UserAddRequest;
import com.huang.yupicture.model.dto.user.UserLoginRequest;
import com.huang.yupicture.model.dto.user.UserQueryRequest;
import com.huang.yupicture.model.dto.user.UserRegisterRequest;
import com.huang.yupicture.model.dto.user.UserUpdateRequest;
import com.huang.yupicture.model.entity.User;
import com.huang.yupicture.model.vo.LoginUserVO;
import com.huang.yupicture.model.vo.UserVO;
import com.huang.yupicture.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 *
 * <p>Controller 只做三件事：收参数 → 调 Service → 包统一返回。
 * 业务规则（校验、加密、会话、权限）一律不下沉到这里，否则换个入口（比如小程序 API）就得重写一遍。
 *
 * <p>所以这里的「管理员接口」区域<b>看不到任何权限判断</b>——{@code checkAdminUser()} 在 Service 里。
 * 在 Controller 再判一次不会更安全，只会多一处将来忘记同步的地方。
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

    /**
     * 用户注册。
     *
     * <p>返回新用户的 id（字符串形式）。<b>这里不自动登录</b>：注册和登录是两个动作，
     * 前端拿到 id 后自己跳登录页 —— 自动登录会糊掉"注册成功"这个语义边界。
     *
     * <p>返回值用 String 而不是 Long，原因同 {@code LoginUserVO#id}：
     * 19 位雪花 id 序列化成数字后，JS 端静默丢精度。
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public BaseResponse<String> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        return ResultUtils.success(userService.userRegister(userRegisterRequest));
    }

    // ==================== 管理员接口 ====================
    // 这几个接口都不是"给用户自己用"的：普通用户调用会拿到 40101（无权限），
    // 未登录调用会拿到 40100（被 SaTokenConfig 的拦截器挡在方法之外）。

    /**
     * 【管理员】新增用户（管理员替别人开号）。
     *
     * <p>返回新用户 id（字符串形式，原因同注册接口）。
     */
    @PostMapping("/add")
    @Operation(summary = "管理员新增用户")
    public BaseResponse<String> userAdd(@RequestBody UserAddRequest userAddRequest) {
        if (userAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        return ResultUtils.success(userService.userAdd(userAddRequest));
    }

    /**
     * 【管理员】更新用户资料（昵称 / 头像 / 简介 / 角色）。
     *
     * <p>请求体里<b>没传的字段表示"不改"</b>：传 {@code {"id":"...","userName":"新名字"}}
     * 只改昵称，不会顺手把头像、简介清空。密码和账号不在这里改（原因见 UserUpdateRequest 的注释）。
     */
    @PostMapping("/update")
    @Operation(summary = "管理员更新用户")
    public BaseResponse<Boolean> userUpdate(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        return ResultUtils.success(userService.userUpdate(userUpdateRequest));
    }

    /**
     * 【管理员】删除用户（逻辑删除：库里那行还在，只是 isDelete 置 1）。
     *
     * <p>id 走查询参数而不是请求体（{@code POST /user/delete?id=2104...}）：
     * 它只是"标识哪个用户"，不是一份数据，为它单建一个请求体类不划算。
     * 前端调用形如 {@code request.post('/user/delete', null, { params: { id } })}。
     */
    @PostMapping("/delete")
    @Operation(summary = "管理员删除用户")
    public BaseResponse<Boolean> userDelete(@RequestParam String id) {
        return ResultUtils.success(userService.userDelete(id));
    }

    /**
     * 【管理员】按 id 查询单个用户（脱敏：不含密码）。
     *
     * <p>id 同样走查询参数，理由同上。
     */
    @PostMapping("/get")
    @Operation(summary = "管理员按 id 查询用户")
    public BaseResponse<UserVO> getUserById(@RequestParam String id) {
        return ResultUtils.success(userService.getUserById(id));
    }

    /**
     * 【管理员】分页查询用户（可按账号 / 昵称 / 简介模糊、按角色精确过滤）。
     *
     * <p>pageSize 传超过 20 会被 Service 夹到 20 —— 前端传多大都拖不垮数据库，
     * 所以这里不需要再挡一次。
     */
    @PostMapping("/list/page")
    @Operation(summary = "管理员分页查询用户")
    public BaseResponse<Page<UserVO>> listUserByPage(@RequestBody UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        return ResultUtils.success(userService.listUserByPage(userQueryRequest));
    }
}
