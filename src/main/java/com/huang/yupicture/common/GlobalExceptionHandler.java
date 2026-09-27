package com.huang.yupicture.common;

import cn.dev33.satoken.exception.NotLoginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 —— 让所有异常都以统一的 BaseResponse 格式返回，前端只需认 code。
 *
 * <p>没有它会发生什么：Service 里 throw 的 BusinessException 冒到 Spring 容器，
 * 客户端收到的是 Spring Boot 默认的 500 错误页（一段 HTML/JSON 混合体，
 * 还带 stacktrace），前端 axios 拦截器解析不出 code/message，只能笼统弹「网络异常」。
 *
 * <p>分层处理：
 * <ul>
 *   <li>{@link BusinessException} → 可预期，message 直接给用户，日志记 warn</li>
 *   <li>其他 Exception → 不可预期，返回统一的「系统内部异常」，真实堆栈只写日志</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> runtimeExceptionHandler(Exception e) {
        // 这里必须打完整堆栈 —— 返回给前端的 message 是脱敏的，排查只能靠日志
        log.error("系统异常", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
    }

    /**
     * Sa-Token 未登录（token 缺失 / 无效 / 过期 / 被顶下线）统一翻译成 40100。
     *
     * <p><b>为什么必须有这个分支：</b>{@link NotLoginException} 不是 {@link BusinessException}，
     * 少了它就会落到上面的 {@code Exception} 兜底分支，未登录被报成 50000「系统内部异常」——
     * 前端拿不到正确语义，排查时也会误以为是代码炸了。
     *
     * <p>message 固定用 {@code "未登录"}，不把 Sa-Token 的原始原因（「token 已过期」等）透给前端：
     * 那些细节对用户没意义，也泄漏了登录态的实现；真要区分，看日志里的 {@code type}。
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginExceptionHandler(NotLoginException e) {
        log.warn("NotLoginException: type={}, message={}", e.getType(), e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR);
    }
}
