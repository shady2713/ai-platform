package com.basicframework.module.infra.service.logger;

import static com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO.REQUEST_PARAMS_MAX_LENGTH;
import static com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO.RESULT_MSG_MAX_LENGTH;

import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.common.util.string.StrUtils;
import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO;
import com.basicframework.module.infra.dal.mysql.logger.ApiAccessLogMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * API 访问日志 Service 实现类（仅保留写入和清理方法）
 */
@Service
@Validated
@RequiredArgsConstructor
public class ApiAccessLogServiceImpl implements ApiAccessLogService {

    private final ApiAccessLogMapper apiAccessLogMapper;

    @Override
    public void createApiAccessLog(ApiAccessLogCreateReqDTO createDTO) {
        ApiAccessLogDO apiAccessLog = BeanUtils.toBean(createDTO, ApiAccessLogDO.class);
        apiAccessLog.setRequestParams(StrUtils.maxLength(apiAccessLog.getRequestParams(), REQUEST_PARAMS_MAX_LENGTH));
        apiAccessLog.setResultMsg(StrUtils.maxLength(apiAccessLog.getResultMsg(), RESULT_MSG_MAX_LENGTH));
        apiAccessLogMapper.insert(apiAccessLog);
    }

    @Override
    @SuppressWarnings("DuplicatedCode")
    public Integer cleanAccessLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches) {
        int count = 0;
        LocalDateTime expireDate = LocalDateTime.now().minusDays(exceedDay);
        for (int i = 0; i < maxBatches; i++) {
            int deleteCount = apiAccessLogMapper.deleteByCreateTimeLt(expireDate, deleteLimit);
            count += deleteCount;
            if (deleteCount < deleteLimit) {
                break;
            }
        }
        return count;
    }
}
