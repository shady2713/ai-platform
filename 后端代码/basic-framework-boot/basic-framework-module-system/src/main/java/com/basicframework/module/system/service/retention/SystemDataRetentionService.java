package com.basicframework.module.system.service.retention;

import com.basicframework.module.system.dal.mysql.logger.LoginLogMapper;
import com.basicframework.module.system.dal.mysql.logger.OperateLogMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsLogMapper;
import com.basicframework.module.system.framework.retention.config.SystemDataRetentionProperties;
import java.time.LocalDateTime;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 按配置分批物理清理 system 模块的到期事件记录和用户会话。 */
@Service
@RequiredArgsConstructor
public class SystemDataRetentionService {

    private final LoginLogMapper loginLogMapper;
    private final OperateLogMapper operateLogMapper;
    private final SmsLogMapper smsLogMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final NotifyMessageMapper notifyMessageMapper;
    private final UserSessionMapper userSessionMapper;
    private final SystemDataRetentionProperties properties;

    /**
     * 清理到期记录和会话；站内信只清理已读记录，未读消息不自动删除。
     *
     * @return 各表实际删除数量
     */
    public CleanupResult cleanExpiredData() {
        LocalDateTime now = LocalDateTime.now();
        int loginLogs = clean(now.minusDays(properties.getLoginLogDays()), loginLogMapper::deleteByCreateTimeLt);
        int operateLogs = clean(now.minusDays(properties.getOperateLogDays()), operateLogMapper::deleteByCreateTimeLt);
        int smsLogs = clean(now.minusDays(properties.getSmsLogDays()), smsLogMapper::deleteByCreateTimeLt);
        int smsCodes = clean(now.minusDays(properties.getSmsCodeDays()), smsCodeMapper::deleteByCreateTimeLt);
        int notifyMessages = clean(
                now.minusDays(properties.getReadNotifyMessageDays()), notifyMessageMapper::deleteReadByCreateTimeLt);
        int expiredSessions = clean(now, userSessionMapper::deleteExpired);
        return new CleanupResult(loginLogs, operateLogs, smsLogs, smsCodes, notifyMessages, expiredSessions);
    }

    private int clean(LocalDateTime expireTime, BiFunction<LocalDateTime, Integer, Integer> deleteBatch) {
        int total = 0;
        for (int batch = 0; batch < properties.getMaxBatches(); batch++) {
            int deleted = deleteBatch.apply(expireTime, properties.getBatchSize());
            total += deleted;
            if (deleted < properties.getBatchSize()) {
                break;
            }
        }
        return total;
    }

    /** 各类到期记录的物理删除数量。 */
    public record CleanupResult(
            int loginLogs, int operateLogs, int smsLogs, int smsCodes, int notifyMessages, int expiredSessions) {

        /**
         * 返回适合 Quartz 执行记录与运维检索的稳定摘要。
         *
         * @return 删除数量摘要
         */
        public String summary() {
            return String.format(
                    "登录日志 %d，操作日志 %d，短信日志 %d，短信验证码 %d，已读站内信 %d，过期会话 %d",
                    loginLogs, operateLogs, smsLogs, smsCodes, notifyMessages, expiredSessions);
        }
    }
}
