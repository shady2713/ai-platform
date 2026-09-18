package com.basicframework.module.ai.job;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.ai.service.auth.AiTicketService;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 失效票据清理任务（A06）：按批逻辑删除"已撤销或过期超过保留期"的访问票据。
 *
 * <p>幂等：处理对象是已失效票据，重复执行不会影响任何仍可用于认证的票据；
 * 每批条数与批次数都有上限，避免一次任务扫全表。
 */
@Slf4j
@Component
public class AiTicketCleanupJob implements JobHandler {

    private final AiTicketService ticketService;

    private final int batchSize;

    private final int maxBatches;

    private final Duration retention;

    /** 构造器注入：配置值与依赖都通过构造器传入，不使用字段注入。 */
    public AiTicketCleanupJob(
            AiTicketService ticketService,
            @Value("${basic-framework.ai.ticket.cleanup.batch-size:200}") int batchSize,
            @Value("${basic-framework.ai.ticket.cleanup.max-batches:10}") int maxBatches,
            @Value("${basic-framework.ai.ticket.cleanup.retention:PT24H}") Duration retention) {
        this.ticketService = ticketService;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.retention = retention;
    }

    @Override
    public String execute(String param) {
        int cleaned = ticketService.cleanInvalidTickets(batchSize, maxBatches, retention);
        log.info("[execute][清理失效访问票据 ({}) 条]", cleaned);
        return String.format("清理失效访问票据 %s 条", cleaned);
    }
}
