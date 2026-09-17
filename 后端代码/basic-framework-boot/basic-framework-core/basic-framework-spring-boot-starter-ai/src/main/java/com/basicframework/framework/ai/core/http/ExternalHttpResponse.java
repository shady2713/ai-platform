package com.basicframework.framework.ai.core.http;

import java.util.Map;

/** 出站响应：只保留状态、必要的响应头与（受大小上限约束的）响应体。 */
public record ExternalHttpResponse(int status, Map<String, String> headers, byte[] body, long declaredLength) {

    /** 响应是否为 2xx。 */
    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }
}
