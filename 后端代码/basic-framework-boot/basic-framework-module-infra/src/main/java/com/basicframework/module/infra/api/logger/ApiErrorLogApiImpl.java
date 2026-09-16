package com.basicframework.module.infra.api.logger;

import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.basicframework.module.infra.service.logger.ApiErrorLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * API 访问日志的 API 接口
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class ApiErrorLogApiImpl implements ApiErrorLogCommonApi {

    private final ApiErrorLogService apiErrorLogService;

    @Override
    public void createApiErrorLog(ApiErrorLogCreateReqDTO createDTO) {
        apiErrorLogService.createApiErrorLog(createDTO);
    }
}
