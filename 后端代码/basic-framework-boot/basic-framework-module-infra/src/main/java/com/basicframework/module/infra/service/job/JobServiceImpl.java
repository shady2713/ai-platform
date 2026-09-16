package com.basicframework.module.infra.service.job;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.containsAny;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.*;
import static com.basicframework.module.infra.enums.LogRecordConstants.*;

import cn.hutool.extra.spring.SpringUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.framework.quartz.core.scheduler.SchedulerManager;
import com.basicframework.framework.quartz.core.util.CronUtils;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import com.basicframework.module.infra.dal.mysql.job.JobMapper;
import com.basicframework.module.infra.enums.job.JobStatusEnum;
import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** 定时任务配置与 Quartz 持久化状态的协调服务；数据库保存期望状态，同步过程负责修复调度状态漂移。 */
@Service
@Validated
@Slf4j
public class JobServiceImpl implements JobService {

    private final JobMapper jobMapper;
    private final SchedulerManager schedulerManager;

    public JobServiceImpl(JobMapper jobMapper, SchedulerManager schedulerManager) {
        this.jobMapper = jobMapper;
        this.schedulerManager = schedulerManager;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_JOB_TYPE,
            subType = INFRA_JOB_CREATE_SUB_TYPE,
            bizNo = "{{#job.id}}",
            success = INFRA_JOB_CREATE_SUCCESS)
    public Long createJob(JobDO job) throws SchedulerException {
        validateCronExpression(job.getCronExpression());
        if (jobMapper.selectByHandlerName(job.getHandlerName()) != null) {
            throw exception(JOB_HANDLER_EXISTS);
        }
        validateJobHandlerExists(job.getHandlerName());

        job.setStatus(JobStatusEnum.INIT.getStatus());
        fillJobMonitorTimeoutEmpty(job);
        jobMapper.insert(job);

        schedulerManager.addJob(
                job.getId(),
                job.getHandlerName(),
                job.getHandlerParam(),
                job.getCronExpression(),
                job.getRetryCount(),
                job.getRetryInterval());
        JobDO updateObj = JobDO.builder()
                .id(job.getId())
                .status(JobStatusEnum.NORMAL.getStatus())
                .build();
        jobMapper.updateById(updateObj);

        // 记录操作日志上下文
        LogRecordContext.putVariable("job", job);
        return job.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_JOB_TYPE,
            subType = INFRA_JOB_UPDATE_SUB_TYPE,
            bizNo = "{{#updateObj.id}}",
            success = INFRA_JOB_UPDATE_SUCCESS)
    public void updateJob(JobDO updateObj) throws SchedulerException {
        validateCronExpression(updateObj.getCronExpression());
        JobDO job = validateJobExists(updateObj.getId());
        // 暂停任务禁止直接修改，避免 Quartz 更新操作意外恢复调度。
        if (!job.getStatus().equals(JobStatusEnum.NORMAL.getStatus())) {
            throw exception(JOB_UPDATE_ONLY_NORMAL_STATUS);
        }
        validateJobHandlerExists(updateObj.getHandlerName());
        if (!Objects.equals(job.getHandlerName(), updateObj.getHandlerName())
                && jobMapper.selectByHandlerName(updateObj.getHandlerName()) != null) {
            throw exception(JOB_HANDLER_EXISTS);
        }

        fillJobMonitorTimeoutEmpty(updateObj);
        jobMapper.updateById(updateObj);

        // 处理器改名需要重建 JobKey；持久化记录缺失时直接修复。
        if (!Objects.equals(job.getHandlerName(), updateObj.getHandlerName())) {
            schedulerManager.deleteJob(job.getHandlerName());
            addJobToScheduler(updateObj);
        } else if (schedulerManager.jobExists(job.getHandlerName())) {
            schedulerManager.updateJob(
                    job.getHandlerName(),
                    updateObj.getHandlerParam(),
                    updateObj.getCronExpression(),
                    updateObj.getRetryCount(),
                    updateObj.getRetryInterval());
        } else {
            addJobToScheduler(updateObj);
        }

        // 记录操作日志上下文
        LogRecordContext.putVariable("job", job);
    }

