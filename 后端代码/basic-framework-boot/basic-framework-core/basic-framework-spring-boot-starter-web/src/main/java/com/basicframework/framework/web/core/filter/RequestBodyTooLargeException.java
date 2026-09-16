package com.basicframework.framework.web.core.filter;

import java.io.IOException;

/** JSON 请求体超过缓存安全上限。 */
final class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;
}
