package com.basicframework.module.ai.job;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.dal.mysql.report.AiReportRefreshMapper;
import com.basicframework.module.ai.service.report.refresh.AiReportRefreshService;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 报表刷新 Job（R06）：按节奏扫描"到期未刷新"的可刷新报表并逐张刷新。
 *
 * <p>为什么"到期"看的是**尝试记录**而不是新列：最近一次尝试（无论成功、未变化或失败）早于间隔的报表才算到期，
 * 失败报表按同一节奏重试而不是每轮都试——失败留痕（AT-047）要的是可诊断，不是失败风暴。
 *
 * <p>单张报表失败不影响其余报表：失败原因已经落在尝试记录里（界面可显示），作业只统计计数。
 * 作业没有会话身份，刷新以**报表自身的归属列**为主体（不接受任何外部指定身份）。
 */
@Slf4j
@Component
public class AiReportRefreshJob implements JobHandler {

    private final AiReportRefreshMapper refreshMapper;

    private final AiReportRefreshService refreshService;

    private final int intervalSeconds;

    private final int batchSize;

    /** 构造器注入：配置值与依赖都通过构造器传入，不使用字段注入。 */
    public AiReportRefreshJob(
            AiReportRefreshMapper refreshMapper,
            AiReportRefreshService refreshService,
            @Value("${basic-framework.ai.report.refresh.interval-seconds:1800}") int intervalSeconds,
            @Value("${basic-framework.ai.report.refresh.batch-size:50}") int batchSize) {
        this.refreshMapper = refreshMapper;
        this.refreshService = refreshService;
        this.intervalSeconds = intervalSeconds;
        this.batchSize = batchSize;
    }

    @Override
    public String execute(String param) {
        List<Long> due = refreshMapper.selectDueReportIds(intervalSeconds, batchSize);
        int refreshed = 0;
        int unchanged = 0;
        int failed = 0;
        for (Long reportId : due) {
            try {
                AiReportRefreshResultDTO result =
                        refreshService.refresh(new AiReportRefreshRequestDTO().setReportId(reportId));
                if (AiReportRefreshResultDTO.STATUS_OK.equals(result.getStatus())) {
                    refreshed++;
                } else if (AiReportRefreshResultDTO.STATUS_UNCHANGED.equals(result.getStatus())) {
                    unchanged++;
                } else {
                    failed++;
                }
            } catch (ServiceException failure) {
                // 前置条件失败（归属/失权/并发）：原因已按稳定错误码给出，不中断其余报表
                failed++;
                log.info("[execute][刷新前置条件失败][reportId={}, code={}]", reportId, failure.getCode());
            }
        }
        return String.format("扫描 %s 张可刷新报表：刷新 %s、未变化 %s、失败 %s", due.size(), refreshed, unchanged, failed);
    }
}
