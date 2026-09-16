package com.basicframework.framework.common.exception;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import lombok.Data;

/**
 * 错误码对象
 *
 * 全局错误码，占用 [0, 999], 参见 {@link GlobalErrorCodeConstants}
 * 业务异常错误码占用 [1 000 000 000, +∞)，由所属模块的 ErrorCodeConstants 分段维护，装配层负责校验全库唯一性。
 *
 * 错误码设计成对象，便于后续扩展国际化、文案映射等能力。
 */
@Data
public class ErrorCode {

    /**
     * 错误码
     */
    private final Integer code;
    /**
     * 错误提示
     */
    private final String msg;

    public ErrorCode(Integer code, String message) {
        this.code = code;
        this.msg = message;
    }
}
