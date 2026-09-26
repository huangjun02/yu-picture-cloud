package com.huang.yupicture.common;

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
}
