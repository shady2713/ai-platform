package com.basicframework.module.infra.service.logger;

import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;

/**
 * API 访问日志 Service 接口（仅保留写入和清理方法）
 */
public interface ApiAccessLogService {

    /**
     * 创建 API 访问日志
     */
    void createApiAccessLog(ApiAccessLogCreateReqDTO createReqDTO);

    /**
     * 清理 exceedDay 天前的访问日志
     */
    Integer cleanAccessLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches);
}
