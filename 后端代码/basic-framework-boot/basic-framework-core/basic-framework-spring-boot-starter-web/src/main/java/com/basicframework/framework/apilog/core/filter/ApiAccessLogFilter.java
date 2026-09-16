package com.basicframework.framework.apilog.core.filter;

import static com.basicframework.framework.apilog.core.interceptor.ApiAccessLogInterceptor.ATTRIBUTE_HANDLER_METHOD;
import static com.basicframework.framework.common.util.json.JsonUtils.toJsonString;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeJson;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeMap;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeRequestPath;
import static com.basicframework.framework.web.core.util.SensitiveDataSanitizer.sanitizeResult;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.apilog.core.enums.OperateTypeEnum;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.monitor.TracerUtils;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.web.config.WebProperties;
import com.basicframework.framework.web.core.filter.ApiRequestFilter;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import com.basicframework.module.infra.api.logger.ApiAccessLogCommonApi;
import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

/**
 * API 访问日志 Filter
 *
 * 目的：记录 API 访问日志到数据库中
 */
@Slf4j
public class ApiAccessLogFilter extends ApiRequestFilter {

    private final String applicationName;

    private final ApiAccessLogCommonApi apiAccessLogApi;

    public ApiAccessLogFilter(
            WebProperties webProperties, String applicationName, ApiAccessLogCommonApi apiAccessLogApi) {
        super(webProperties);
        this.applicationName = applicationName;
        this.apiAccessLogApi = apiAccessLogApi;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        LocalDateTime beginTime = LocalDateTime.now();
        Map<String, String> queryString = ServletUtils.getParamMap(request);
        String requestBody = ServletUtils.isJsonRequest(request) ? ServletUtils.getBody(request) : null;

        try {
            filterChain.doFilter(request, response);
            createApiAccessLog(request, beginTime, queryString, requestBody, null);
        } catch (Exception ex) {
            createApiAccessLog(request, beginTime, queryString, requestBody, ex);
            throw ex;
        }
    }

    private void createApiAccessLog(
            HttpServletRequest request,
            LocalDateTime beginTime,
            Map<String, String> queryString,
            String requestBody,
            Exception ex) {
        ApiAccessLogCreateReqDTO accessLog = new ApiAccessLogCreateReqDTO();
        try {
            boolean enable = buildApiAccessLog(accessLog, request, beginTime, queryString, requestBody, ex);
            if (!enable) {
                return;
            }
            apiAccessLogApi.createApiAccessLogAsync(accessLog);
        } catch (Exception exception) {
            log.error(
                    "[createApiAccessLog][url({}) traceId({}) resultCode({}) exceptionName({}) 记录失败]",
                    sanitizeRequestPath(request),
                    accessLog.getTraceId(),
                    accessLog.getResultCode(),
                    exception.getClass().getName());
        }
    }

    private boolean buildApiAccessLog(
            ApiAccessLogCreateReqDTO accessLog,
            HttpServletRequest request,
            LocalDateTime beginTime,
            Map<String, String> queryString,
            String requestBody,
            Exception ex) {
        HandlerMethod handlerMethod = (HandlerMethod) request.getAttribute(ATTRIBUTE_HANDLER_METHOD);
        ApiAccessLog accessLogAnnotation = null;
        if (handlerMethod != null) {
            accessLogAnnotation = handlerMethod.getMethodAnnotation(ApiAccessLog.class);
            if (accessLogAnnotation != null && BooleanUtil.isFalse(accessLogAnnotation.enable())) {
                return false;
            }
        }

        accessLog
                .setUserId(WebFrameworkUtils.getLoginUserId(request))
                .setUserType(WebFrameworkUtils.getLoginUserType(request));
        CommonResult<?> result = WebFrameworkUtils.getCommonResult(request);
        if (result != null) {
            accessLog.setResultCode(result.getCode()).setResultMsg(result.getMsg());
        } else if (ex != null) {
            accessLog
                    .setResultCode(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode())
                    .setResultMsg(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getMsg());
        } else {
            accessLog.setResultCode(GlobalErrorCodeConstants.SUCCESS.getCode()).setResultMsg("");
        }
        accessLog
                .setTraceId(TracerUtils.getTraceId())
                .setApplicationName(applicationName)
                .setRequestUrl(sanitizeRequestPath(request))
                .setRequestMethod(request.getMethod())
                .setUserAgent(ServletUtils.getUserAgent(request))
                .setUserIp(ServletUtils.getClientIP(request));
        String[] sanitizeKeys = accessLogAnnotation != null ? accessLogAnnotation.sanitizeKeys() : null;
        Boolean requestEnable = accessLogAnnotation != null ? accessLogAnnotation.requestEnable() : Boolean.TRUE;
        if (!BooleanUtil.isFalse(requestEnable)) {
            Map<String, Object> requestParams = MapUtil.<String, Object>builder()
                    .put("query", sanitizeMap(queryString, sanitizeKeys))
                    .put("body", sanitizeJson(requestBody, sanitizeKeys))
                    .build();
            accessLog.setRequestParams(toJsonString(requestParams));
        }
        Boolean responseEnable = accessLogAnnotation != null ? accessLogAnnotation.responseEnable() : Boolean.FALSE;
        if (BooleanUtil.isTrue(responseEnable)) {
            accessLog.setResponseBody(sanitizeResult(result, sanitizeKeys));
        }
        accessLog.setBeginTime(beginTime).setEndTime(LocalDateTime.now()).setDuration((int)
                LocalDateTimeUtil.between(accessLog.getBeginTime(), accessLog.getEndTime(), ChronoUnit.MILLIS));

        if (handlerMethod != null) {
            Tag tagAnnotation = handlerMethod.getBeanType().getAnnotation(Tag.class);
            Operation operationAnnotation = handlerMethod.getMethodAnnotation(Operation.class);
            String operateModule =
                    accessLogAnnotation != null && StrUtil.isNotBlank(accessLogAnnotation.operateModule())
                            ? accessLogAnnotation.operateModule()
                            : tagAnnotation != null
                                    ? StrUtil.nullToDefault(tagAnnotation.name(), tagAnnotation.description())
                                    : null;
            String operateName = accessLogAnnotation != null && StrUtil.isNotBlank(accessLogAnnotation.operateName())
                    ? accessLogAnnotation.operateName()
                    : operationAnnotation != null ? operationAnnotation.summary() : null;
            OperateTypeEnum operateType = accessLogAnnotation != null && accessLogAnnotation.operateType().length > 0
                    ? accessLogAnnotation.operateType()[0]
                    : parseOperateLogType(request);
            accessLog
                    .setOperateModule(operateModule)
                    .setOperateName(operateName)
                    .setOperateType(operateType.getType());
        }
        return true;
    }

    private static OperateTypeEnum parseOperateLogType(HttpServletRequest request) {
        RequestMethod requestMethod = RequestMethod.resolve(request.getMethod());
        if (requestMethod == null) {
            return OperateTypeEnum.OTHER;
        }
        switch (requestMethod) {
            case GET:
                return OperateTypeEnum.GET;
            case POST:
                return OperateTypeEnum.CREATE;
            case PUT:
                return OperateTypeEnum.UPDATE;
            case DELETE:
                return OperateTypeEnum.DELETE;
            default:
                return OperateTypeEnum.OTHER;
        }
    }
}
