package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.job.AiWebhookDeliveryJob;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryService;
import com.basicframework.module.ai.service.webhook.AiWebhookFailureCodes;
import com.basicframework.module.ai.service.webhook.AiWebhookSignature;
import com.basicframework.module.ai.service.webhook.AiWebhookTargetService;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import com.basicframework.server.BasicFrameworkServerApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X10 受控异步结果 Webhook 验收：真实 MySQL/Redis + **真实接收端样例** + 真实受控出站边界。
 *
 * <p>每个用例的链路是真的：终态运行行 → 入队（正文与编号冻结）→ 租约领取 → 经受控出站边界
 * 以真实 socket 投递到本机接收端样例 → 接收端按协议验签/防重放 → 结论落投递行与尝试行。
 * 出站客户端是 {@link GuardedExternalHttpClient}（F09 实现），只把本机接收端列入允许清单：
 * 未列入的主机/端口在发送前被拒（零请求），私网地址未显式批准时同样被拒。
 *
 * <p>本类还钉住最重要的不变量：**投递失败绝不重跑运行**（运行状态、事件数、任务数在所有
 * 失败分支里都保持不变）。
 */
@SpringBootTest(
        classes = BasicFrameworkServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.task.scheduling.enabled=false",
            "basic-framework.security.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            // 投递超时收到 2 秒：接收端样例的"响应丢失"分支需要稳定地触发客户端超时（不靠真等 30 秒）
            "basic-framework.ai.webhook.request-timeout-seconds=2"
        })
