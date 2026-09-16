package com.basicframework.module.infra.service.job;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.mysql.job.JobLogMapper;
import com.basicframework.module.infra.dal.mysql.job.JobLogQuery;
import com.basicframework.module.infra.enums.job.JobLogStatusEnum;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * Job 日志 Service 实现类
 *
 */
@Service
@Validated
@Slf4j
@RequiredArgsConstructor
public class JobLogServiceImpl implements JobLogService {

    private final JobLogMapper jobLogMapper;

    @Override
    public Long createJobLog(
            Long jobId, LocalDateTime beginTime, String jobHandlerName, String jobHandlerParam, Integer executeIndex) {
        JobLogDO log = JobLogDO.builder()
                .jobId(jobId)
                .handlerName(jobHandlerName)
                .handlerParam(jobHandlerParam)
                .executeIndex(executeIndex)
                .beginTime(beginTime)
                .status(JobLogStatusEnum.RUNNING.getStatus())
                .build();
        jobLogMapper.insert(log);
        return log.getId();
    }

    @Override
    @Async
    public void updateJobLogResultAsync(
            Long logId, LocalDateTime endTime, Integer duration, boolean success, String result) {
        try {
            JobLogDO updateObj = JobLogDO.builder()
                    .id(logId)
                    .endTime(endTime)
                    .duration(duration)
                    .status(success ? JobLogStatusEnum.SUCCESS.getStatus() : JobLogStatusEnum.FAILURE.getStatus())
                    .result(result)
                    .build();
            jobLogMapper.updateById(updateObj);
        } catch (Exception ex) {
            log.error(
                    "[updateJobLogResultAsync][logId({}) endTime({}) duration({}) success({}) stackTrace({})]",
                    logId,
                    endTime,
                    duration,
                    success,
                    format(ex));
        }
    }

    @Override
    @SuppressWarnings("DuplicatedCode")
    public Integer cleanJobLog(Integer exceedDay, Integer deleteLimit, Integer maxBatches) {
        int count = 0;
        LocalDateTime expireDate = LocalDateTime.now().minusDays(exceedDay);
        // 循环删除，直到没有满足条件的数据
        for (int i = 0; i < maxBatches; i++) {
            int deleteCount = jobLogMapper.deleteByCreateTimeLt(expireDate, deleteLimit);
            count += deleteCount;
            // 达到删除预期条数，说明到底了
            if (deleteCount < deleteLimit) {
                break;
            }
        }
        return count;
    }

    @Override
    public JobLogDO getJobLog(Long id) {
        return jobLogMapper.selectById(id);
    }

    @Override
    public PageResult<JobLogDO> getJobLogPage(PageParam pageParam, JobLogQuery query) {
        return jobLogMapper.selectPage(pageParam, query);
    }
}
