package com.basicframework.module.system.api.logger;

import com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO;
import com.basicframework.module.system.service.logger.OperateLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 操作日志 API 实现类
 *
 */
@Service
@Validated
@RequiredArgsConstructor
public class OperateLogApiImpl implements OperateLogCommonApi {

    private final OperateLogService operateLogService;

    @Override
    public void createOperateLog(OperateLogCreateReqDTO createReqDTO) {
        operateLogService.createOperateLog(createReqDTO);
    }
}
