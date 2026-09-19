package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.job.job.JobLogCleanJob;
import com.basicframework.module.system.job.SystemDataRetentionCleanJob;
import com.basicframework.module.system.service.sms.SmsLogService;
import com.basicframework.module.system.service.sms.SmsReceiveResultCommand;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;

/** 使用真实 MySQL 验证空库迁移、生命周期列与清理任务。 */
class PersistenceLifecycleIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JobLogCleanJob jobLogCleanJob;

    @Autowired
    private SystemDataRetentionCleanJob systemDataRetentionCleanJob;

    @Autowired
    private SmsLogService smsLogService;

    @Test
    void migrationLifecyclePersistenceAndJobs_succeedAgainstRealServices() throws Exception {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(migrationCount).isEqualTo(MigrationTestSupport.migrationCount());
        String currentVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(currentVersion).isEqualTo(String.valueOf(MigrationTestSupport.latestVersion()));
        Integer quartzTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND LEFT(table_name, 5) = 'QRTZ_'",
                Integer.class);
        assertThat(quartzTableCount).isEqualTo(11);
        Integer mfaTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('system_user_mfa_factor', 'system_user_mfa_recovery_code')",
                Integer.class);
        assertThat(mfaTableCount).isZero();
        verifyRemovedExampleMetadataAndAppSeams();
        verifyNormalizedFrontendComponentPaths();
        verifyLifecycleColumns();
        verifySmsReceiptCorrelation();
        verifyPresignedUploadLifecycleSchema();
        assertThat(scheduler.isStarted()).isTrue();
        verifySeedJobsRegisteredInQuartz();
        verifyBuiltInJobIsReentrant();
        verifySystemDataRetentionJob();
    }

    private void verifyPresignedUploadLifecycleSchema() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'infra_file'
                          AND column_name IN ('upload_status', 'upload_staging_path', 'upload_token_hash', 'upload_expires_at',
                                              'upload_user_id', 'upload_user_type')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactly(
                        "upload_expires_at",
                        "upload_staging_path",
                        "upload_status",
                        "upload_token_hash",
                        "upload_user_id",
                        "upload_user_type");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'infra_file' "
                                + "AND index_name IN ('uk_upload_token_hash', 'uk_upload_staging_path', "
                                + "'uk_config_path', 'idx_upload_expiry')",
                        Integer.class))
                .isEqualTo(4);
    }

    private void verifySmsReceiptCorrelation() {
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_log
                    (channel_id, channel_code, template_id, template_code, template_type,
                     template_content, template_params, api_template_id, mobile,
                     send_status, api_serial_no, receive_status, create_time)
                VALUES
                    (1, 'ALIYUN', 1, 'receipt-aliyun', 1, 'receipt', '{}', '1',
                     '13900000003', 10, 'receipt-serial-aliyun', 0, NOW()),
                    (2, 'LEGACY', 2, 'receipt-legacy', 1, 'receipt', '{}', '2',
                     '13900000004', 10, 'receipt-serial-legacy', 0, NOW())
                """);
        Long aliyunLogId = jdbcTemplate.queryForObject(
                "SELECT id FROM system_sms_log WHERE api_serial_no = 'receipt-serial-aliyun'", Long.class);

        assertThat(smsLogService.updateSmsReceiveResult(new SmsReceiveResultCommand(
                        "ALIYUN",
                        aliyunLogId,
                        "receipt-serial-aliyun",
                        true,
                        LocalDateTime.now(),
                        "DELIVERED",
                        "delivered")))
                .isTrue();
        assertThat(smsLogService.updateSmsReceiveResult(new SmsReceiveResultCommand(
                        "LEGACY", null, "receipt-serial-legacy", false, LocalDateTime.now(), "FAIL", "failed")))
                .isTrue();
        assertThat(smsLogService.updateSmsReceiveResult(new SmsReceiveResultCommand(
                        "ALIYUN", null, "receipt-serial-legacy", true, LocalDateTime.now(), null, null)))
                .isFalse();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT receive_status FROM system_sms_log WHERE api_serial_no = 'receipt-serial-aliyun'",
                        Integer.class))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT receive_status FROM system_sms_log WHERE api_serial_no = 'receipt-serial-legacy'",
                        Integer.class))
                .isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                                + "AND table_name = 'system_sms_log' AND index_name = 'uk_channel_api_serial_no'",
                        Integer.class))
                .isEqualTo(2);
    }

    /** 启动注册器（JobStartupRegistrar）在应用就绪后自动执行 syncJob，种子任务无需人工触发。 */
    private void verifySeedJobsRegisteredInQuartz() {
        assertThat(jdbcTemplate.queryForList("SELECT JOB_NAME FROM QRTZ_JOB_DETAILS", String.class))
                .containsExactlyInAnyOrder(
                        "accessLogCleanJob",
                        "errorLogCleanJob",
                        "fileDeletionRetryJob",
                        "infraDataIntegrityAuditJob",
                        "jobLogCleanJob",
                        "systemDataIntegrityAuditJob",
                        "systemDataRetentionCleanJob");
    }

    private void verifyNormalizedFrontendComponentPaths() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE component IN "
                                + "('infra/file-config/index', 'system/login-log/index', "
                                + "'system/operate-log/index')",
                        Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE component IN "
                                + "('infra/fileConfig/index', 'system/loginlog/index', "
                                + "'system/operatelog/index')",
                        Integer.class))
                .isZero();
    }

    private void verifyBuiltInJobIsReentrant() throws Exception {
        jdbcTemplate.update("INSERT INTO infra_job_log "
                + "(job_id, handler_name, execute_index, begin_time, status, create_time) "
                + "VALUES (25, 'integrationReentrantCleanup', 1, NOW() - INTERVAL 30 DAY, 1, "
                + "NOW() - INTERVAL 30 DAY)");

        assertThat(jobLogCleanJob.execute("")).contains("1 个");
        assertThat(jobLogCleanJob.execute("")).contains("0 个");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_job_log WHERE handler_name = 'integrationReentrantCleanup'",
                        Integer.class))
                .isZero();
    }

    private void verifySystemDataRetentionJob() {
        jdbcTemplate.update(
                """
                INSERT INTO system_login_log
                    (log_type, trace_id, user_id, user_type, username, result, user_ip, user_agent, create_time)
                VALUES
                    (100, 'retention-old', 1, 2, 'retention', 0, '127.0.0.1', 'integration', '2000-01-01'),
                    (100, 'retention-current', 1, 2, 'retention', 0, '127.0.0.1', 'integration', NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_operate_log
                    (trace_id, user_id, user_type, type, sub_type, biz_id, action, success, extra, create_time)
                VALUES
                    ('retention-old', 1, 2, 'retention', 'old', 1, 'old', b'1', '', '2000-01-01'),
                    ('retention-current', 1, 2, 'retention', 'current', 1, 'current', b'1', '', NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_log
                    (channel_id, channel_code, template_id, template_code, template_type,
                     template_content, template_params, api_template_id, mobile,
                     send_status, receive_status, create_time)
                VALUES
                    (1, 'retention', 1, 'retention-old', 1, 'old', '{}', '1', '13900000001', 0, 0, '2000-01-01'),
                    (1, 'retention', 1, 'retention-current', 1, 'current', '{}', '1', '13900000001', 0, 0, NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_notify_message
                    (user_id, user_type, template_id, template_code, template_nickname,
                     template_content, template_type, template_params, read_status, create_time)
                VALUES
                    (1, 2, 1, 'retention-old-read', 'system', 'old', 1, '{}', b'1', '2000-01-01'),
                    (1, 2, 1, 'retention-old-unread', 'system', 'old', 1, '{}', b'0', '2000-01-01'),
                    (1, 2, 1, 'retention-current-read', 'system', 'current', 1, '{}', b'1', NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_code
                    (mobile, code, create_ip, scene, today_index, used, create_time)
                VALUES
                    ('13900000002', '900001', '127.0.0.1', 1, 1, b'1', '2000-01-01'),
                    ('13900000002', '900002', '127.0.0.1', 1, 2, b'0', '2000-01-01'),
                    ('13900000002', '900003', '127.0.0.1', 1, 3, b'0', NOW())
                """);

        assertThat(systemDataRetentionCleanJob.execute("")).isEqualTo("登录日志 1，操作日志 1，短信日志 1，短信验证码 2，已读站内信 1，过期会话 0");
        assertThat(systemDataRetentionCleanJob.execute("")).isEqualTo("登录日志 0，操作日志 0，短信日志 0，短信验证码 0，已读站内信 0，过期会话 0");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_login_log WHERE trace_id LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_operate_log WHERE trace_id LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_sms_log WHERE template_code LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_notify_message WHERE template_code LIKE 'retention-%'",
                        Integer.class))
                .isEqualTo(2);
        // 过期的已用/未用验证码都被清理，仅保留保留期内的记录
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_sms_code WHERE mobile = '13900000002'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_job WHERE id BETWEEN 25 AND 30 AND status = 1", Integer.class))
                .isEqualTo(6);
    }

    private void verifyLifecycleColumns() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND column_name = 'deleted'
                        ORDER BY table_name
                        """,
                        String.class))
                .containsExactly(
                        "ai_access_ticket",
                        "ai_application",
                        "ai_application_credential",
                        "ai_conversation",
                        "ai_conversation_message",
                        "ai_file_binding",
                        "ai_model_endpoint",
                        "ai_model_endpoint_revision",
                        "ai_model_probe",
                        "ai_resource_grant",
                        "ai_service",
                        "ai_service_release",
                        "ai_service_release_evaluation",
                        "ai_service_resource",
                        "ai_subject",
                        "infra_config",
                        "infra_file_config",
                        "infra_job",
                        "system_dept",
                        "system_dict_data",
                        "system_dict_type",
                        "system_menu",
                        "system_notice",
                        "system_notify_template",
                        "system_post",
                        "system_role",
                        "system_sms_channel",
                        "system_sms_template",
                        "system_users");
    }

    private void verifyRemovedExampleMetadataAndAppSeams() {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_name = 'crm_customer'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE id BETWEEN 3000 AND 3004", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() "
                                + "AND table_name IN ('infra_codegen_table', 'infra_codegen_column')",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE id = 115 OR parent_id = 115 "
                                + "OR permission LIKE 'infra:codegen:%'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_data WHERE dict_type LIKE 'infra_codegen_%' "
                                + "OR dict_type = 'date_interval'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type = 'date_interval'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type LIKE 'infra_codegen_%'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT CAST(value AS UNSIGNED) FROM system_dict_data "
                                + "WHERE dict_type = 'terminal' AND deleted = b'0' ORDER BY CAST(value AS UNSIGNED)",
                        Integer.class))
                .containsExactly(10, 11, 20, 31, 32);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT CAST(value AS UNSIGNED) FROM system_dict_data "
                                + "WHERE dict_type = 'user_type' AND deleted = b'0' ORDER BY CAST(value AS UNSIGNED)",
                        Integer.class))
                .containsExactly(1, 2);
    }
}
