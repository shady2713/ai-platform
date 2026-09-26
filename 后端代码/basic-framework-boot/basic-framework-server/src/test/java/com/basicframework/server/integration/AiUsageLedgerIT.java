package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.mysql.usage.AiQuotaLeaseMapper;
import com.basicframework.module.ai.dal.mysql.usage.AiUsageLedgerMapper;
import com.basicframework.module.ai.service.quota.AiQuotaService;
import com.basicframework.module.ai.service.quota.dto.AiQuotaAcquireDTO;
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import com.basicframework.module.ai.service.usage.dto.AiUsageRecordDTO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q02 用量账本与配额占位（真实 MySQL + V81 迁移）。
 *
 * <p>在真实数据库上验证三件用内存桩证明不了的事：唯一键去重（并发/重复上报只累计一次）、
 * 聚合 SQL 的口径（按来源/服务分组）、以及**租约到期即可回收**（占位不会永久占用配额）。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiUsageLedgerIT extends AbstractPersistenceIntegrationTest {

    private static final long APP_ID = 9_001L;

    private static final String PREFIX = "it_q02_";

    @Autowired
    private AiUsageLedgerService usageLedgerService;

    @Autowired
    private AiUsageLedgerMapper usageLedgerMapper;

    @Autowired
    private AiQuotaLeaseMapper quotaLeaseMapper;

    @Autowired
    private AiQuotaService quotaService;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_usage_ledger WHERE invocation_id LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM ai_quota_lease WHERE invocation_id LIKE ?", PREFIX + "%");
    }

    private static AiUsageRecordDTO record(String suffix, String source, Long input, Long output) {
        return new AiUsageRecordDTO()
                .setInvocationId(PREFIX + suffix)
                .setApplicationId(APP_ID)
                .setDurationMs(12)
                .setEndpointRef("endpoint:3")
                .setInputTokens(input)
                .setModelRef("it-q02-model")
                .setModelRevision(1)
                .setOutputTokens(output)
                .setRunId(7L)
                .setServiceId(4L)
                .setStatus("SUCCEEDED")
                .setSubjectRef("app:9001/subject:it")
                .setUsageSource(source);
    }

    @Test
    void ledgerDeduplicatesByInvocationAndAggregatesTruthfully() {
        assertThat(usageLedgerService.record(record("a", "REPORTED", 30L, 10L))).isTrue();
        // 同一调用重复上报：不重复累计
        assertThat(usageLedgerService.record(record("a", "REPORTED", 30L, 10L))).isFalse();
        assertThat(usageLedgerService.record(record("b", "ESTIMATED", 8L, 2L).setServiceId(4L)))
                .isTrue();
        assertThat(usageLedgerService.record(
                        record("c", "UNKNOWN", null, null).setRunId(null).setTaskId(5L)))
                .isTrue();
        // UNKNOWN 不允许携带 token（否则会被读成"真实用量"）
        assertThatThrownBy(() -> usageLedgerService.record(record("d", "UNKNOWN", 1L, null)))
                .isInstanceOf(ServiceException.class);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_usage_ledger WHERE application_id = ?", Long.class, APP_ID))
                .isEqualTo(3L);
        assertThat(usageLedgerService.listByRun(7L)).hasSize(2);
        // 三条记录都属于同一应用与服务（含"用量未知"那条）：按服务过滤应全部命中
        assertThat(usageLedgerService
                        .page(
                                new PageParam(),
                                APP_ID,
                                4L,
                                LocalDateTime.now().minusMinutes(5),
                                LocalDateTime.now().plusMinutes(1))
                        .getList())
                .hasSize(3);
        // 时间窗外为空：聚合口径不受调用方"顺手放宽"影响
        assertThat(usageLedgerService
                        .page(
                                new PageParam(),
                                APP_ID,
                                4L,
                                LocalDateTime.now().plusMinutes(1),
                                LocalDateTime.now().plusMinutes(2))
                        .getList())
                .isEmpty();

        Map<String, Object> summary = usageLedgerService.summaryBySource(
                APP_ID, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(1));
        assertThat(summary.get("unknownInvocations")).isEqualTo(1L);
        List<Map<String, Object>> byService = usageLedgerService.summaryByService(
                APP_ID, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(1));
        assertThat(byService).isNotEmpty();
        // 端点只存引用，地址与密钥不在表里
        assertThat(usageLedgerMapper.selectByInvocation(PREFIX + "a").getEndpointRef())
                .isEqualTo("endpoint:3");
        assertThat(usageLedgerMapper.selectByInvocation(PREFIX + "missing")).isNull();
    }

    @Test
    void quotaLeasesExpireAndAreReclaimedInsteadOfBlockingForever() {
        AiQuotaAcquireDTO request = new AiQuotaAcquireDTO()
                .setApplicationId(APP_ID)
                .setInvocationId(PREFIX + "inv1")
                .setLease(Duration.ofMinutes(5))
                .setLimit(1)
                .setHolderRef("it-node-1");

        assertThat(quotaService.acquire(request)).isTrue();
        // 同一调用重复申请：续租而不是再占一格
        assertThat(quotaService.acquire(request)).isTrue();
        assertThat(quotaService.activeCount(APP_ID)).isEqualTo(1L);

        // 并发上限：第二个调用拿不到（不排队、不阻塞）
        assertThat(quotaService.acquire(request.setInvocationId(PREFIX + "inv2")))
                .isFalse();

        // 进程崩溃场景：占位的租约到期后不再计入并发，新的申请可以拿到
        jdbcTemplate.update(
                "UPDATE ai_quota_lease SET lease_until = ? WHERE invocation_id = ?",
                LocalDateTime.now().minusMinutes(1),
                PREFIX + "inv1");
        assertThat(quotaService.activeCount(APP_ID)).isZero();
        assertThat(quotaService.acquire(request.setInvocationId(PREFIX + "inv3")))
                .isTrue();

        // 续租：过期的占位续租失败（调用方必须停止工作），活动占位续租成功
        assertThat(quotaService.renew(PREFIX + ":lease:missing", Duration.ofMinutes(1)))
                .isFalse();
        String activeKey = quotaLeaseMapper
                .selectByLeaseKey(APP_ID + ":0:" + PREFIX + "inv3")
                .getLeaseKey();
        assertThat(quotaService.renew(activeKey, Duration.ofMinutes(1))).isTrue();

        // 释放是幂等的，释放后不再计入并发
        quotaService.release(activeKey);
        quotaService.release(activeKey);
        assertThat(quotaService.activeCount(APP_ID)).isZero();
    }
}
