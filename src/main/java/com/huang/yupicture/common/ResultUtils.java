package com.huang.yupicture.common;

public class ResultUtils {

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    public static <T> BaseResponse<T> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 错误响应：错误码 + 更具体的提示。
     *
     * <p><b>为什么需要这个重载：</b>{@code error(ErrorCode)} 只能用错误码自带的标准提示，
     * 而业务里经常要"同一个错误码 + 更具体的原因"—— 比如 40000 底下可能是十几种不同的参数问题，
     * 只说"请求参数错误"用户不知道改什么。用 {@code error(int, String)} 也能做到，
     * 但调用处要写 {@code code.getCode()}，等于把 ErrorCode 拆成 int 用；
     * 这个重载让调用处保持"传枚举 + 传提示"的表达方式。
     */
    public static <T> BaseResponse<T> error(ErrorCode errorCode, String message) {
        return error(errorCode.getCode(), message);
    }

    public static <T> BaseResponse<T> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }
}