    private void validateJobHandlerExists(String handlerName) {
        try {
            Object handler = SpringUtil.getBean(handlerName);
            if (handler == null) {
                throw exception(JOB_HANDLER_BEAN_NOT_EXISTS);
            }
            if (!(handler instanceof JobHandler)) {
                throw exception(JOB_HANDLER_BEAN_TYPE_ERROR);
            }
        } catch (NoSuchBeanDefinitionException e) {
            throw exception(JOB_HANDLER_BEAN_NOT_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_JOB_TYPE,
            subType = INFRA_JOB_UPDATE_STATUS_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_JOB_UPDATE_STATUS_SUCCESS)
    public void updateJobStatus(Long id, Integer status) throws SchedulerException {
        if (!containsAny(status, JobStatusEnum.NORMAL.getStatus(), JobStatusEnum.STOP.getStatus())) {
            throw exception(JOB_CHANGE_STATUS_INVALID);
        }
        JobDO job = validateJobExists(id);
        if (job.getStatus().equals(status)) {
            throw exception(JOB_CHANGE_STATUS_EQUALS);
        }
        JobDO updateObj = JobDO.builder().id(id).status(status).build();
        jobMapper.updateById(updateObj);

        if (JobStatusEnum.NORMAL.getStatus().equals(status)) {
            if (!schedulerManager.jobExists(job.getHandlerName())) {
                addJobToScheduler(job);
            }
            schedulerManager.resumeJob(job.getHandlerName());
        } else { // 暂停
            schedulerManager.pauseJob(job.getHandlerName());
        }

        // 记录操作日志上下文
        LogRecordContext.putVariable("job", job);
        LogRecordContext.putVariable(
                "statusName", JobStatusEnum.NORMAL.getStatus().equals(status) ? "开启" : "暂停");
    }

    @Override
    @LogRecord(
            type = INFRA_JOB_TYPE,
            subType = INFRA_JOB_TRIGGER_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_JOB_TRIGGER_SUCCESS)
    public void triggerJob(Long id) throws SchedulerException {
        JobDO job = validateJobExists(id);

        schedulerManager.triggerJob(job.getId(), job.getHandlerName(), job.getHandlerParam());

        // 记录操作日志上下文
        LogRecordContext.putVariable("job", job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncJob() throws SchedulerException {
        List<JobDO> jobList = jobMapper.selectList();

        for (JobDO job : jobList) {
            // 已存在的任务原地更新，避免同步操作制造无调度窗口。
            if (schedulerManager.jobExists(job.getHandlerName())) {
                schedulerManager.updateJob(
                        job.getHandlerName(),
                        job.getHandlerParam(),
                        job.getCronExpression(),
                        job.getRetryCount(),
                        job.getRetryInterval());
            } else {
                addJobToScheduler(job);
            }
            // 数据库是状态源；同步时修复 Quartz 持久化表与 infra_job 的漂移。
            if (Objects.equals(job.getStatus(), JobStatusEnum.STOP.getStatus())) {
                schedulerManager.pauseJob(job.getHandlerName());
            } else {
                schedulerManager.resumeJob(job.getHandlerName());
            }
            log.info("[syncJob][id({}) handlerName({}) 同步完成]", job.getId(), job.getHandlerName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = INFRA_JOB_TYPE,
            subType = INFRA_JOB_DELETE_SUB_TYPE,
            bizNo = "{{#id}}",
            success = INFRA_JOB_DELETE_SUCCESS)
    public void deleteJob(Long id) throws SchedulerException {
        JobDO job = validateJobExists(id);
        jobMapper.deleteById(id);

        schedulerManager.deleteJob(job.getHandlerName());

        // 记录操作日志上下文
        LogRecordContext.putVariable("job", job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobList(List<Long> ids) throws SchedulerException {
        List<JobDO> jobs = jobMapper.selectByIds(ids);
        jobMapper.deleteByIds(ids);

        for (JobDO job : jobs) {
            schedulerManager.deleteJob(job.getHandlerName());
        }
    }

    private JobDO validateJobExists(Long id) {
        JobDO job = jobMapper.selectById(id);
        if (job == null) {
            throw exception(JOB_NOT_EXISTS);
        }
        return job;
    }

    private void validateCronExpression(String cronExpression) {
        if (!CronUtils.isValid(cronExpression)) {
            throw exception(JOB_CRON_EXPRESSION_VALID);
        }
    }

    @Override
    public JobDO getJob(Long id) {
        return jobMapper.selectById(id);
    }

    @Override
    public PageResult<JobDO> getJobPage(PageParam pageParam, String name, Integer status, String handlerName) {
        return jobMapper.selectPage(pageParam, name, status, handlerName);
    }

    private static void fillJobMonitorTimeoutEmpty(JobDO job) {
        if (job.getMonitorTimeout() == null) {
            job.setMonitorTimeout(0);
        }
    }

    private void addJobToScheduler(JobDO job) throws SchedulerException {
        schedulerManager.addJob(
                job.getId(),
                job.getHandlerName(),
                job.getHandlerParam(),
                job.getCronExpression(),
                job.getRetryCount(),
                job.getRetryInterval());
    }
}
