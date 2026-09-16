package com.basicframework.framework.web.core.handler.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * Error codes used by GlobalExceptionHandlerTest to exercise the suffix-based HTTP status
 * derivation (ADR 0003) without depending on business modules. Codes stay on this module's
 * test classpath, so they never reach the server-wide ErrorCodeUniquenessTest scan.
 */
public interface TestStatusErrorCodeConstants {

    ErrorCode TEST_FOO_NOT_EXISTS = new ErrorCode(1_999_000_001, "foo not exists");
    ErrorCode TEST_FOO_EXISTS = new ErrorCode(1_999_000_002, "foo exists");
    ErrorCode TEST_FOO_INVALID_STATE = new ErrorCode(1_999_000_003, "foo invalid state");
}
