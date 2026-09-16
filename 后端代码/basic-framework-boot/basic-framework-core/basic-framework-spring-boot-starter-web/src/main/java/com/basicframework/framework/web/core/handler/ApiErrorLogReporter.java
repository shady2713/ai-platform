package com.basicframework.framework.web.core.handler;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeJson;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeMap;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeRequestPath;

import cn.hutool.core.map.MapUtil;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiErrorLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统异常的错误日志上报：组装 {@link ApiErrorLogCreateReqDTO} 并异步落库。
 *
 * 上报失败只记录错误日志，不影响主异常处理流程。由
 * {@link GlobalExceptionHandler} 持有，随其构造装配，不需要独立注册为 Bean。
 */
@Slf4j
public class ApiErrorLogReporter {

    private final String applicationName;

    private final ApiErrorLogCommonApi apiErrorLogApi;

    public ApiErrorLogReporter(String applicationName, ApiErrorLogCommonApi apiErrorLogApi) {
        this.applicationName = applicationName;
        this.apiErrorLogApi = apiErrorLogApi;
    }

    /**
     * 组装并异步保存一条系统异常日志；保存失败仅告警，不向上抛出。
     *
     * @param request 当前请求
     * @param e 待上报的异常
     */
    public void report(HttpServletRequest request, Exception e) {
        ApiErrorLogCreateReqDTO errorLog = new ApiErrorLogCreateReqDTO();
        try {
            buildExceptionLog(errorLog, request, e);
            apiErrorLogApi.createApiErrorLogAsync(errorLog);
        } catch (Exception logException) {
            log.error(
                    "[report][url({}) traceId({}) exceptionName({}) logExceptionName({}) 记录失败]",
                    sanitizeRequestPath(request),
                    errorLog.getTraceId(),
                    errorLog.getExceptionName(),
                    logException.getClass().getName());
        }
    }

    private void buildExceptionLog(ApiErrorLogCreateReqDTO errorLog, HttpServletRequest request, Exception e) {
        // 处理用户信息
        errorLog.setUserId(WebFrameworkUtils.getLoginUserId(request));
        errorLog.setUserType(WebFrameworkUtils.getLoginUserType(request));
        // 设置异常字段
        errorLog.setExceptionName(e.getClass().getName());
        errorLog.setExceptionMessage(e.getClass().getName());
        errorLog.setExceptionRootCauseMessage(getRootCauseClassName(e));
        errorLog.setExceptionStackTrace(format(e));
        StackTraceElement[] stackTraceElements = e.getStackTrace();
        StackTraceElement stackTraceElement = stackTraceElements.length == 0
                ? new StackTraceElement(e.getClass().getName(), "unknown", null, -1)
                : stackTraceElements[0];
        errorLog.setExceptionClassName(stackTraceElement.getClassName());
        errorLog.setExceptionFileName(stackTraceElement.getFileName());
        errorLog.setExceptionMethodName(stackTraceElement.getMethodName());
        errorLog.setExceptionLineNumber(stackTraceElement.getLineNumber());
        // 设置其它字段
        errorLog.setTraceId(TracerUtils.getTraceId());
        errorLog.setApplicationName(applicationName);
        errorLog.setRequestUrl(sanitizeRequestPath(request));
        Map<String, Object> requestParams = MapUtil.<String, Object>builder()
                .put("query", sanitizeMap(ServletUtils.getParamMap(request)))
                .put("body", sanitizeJson(ServletUtils.getBody(request)))
                .build();
        errorLog.setRequestParams(JsonUtils.toJsonString(requestParams));
        errorLog.setRequestMethod(request.getMethod());
        errorLog.setUserAgent(ServletUtils.getUserAgent(request));
        errorLog.setUserIp(ServletUtils.getClientIP(request));
        errorLog.setExceptionTime(LocalDateTime.now());
    }

    private static String getRootCauseClassName(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && visited.add(rootCause)) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getName();
    }
}
