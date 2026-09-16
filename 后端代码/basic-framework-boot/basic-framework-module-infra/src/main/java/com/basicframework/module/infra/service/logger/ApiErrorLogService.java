package com.basicframework.module.infra.service.logger;

import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;

/**
 * API 错误日志 Service 接口（仅保留写入和清理方法）
 */
public interface ApiErrorLogService {

    /**
     * 创建 API 错误日志
     */
    void createApiErrorLog(ApiErrorLogCreateReqDTO createReqDTO);

    /**
     * 清理 exceedDay 天前的错误日志
     */
    Integer cleanErrorLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches);
}
