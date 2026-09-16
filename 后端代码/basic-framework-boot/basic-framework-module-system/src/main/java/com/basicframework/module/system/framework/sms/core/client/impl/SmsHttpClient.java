package com.basicframework.module.system.framework.sms.core.client.impl;

import java.util.Map;

/** 短信供应商客户端的最小 HTTP 出口，便于隔离网络并验证签名请求。 */
@FunctionalInterface
interface SmsHttpClient {

    String post(String url, Map<String, String> headers, String body);
}
