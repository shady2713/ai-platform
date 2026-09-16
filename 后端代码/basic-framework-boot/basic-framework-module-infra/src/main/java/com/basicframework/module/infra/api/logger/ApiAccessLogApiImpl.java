package com.basicframework.module.infra.api.logger;

import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import com.basicframework.module.infra.service.logger.ApiAccessLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * API 访问日志的 API 实现类
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class ApiAccessLogApiImpl implements ApiAccessLogCommonApi {

    private final ApiAccessLogService apiAccessLogService;

    @Override
    public void createApiAccessLog(ApiAccessLogCreateReqDTO createDTO) {
        apiAccessLogService.createApiAccessLog(createDTO);
    }
}
