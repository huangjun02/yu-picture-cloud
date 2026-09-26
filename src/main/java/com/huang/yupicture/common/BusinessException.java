package com.huang.yupicture.common;

import lombok.Getter;

/**
 * 业务异常：项目里所有「可预期的错误」统一抛它。
 *
 * <p>为什么要自定义异常，而不是直接 throw new RuntimeException？
 * <ul>
 *   <li>能携带业务错误码（40000/40100...），前端凭 code 做不同处理</li>
 *   <li>message 是「给人看的」（账号或密码错误），由 {@link GlobalExceptionHandler} 原样返回给前端</li>
 *   <li>和系统异常区分开：系统异常（NullPointerException 等）的 message 绝不能暴露给用户（可能含 SQL、路径等敏感信息）</li>
 * </ul>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，取值见 {@link ErrorCode} */
    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /** 用自定义 message 覆盖 ErrorCode 里的默认文案（错误码仍用 errorCode 的） */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
