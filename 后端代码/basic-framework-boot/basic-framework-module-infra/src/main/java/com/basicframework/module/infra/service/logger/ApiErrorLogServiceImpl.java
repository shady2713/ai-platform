package com.basicframework.module.infra.service.logger;

import static com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO.REQUEST_PARAMS_MAX_LENGTH;

import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.common.util.string.StrUtils;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO;
import com.basicframework.module.infra.dal.mysql.logger.ApiErrorLogMapper;
import com.basicframework.module.infra.enums.logger.ApiErrorLogProcessStatusEnum;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * API 错误日志 Service 实现类（仅保留写入和清理方法）
 */
@Service
@Validated
@Slf4j
@RequiredArgsConstructor
public class ApiErrorLogServiceImpl implements ApiErrorLogService {

    private final ApiErrorLogMapper apiErrorLogMapper;

    @Override
    public void createApiErrorLog(ApiErrorLogCreateReqDTO createDTO) {
        ApiErrorLogDO apiErrorLog = BeanUtils.toBean(createDTO, ApiErrorLogDO.class)
                .setProcessStatus(ApiErrorLogProcessStatusEnum.INIT.getStatus());
        apiErrorLog.setRequestParams(StrUtils.maxLength(apiErrorLog.getRequestParams(), REQUEST_PARAMS_MAX_LENGTH));
        try {
            apiErrorLogMapper.insert(apiErrorLog);
        } catch (Exception ex) {
            log.error(
                    "[createApiErrorLog][traceId({}) requestUrl({}) exceptionName({}) logExceptionName({}) 记录失败]",
                    createDTO.getTraceId(),
                    createDTO.getRequestUrl(),
                    createDTO.getExceptionName(),
                    ex.getClass().getName());
        }
    }

    @Override
    @SuppressWarnings("DuplicatedCode")
    public Integer cleanErrorLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches) {
        int count = 0;
        LocalDateTime expireDate = LocalDateTime.now().minusDays(exceedDay);
        for (int i = 0; i < maxBatches; i++) {
            int deleteCount = apiErrorLogMapper.deleteByCreateTimeLt(expireDate, deleteLimit);
            count += deleteCount;
            if (deleteCount < deleteLimit) {
                break;
            }
        }
        return count;
    }
}
