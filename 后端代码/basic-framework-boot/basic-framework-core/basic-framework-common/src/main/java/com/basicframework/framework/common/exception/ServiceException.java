package com.basicframework.framework.common.exception;

import lombok.Getter;

/**
 * 业务逻辑异常 Exception
 */
@Getter
public final class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码
     *
     */
    private final Integer code;

    public ServiceException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMsg());
    }

    public ServiceException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回允许展示给调用方的业务文案。调用方应使用本方法显式表达该信任边界，基础设施异常不得转换为该文案。
     *
     * @return 业务错误文案
     */
    public String getPublicMessage() {
        return super.getMessage();
    }
}
