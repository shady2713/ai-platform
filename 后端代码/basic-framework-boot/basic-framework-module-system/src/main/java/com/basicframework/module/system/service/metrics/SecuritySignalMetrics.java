package com.basicframework.module.system.service.metrics;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 安全信号指标：把威胁模型中「必须保持的边界」变成可告警的运行期信号。
 *
 * <p>门禁只能证明控制在 CI 那一刻成立，不能说明上线后仍然生效。本类把 T1（爆破与账号枚举）、
 * T2（刷新令牌重放）与 T3（控制面会话撤销）的判定点暴露为计数器，供告警规则消费。
 * 指标语义、阈值与处置步骤见 {@code docs/security/security-signals.md}；两处由
 * {@code scripts/check-security-signals.mjs} 门禁保持一致。
 *
 * <p>指标永远不得影响业务结果：所有记录方法都不抛出异常，也不参与事务判定。
 */
@Component
public class SecuritySignalMetrics {

    /** 账号密码登录失败次数（威胁模型 T1）。 */
    public static final String LOGIN_FAILURES = "basic_framework.auth.login.failures";

    /** 账号因连续登录失败被锁定次数（威胁模型 T1）。 */
    public static final String ACCOUNT_LOCKS = "basic_framework.auth.account.locks";

    /** 旧代刷新令牌重放检测次数（威胁模型 T2）：任何非零值都必须告警。 */
    public static final String REFRESH_REPLAYS = "basic_framework.auth.refresh.replays";

    /** 会话撤销次数，按原因区分（威胁模型 T3）。 */
    public static final String SESSION_REVOCATIONS = "basic_framework.auth.session.revocations";

    private final MeterRegistry registry;
    private final Counter loginFailures;
    private final Counter accountLocks;
    private final Counter refreshReplays;

    public SecuritySignalMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.loginFailures =
                Counter.builder(LOGIN_FAILURES).description("账号密码登录失败次数").register(registry);
        this.accountLocks =
                Counter.builder(ACCOUNT_LOCKS).description("账号因连续登录失败被锁定次数").register(registry);
        this.refreshReplays =
                Counter.builder(REFRESH_REPLAYS).description("旧代刷新令牌重放检测次数").register(registry);
    }

    /** 记录一次账号密码登录失败。 */
    public void recordLoginFailure() {
        loginFailures.increment();
    }

    /** 记录一次账号因连续失败被锁定。 */
    public void recordAccountLock() {
        accountLocks.increment();
    }

    /** 记录一次旧代刷新令牌重放检测。 */
    public void recordRefreshReplay() {
        refreshReplays.increment();
    }

    /**
     * 记录会话撤销。
     *
     * @param reason 撤销原因；为空时不记录
     * @param count 本次撤销的会话数；非正数时不记录
     */
    public void recordSessionRevocation(UserSessionRevocationReasonEnum reason, int count) {
        if (reason == null || count <= 0) {
            return;
        }
        registry.counter(SESSION_REVOCATIONS, "reason", reason.name()).increment(count);
    }
}