@Import(AiWebhookDeliveryIT.ReceiverBoundaryConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiWebhookDeliveryIT extends AbstractPersistenceIntegrationTest {

    private static final AiWebhookReceiverSample RECEIVER = startReceiver();

    private static final String SECRET = "it-webhook-signing-key-0123456789";

    private static final String APP_CODE = "it-webhook-app";

    private static final String SERVICE_CODE = "it-webhook-service";

    private static final String TARGET_CODE = "it-webhook-target";

    @Autowired
    private AiWebhookTargetService targetService;

    @Autowired
    private AiWebhookDeliveryService deliveryService;

    @Autowired
    private AiWebhookDeliveryJob deliveryJob;

    private Long applicationId;

    private Long serviceId;

    private Long releaseId;

    private Long targetId;

    /** 真实出站边界：允许清单只含本机接收端；参数与生产一致（不跟随重定向、有大小与超时上限）。 */
    @TestConfiguration
    static class ReceiverBoundaryConfiguration {

        @Bean
        @Primary
        ExternalHttpClient webhookBoundaryHttpClient() {
            AiHttpProperties properties = new AiHttpProperties();
            properties.setAllowedHosts(List.of("127.0.0.1"));
            properties.setAllowedPorts(List.of(RECEIVER.port()));
            properties.setAllowPrivateTargets(true);
            properties.setReadTimeout(Duration.ofSeconds(2));
            properties.setConnectTimeout(Duration.ofSeconds(2));
            return new GuardedExternalHttpClient(properties);
        }
    }

    private static AiWebhookReceiverSample startReceiver() {
        try {
            return AiWebhookReceiverSample.start();
        } catch (IOException failure) {
            throw new IllegalStateException("接收端样例启动失败", failure);
        }
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    @BeforeEach
    void prepareReceiverAndApplication() {
        RECEIVER.reset();
        RECEIVER.setSecret(SECRET);
        cleanupBusinessChain();
        jdbcTemplate.update(
                "INSERT INTO ai_application (app_code, name, origins, enabled, creator, updater)"
                        + " VALUES (?, 'IT Webhook 应用', '[]', b'1', 'it', 'it')",
                APP_CODE);
        applicationId =
                jdbcTemplate.queryForObject("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        jdbcTemplate.update(
                "INSERT INTO ai_service (app_id, code, name, status, model_endpoint_id, prompt_template,"
                        + " input_schema, required_capabilities, run_subject_type, creator, updater)"
                        + " VALUES (?, ?, 'IT Webhook 服务', 'READY', 1, 'prompt', '{}', 'TEXT', 'APP', 'it', 'it')",
                applicationId,
                SERVICE_CODE);
        serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service WHERE code = ? AND app_id = ?", Long.class, SERVICE_CODE, applicationId);
        jdbcTemplate.update(
                "INSERT INTO ai_service_release (service_id, model_endpoint_id, endpoint_config_revision,"
                        + " prompt_template, input_schema, required_capabilities, content_hash, status,"
                        + " release_version, creator, updater)"
                        + " VALUES (?, 1, 1, 'prompt', '{}', 'TEXT', ?, 'ACTIVE', 1, 'it', 'it')",
                serviceId,
                "a".repeat(64));
        releaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_service_release WHERE service_id = ?", Long.class, serviceId);
        targetId = targetService.create(new AiWebhookTargetSaveDTO()
                .setApplicationId(applicationId)
                .setCode(TARGET_CODE)
                .setName("IT 接收端")
                .setTargetUrl(RECEIVER.origin())
                .setEventTypes(List.of("RUN.SUCCEEDED", "RUN.FAILED"))
                .setSecret(SECRET)
                .setMaxAttempts(2));
    }

    @AfterEach
    void cleanWebhookRows() {
        RECEIVER.reset();
        cleanupBusinessChain();
        applicationId = null;
        serviceId = null;
        releaseId = null;
        targetId = null;
    }

    /**
     * 按<b>稳定业务键</b>无条件清理整条链，不依赖 {@code applicationId} 字段。
     *
     * <p>原实现先看 {@code applicationId != null}：非空时按 id 删（那时已是<b>上一个</b>用例的 id），
     * 为空时只删 {@code ai_application} 一张表、留下另外 6 张的孤儿行。
     * 于是在复用/残留数据的库上，这个类<b>不幂等</b>：首个用例就会留下孤儿 {@code ai_run}，
     * 后续用例的入队统计因此时对时错（例如 {@code enqueued=0}）。
     *
     * <p>改成按 app_code / service_code / target_code 定位后，任何历史残留都会被清干净，
     * 本类可以在同一个库上重复运行——这正是它作为验收用例应有的性质。
     */
    private void cleanupBusinessChain() {
        List<Long> appIds =
                jdbcTemplate.queryForList("SELECT id FROM ai_application WHERE app_code = ?", Long.class, APP_CODE);
        for (Long appId : appIds) {
            jdbcTemplate.update(
                    "DELETE FROM ai_webhook_delivery_attempt WHERE delivery_id IN"
                            + " (SELECT id FROM ai_webhook_delivery WHERE application_id = ?)",
                    appId);
            jdbcTemplate.update("DELETE FROM ai_webhook_delivery WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_webhook_target WHERE application_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_run WHERE application_id = ?", appId);
            jdbcTemplate.update(
                    "DELETE FROM ai_service_release WHERE service_id IN"
                            + " (SELECT id FROM ai_service WHERE app_id = ?)",
                    appId);
            jdbcTemplate.update("DELETE FROM ai_service WHERE app_id = ?", appId);
            jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", appId);
        }
        // 孤儿行：上一轮残留里 application_id 已指向不存在的应用，按 code 再兜一次
        jdbcTemplate.update("DELETE FROM ai_webhook_delivery_attempt WHERE delivery_id IN"
                + " (SELECT d.id FROM ai_webhook_delivery d WHERE d.application_id NOT IN"
                + " (SELECT id FROM ai_application))");
        jdbcTemplate.update(
                "DELETE FROM ai_webhook_delivery WHERE application_id NOT IN (SELECT id FROM ai_application)");
        jdbcTemplate.update(
                "DELETE FROM ai_webhook_target WHERE application_id NOT IN (SELECT id FROM ai_application)");
        jdbcTemplate.update("DELETE FROM ai_run WHERE application_id NOT IN (SELECT id FROM ai_application)");
        jdbcTemplate.update("DELETE FROM ai_service_release WHERE service_id NOT IN (SELECT id FROM ai_service)");
        jdbcTemplate.update("DELETE FROM ai_service WHERE app_id NOT IN (SELECT id FROM ai_application)");
        jdbcTemplate.update("DELETE FROM ai_application WHERE app_code = ?", APP_CODE);
        jdbcTemplate.update("DELETE FROM ai_service WHERE code = ?", SERVICE_CODE);
        jdbcTemplate.update("DELETE FROM ai_webhook_target WHERE code = ?", TARGET_CODE);
    }

    @Test
    void terminalRunIsDeliveredOverTheRealBoundaryAndTheReceiverVerifiesTheSignature() {
        Long runId = terminalRun("run_it_wh_ok", "SUCCEEDED");

        String summary = deliveryJob.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=1,claimed=1,delivered=1,retried=0,failed=0,pruned=0");
        assertThat(RECEIVER.requests()).isEqualTo(1);
        assertThat(RECEIVER.rejections()).isEmpty();
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
        AiWebhookReceiverSample.ReceivedEvent event = RECEIVER.acceptedEvents().get(0);
        assertThat(event.deliveryNo()).startsWith("whd_");
        assertThat(event.attempt()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo("RUN.SUCCEEDED");
        assertThat(event.resource()).isEqualTo("RUN:run_it_wh_ok");
        assertThat(event.status()).isEqualTo("SUCCEEDED");

        AiWebhookDeliveryDO delivery = deliveryOf(runId);
        assertThat(delivery.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_SUCCEEDED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getDeliveredTime()).isNotNull();
        assertThat(delivery.getFailureCode()).isNull();
        assertThat(delivery.getPayloadDigest()).hasSize(64);
        assertThat(delivery.getDeliveryNo()).isEqualTo(event.deliveryNo());

        List<AiWebhookDeliveryAttemptDO> attempts = deliveryService.getAttempts(delivery.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getOutcome()).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_DELIVERED);
        assertThat(attempts.get(0).getHttpStatus()).isEqualTo(200);
        assertThat(attempts.get(0).getSignatureTimestamp()).isPositive();
    }

    @Test
    void receiverRejectsForgedSignatureStaleTimestampAndDuplicateDelivery() throws Exception {
        Long runId = terminalRun("run_it_wh_verify", "FAILED");
        deliveryJob.execute(null);
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
        String deliveryNo = RECEIVER.acceptedEvents().get(0).deliveryNo();
        byte[] body = deliveryOf(runId).getPayloadJson().getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        // 伪造签名：正文/编号/时间戳都合法，签名用另一个密钥算 → 401
        assertThat(post(
                        "whd_forged",
                        body,
                        String.valueOf(now),
                        AiWebhookSignature.sign("another-key-0123456789", String.valueOf(now), "whd_forged", body)))
                .isEqualTo(401);
        // 过期时间戳：签名正确但时间戳超出 ±5 分钟窗口 → 401
        long stale = now - 3_600;
        assertThat(post(
                        deliveryNo,
                        body,
                        String.valueOf(stale),
                        AiWebhookSignature.sign(SECRET, String.valueOf(stale), deliveryNo, body)))
                .isEqualTo(401);
        // 重复投递：完全相同的合法请求再来一次 → 409，且不会被二次处理
        assertThat(post(
                        deliveryNo,
                        body,
                        String.valueOf(now),
                        AiWebhookSignature.sign(SECRET, String.valueOf(now), deliveryNo, body)))
                .isEqualTo(409);

        assertThat(RECEIVER.rejections()).containsExactly("forged-signature", "stale-timestamp", "replayed-delivery");
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
    }

    @Test
    void redirectIsNotFollowedAndIsRecordedAsADefiniteFailure() {
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.REDIRECT);
        Long runId = terminalRun("run_it_wh_redirect", "SUCCEEDED");

        String summary = deliveryJob.execute(null);

        assertThat(summary).isEqualTo("recovered=0,enqueued=1,claimed=1,delivered=0,retried=0,failed=1,pruned=0");
        assertThat(RECEIVER.requests()).isEqualTo(1);
        AiWebhookDeliveryDO delivery = deliveryOf(runId);
        assertThat(delivery.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(delivery.getFailureCode()).isEqualTo(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED);
        assertThat(deliveryService.getAttempts(delivery.getId()).get(0).getHttpStatus())
                .isEqualTo(302);
    }

    @Test
    void unauthorizedTargetsAndPrivateAddressesAreRefusedBeforeAnyRequest() {
        // 只让"未授权目标"参与本用例：本机接收端目标停用，接收端请求数才能判定为 0
        disableTarget();
        // 未列入允许清单的主机：发送前被拒（零请求），投递按确定失败收尾
        Long blockedTargetId = targetService.create(new AiWebhookTargetSaveDTO()
                .setApplicationId(applicationId)
                .setCode("it-webhook-blocked")
                .setName("未授权主机")
                .setTargetUrl("https://erp.example.com/hook")
                .setEventTypes(List.of("RUN.SUCCEEDED"))
                .setSecret(SECRET)
                .setMaxAttempts(2));
        Long runId = terminalRun("run_it_wh_blocked", "SUCCEEDED");

        deliveryJob.execute(null);

        assertThat(RECEIVER.requests()).isZero();
        AiWebhookDeliveryDO blocked = deliveryOf(runId, blockedTargetId);
        assertThat(blocked.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(blocked.getFailureCode()).isEqualTo(AiWebhookFailureCodes.TARGET_NOT_ALLOWED);
        assertThat(deliveryService.getAttempts(blocked.getId()).get(0).getHttpStatus())
                .isNull();

        // 私网/环回地址未显式批准时同样被拒：用同一条受控边界（allowPrivateTargets=false）验证，零请求
        AiHttpProperties strict = new AiHttpProperties();
        strict.setAllowedHosts(List.of("127.0.0.1"));
        strict.setAllowedPorts(List.of(RECEIVER.port()));
        strict.setAllowPrivateTargets(false);
        // 用 https 形态触发**地址维度**的拒绝：http 形态会先被"仅允许 https（内网批准可用 http）"拦下
        String privateUrl = "https://127.0.0.1:" + RECEIVER.port() + "/hook";
        try (GuardedExternalHttpClient privateDenied = new GuardedExternalHttpClient(strict)) {
            assertThatThrownBy(() -> privateDenied.execute(ExternalHttpRequest.get(privateUrl)))
                    .isInstanceOf(ExternalHttpException.class)
                    .hasFieldOrPropertyWithValue("reason", ExternalHttpException.Reason.PRIVATE_TARGET_DENIED);
        }
        assertThat(RECEIVER.requests()).isZero();
    }

    @Test
    void disablingATargetStopsNewDeliveriesAndFinishesInflightOnesAccordingToFacts() {
        Long deliveredRun = terminalRun("run_it_wh_before_disable", "SUCCEEDED");
        deliveryJob.execute(null);
        assertThat(RECEIVER.requests()).isEqualTo(1);
        assertThat(deliveryOf(deliveredRun).getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_SUCCEEDED);

        // 停用后不再产生新投递：入队阶段就不会建行（投递行数量不变、接收端没有新请求）
        disableTarget();
        Long disabledRun = terminalRun("run_it_wh_after_disable", "SUCCEEDED");
        deliveryJob.execute(null);
        assertThat(RECEIVER.requests()).isEqualTo(1);
        assertThat(countDeliveries()).isEqualTo(1);

        // 重新启用后补投"停用期间"产生的事件：停用期间不扫描该目标、水位原地不动，
        // 重新启用后补漏会覆盖停用期间那一段（事件不会永久丢失），
        // 因此此刻会同时补出停用期间的运行与在途运行两条投递行
        enableTarget();
        Long inflightRun = terminalRun("run_it_wh_inflight", "SUCCEEDED");
        RECEIVER.reset();
        assertThat(deliveryService.enqueueTerminalRuns(50)).isEqualTo(2);
        AiWebhookDeliveryDO inflight = deliveryOf(inflightRun);
        assertThat(inflight.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_PENDING);

        // 在途投递按事实收尾：行已建立后目标被停用 → 发送前复检失败，零请求、确定失败
        disableTarget();
        deliveryJob.execute(null);
        AiWebhookDeliveryDO settled = deliveryService.get(inflight.getId());
        assertThat(settled.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(settled.getFailureCode()).isEqualTo(AiWebhookFailureCodes.TARGET_DISABLED);
        assertThat(deliveryService.get(deliveryOf(disabledRun).getId()).getFailureCode())
                .isEqualTo(AiWebhookFailureCodes.TARGET_DISABLED);
        assertThat(RECEIVER.requests()).isZero();

        // 停用状态下人工重投被拒
        assertThatThrownBy(() -> deliveryService.redeliver(settled.getId()))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code", AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED.getCode());

        // 重新启用后人工重投可用（追加一份预算、尝试序号继续递增，并回到待领取）
        enableTarget();
        deliveryService.redeliver(settled.getId());
        assertThat(deliveryService.get(settled.getId()).getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_PENDING);
        deliveryJob.execute(null);
        assertThat(deliveryService.get(settled.getId()).getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_SUCCEEDED);
        assertThat(RECEIVER.requests()).isEqualTo(1);
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
    }

    @Test
    void retriesAreBoundedAndExhaustionBecomesADeadLetterWithoutRerunningTheRun() {
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.HTTP_500);
        Long runId = terminalRun("run_it_wh_retry", "SUCCEEDED");
        int eventsBefore = countRunEvents("run_it_wh_retry");
        int tasksBefore = countRunTasks(runId);

        // 第 1 次：5xx → 可重试（退避到未来）
        deliveryJob.execute(null);
        AiWebhookDeliveryDO afterFirst = deliveryOf(runId);
        assertThat(afterFirst.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_PENDING);
        assertThat(afterFirst.getAttemptCount()).isEqualTo(1);
        assertThat(afterFirst.getLastErrorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_SERVER_ERROR);
        assertThat(afterFirst.getNextAttemptTime()).isAfter(LocalDateTime.now());
        assertThat(RECEIVER.requests()).isEqualTo(1);

        // 退避窗口内不会被重复领取
        assertThat(deliveryJob.execute(null)).contains("claimed=0");
        assertThat(RECEIVER.requests()).isEqualTo(1);

        // 让退避到期（只动投递行的时间，不改协议语义）后重试到预算上限
        expireBackoff(afterFirst.getId());
        deliveryJob.execute(null);

        AiWebhookDeliveryDO exhausted = deliveryService.get(afterFirst.getId());
        assertThat(exhausted.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(2);
        assertThat(exhausted.getFailureCode()).isEqualTo(AiWebhookFailureCodes.DELIVERY_EXHAUSTED);
        assertThat(exhausted.getLastErrorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_SERVER_ERROR);
        assertThat(RECEIVER.requests()).isEqualTo(2);
        assertThat(deliveryService.getAttempts(exhausted.getId())).hasSize(2).allSatisfy(attempt -> assertThat(
                        attempt.getOutcome())
                .isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_RETRYABLE));

        // 投递失败绝不重跑运行：运行状态、事件与任务数都原样
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_run WHERE id = ?", String.class, runId))
                .isEqualTo("SUCCEEDED");
        assertThat(countRunEvents("run_it_wh_retry")).isEqualTo(eventsBefore);
        assertThat(countRunTasks(runId)).isEqualTo(tasksBefore);
        assertThat(RECEIVER.acceptedEvents()).isEmpty();

        // 接收端恢复后，人工重投把死信送回队列并成功送达
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.OK);
        deliveryService.redeliver(exhausted.getId());
        assertThat(deliveryJob.execute(null)).contains("delivered=1");
        assertThat(deliveryService.get(exhausted.getId()).getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_SUCCEEDED);
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
        assertThat(countRunEvents("run_it_wh_retry")).isEqualTo(eventsBefore);
        assertThat(countRunTasks(runId)).isEqualTo(tasksBefore);
    }

    @Test
    void aLostResponseAfterAcceptanceCannotProduceASecondReceipt() {
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.ACCEPT_THEN_TIMEOUT);
        Long runId = terminalRun("run_it_wh_lost", "SUCCEEDED");

        // 第 1 次：接收端已生效但响应丢失 → 平台记可重试（不谎报成功）
        deliveryJob.execute(null);
        AiWebhookDeliveryDO delivery = deliveryOf(runId);
        assertThat(delivery.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_PENDING);
        assertThat(delivery.getLastErrorCode()).isEqualTo(AiWebhookFailureCodes.TIMEOUT);
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);

        // 重试撞上接收端去重：409 按确定失败收尾，事件仍然只有一条（副作用只发生一次）
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.OK);
        expireBackoff(delivery.getId());
        deliveryJob.execute(null);

        AiWebhookDeliveryDO settled = deliveryService.get(delivery.getId());
        assertThat(settled.getStatus()).isEqualTo(AiWebhookDeliveryDO.STATUS_FAILED);
        assertThat(settled.getLastErrorCode()).isEqualTo(AiWebhookFailureCodes.HTTP_CLIENT_ERROR);
        assertThat(deliveryService.getAttempts(delivery.getId()).get(1).getHttpStatus())
                .isEqualTo(409);
        assertThat(RECEIVER.acceptedEvents()).hasSize(1);
        assertThat(RECEIVER.rejections()).containsExactly("replayed-delivery");
    }

    @Test
    void enqueueIsIdempotentAndNeverDuplicatesTheDeliveryRow() {
        Long runId = terminalRun("run_it_wh_idempotent", "SUCCEEDED");

        assertThat(deliveryService.enqueueTerminalRuns(50)).isEqualTo(1);
        String deliveryNo = deliveryOf(runId).getDeliveryNo();
        assertThat(deliveryService.enqueueTerminalRuns(50)).isZero();

        assertThat(deliveryOf(runId).getDeliveryNo()).isEqualTo(deliveryNo);
        assertThat(countDeliveries()).isEqualTo(1);
    }

    /**
     * 有界补漏（X10 性能回归钉）：单轮预算封顶 + 跨轮可恢复 + 不漏不重。
     *
     * <p>补漏扫描从"整表 {@code NOT EXISTS} 反连接 + 排序后截断"改成"水位 + 最近窗口 + LIMIT"后，
     * 必须仍然满足：每一轮最多补一个预算内的条数；停机/多轮之后补齐全部符合条件的事件；
     * 不在白名单与未终态的运行永远不产生投递；同一事件绝不产生第二条投递行。
     */
    @Test
    void boundedEnqueueIsResumableAcrossRoundsAndCoversEveryEligibleEventExactlyOnce() {
        List<Long> eligible = List.of(
                terminalRun("run_it_wh_bounded_1", "SUCCEEDED"),
                terminalRun("run_it_wh_bounded_2", "FAILED"),
                terminalRun("run_it_wh_bounded_3", "SUCCEEDED"),
                terminalRun("run_it_wh_bounded_4", "FAILED"),
                terminalRun("run_it_wh_bounded_5", "SUCCEEDED"));
        terminalRun("run_it_wh_bounded_cancelled", "CANCELLED");
        terminalRun("run_it_wh_bounded_running", "RUNNING");

        // 单轮有界：预算 1 → 一轮最多补一条
        assertThat(deliveryService.enqueueTerminalRuns(1)).isEqualTo(1);
        assertThat(countDeliveries()).isEqualTo(1);

        // 跨轮可恢复：反复推进（Job 的常规一轮预算 50）直到收敛，收敛后不再新增
        int rounds = 0;
        while (countDeliveries() < eligible.size() && rounds < 10) {
            deliveryJob.execute(null);
            rounds++;
        }
        assertThat(rounds).isLessThan(10);
        assertThat(countDeliveries()).isEqualTo(eligible.size());
        for (Long runId : eligible) {
            assertThat(countDeliveriesOf(runId)).isEqualTo(1);
        }
        // 不在白名单（CANCELLED）与非终态（RUNNING）的运行没有投递行
        assertThat(countDeliveries()).isEqualTo(eligible.size());

        // 收敛后再跑：水位已到窗口右端，仍然不重复、不新增
        deliveryJob.execute(null);
        assertThat(countDeliveries()).isEqualTo(eligible.size());

        // 水位推进之后新写入的终态运行（同一秒）仍会被"最近窗口"读到：水位不会让它静默消失
        Long lateRun = terminalRun("run_it_wh_bounded_late", "SUCCEEDED");
        deliveryJob.execute(null);
        assertThat(countDeliveriesOf(lateRun)).isEqualTo(1);
        assertThat(countDeliveries()).isEqualTo(eligible.size() + 1);
    }

    @Test
    void managementQueriesReturnTheRecordedFacts() {
        Long runId = terminalRun("run_it_wh_query", "SUCCEEDED");
        deliveryJob.execute(null);

        // 控制面分页（目标：按应用 + 标识 + 状态过滤；投递：按目标 + 状态 + 事件过滤）
        assertThat(targetService
                        .getPage(new PageParam(), applicationId, TARGET_CODE, AiWebhookTargetDO.STATUS_ENABLED)
                        .getList())
                .extracting(AiWebhookTargetDO::getCode)
                .containsExactly(TARGET_CODE);
        assertThat(targetService
                        .getPage(new PageParam(), applicationId, null, null)
                        .getList())
                .extracting(AiWebhookTargetDO::getId)
                .contains(targetId);

        AiWebhookDeliveryDO delivery = deliveryOf(runId);
        assertThat(deliveryService
                        .getPage(new PageParam(), targetId, AiWebhookDeliveryDO.STATUS_SUCCEEDED, "RUN.SUCCEEDED")
                        .getList())
                .extracting(AiWebhookDeliveryDO::getId)
                .contains(delivery.getId());
        assertThat(deliveryService.get(delivery.getId()).getPayloadJson()).contains("RUN.SUCCEEDED");
    }

    @Test
    void attemptRetentionPrunesOnlyExpiredAttemptRecords() {
        RECEIVER.setMode(AiWebhookReceiverSample.Mode.HTTP_500);
        Long runId = terminalRun("run_it_wh_prune", "SUCCEEDED");
        deliveryJob.execute(null);
        Long deliveryId = deliveryOf(runId).getId();
        assertThat(deliveryService.getAttempts(deliveryId)).hasSize(1);

        assertThat(deliveryService.pruneAttempts(Duration.ofDays(30), 100)).isZero();
        jdbcTemplate.update(
                "UPDATE ai_webhook_delivery_attempt SET create_time = ?, update_time = ? WHERE delivery_id = ?",
                LocalDateTime.now().minusDays(31),
                LocalDateTime.now().minusDays(31),
                deliveryId);

        assertThat(deliveryService.pruneAttempts(Duration.ofDays(30), 100)).isEqualTo(1);
        assertThat(deliveryService.getAttempts(deliveryId)).isEmpty();
        // 投递行本身不受留痕清理影响（事实链的主体保留）
        assertThat(deliveryService.get(deliveryId).getId()).isEqualTo(deliveryId);
    }

    private int post(String deliveryNo, byte[] body, String timestamp, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RECEIVER.origin()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(AiWebhookSignature.HEADER_DELIVERY_NO, deliveryNo)
                .header(AiWebhookSignature.HEADER_TIMESTAMP, timestamp)
                .header(AiWebhookSignature.HEADER_EVENT, "RUN.FAILED")
                .header(AiWebhookSignature.HEADER_SIGNATURE, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private void disableTarget() {
        AiWebhookTargetDO target = targetService.get(targetId);
        targetService.updateStatus(targetId, target.getVersion(), false);
    }

    private void enableTarget() {
        AiWebhookTargetDO target = targetService.get(targetId);
        targetService.updateStatus(targetId, target.getVersion(), true);
    }

    private void expireBackoff(Long deliveryId) {
        jdbcTemplate.update(
                "UPDATE ai_webhook_delivery SET next_attempt_time = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1),
                deliveryId);
    }

    private Long terminalRun(String runKey, String status) {
        jdbcTemplate.update(
                "INSERT INTO ai_run (run_key, application_id, subject_type, external_user_id, service_id,"
                        + " release_id, model_endpoint_id, endpoint_config_revision, content_hash, input_digest,"
                        + " status, step_count, version, creator, updater)"
                        + " VALUES (?, ?, 'APP', '', ?, ?, 1, 1, ?, ?, ?, 0, 0, 'it', 'it')",
                runKey,
                applicationId,
                serviceId,
                releaseId,
                "a".repeat(64),
                "b".repeat(64),
                status);
        return jdbcTemplate.queryForObject("SELECT id FROM ai_run WHERE run_key = ?", Long.class, runKey);
    }

    private AiWebhookDeliveryDO deliveryOf(Long runId) {
        return deliveryOf(runId, targetId);
    }

    private AiWebhookDeliveryDO deliveryOf(Long runId, Long expectedTargetId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_webhook_delivery WHERE target_id = ? AND resource_id = ? AND deleted = b'0'",
                Long.class,
                expectedTargetId,
                runId);
        return deliveryService.get(id);
    }

    private long countDeliveries() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_webhook_delivery WHERE application_id = ?", Long.class, applicationId);
    }

    /** 某个运行在本目标上的投递行数（唯一键语义：要么 0，要么 1）。 */
    private int countDeliveriesOf(Long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_webhook_delivery WHERE target_id = ? AND resource_id = ? AND deleted = b'0'",
                Integer.class,
                targetId,
                runId);
    }

    private int countRunEvents(String runKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_run_event e JOIN ai_run r ON r.id = e.run_id WHERE r.run_key = ?",
                Integer.class,
                runKey);
    }

    private int countRunTasks(Long runId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_run_task WHERE run_id = ?", Integer.class, runId);
    }
}
