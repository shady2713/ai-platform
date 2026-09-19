package com.basicframework.module.ai.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A04 换票失败节流：窗口内超限 429、成功后清零、不同客户端互不影响、窗口过期后恢复。 */
class AiTicketAttemptThrottleTest {

    @Test
    void blocksAfterConfiguredFailuresAndResetsOnSuccess() {
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(3, Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            throttle.checkAllowed("1.2.3.4|crm-portal");
            throttle.recordFailure("1.2.3.4|crm-portal");
        }

        assertThatThrownBy(() -> throttle.checkAllowed("1.2.3.4|crm-portal"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode());

        // 另一个客户端不受影响
        assertThatCode(() -> throttle.checkAllowed("5.6.7.8|crm-portal")).doesNotThrowAnyException();

        // 成功换票清零计数
        throttle.recordSuccess("1.2.3.4|crm-portal");
        assertThatCode(() -> throttle.checkAllowed("1.2.3.4|crm-portal")).doesNotThrowAnyException();
        assertThat(throttle.trackedKeys()).isZero();
    }

    @Test
    void windowExpiryRestoresAccess() throws InterruptedException {
        // 窗口取 2 秒：窗口内"仍然拦截"的断言不再依赖线程调度抖动（原 50ms 窗口在门禁满载时会被调度延迟吃掉），
        // 过期仍用真实等待验证，语义不变
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(1, Duration.ofSeconds(2));
        throttle.checkAllowed("ip|app");
        throttle.recordFailure("ip|app");
        assertThatThrownBy(() -> throttle.checkAllowed("ip|app")).isInstanceOf(ServiceException.class);

        Thread.sleep(2_100);
        assertThatCode(() -> throttle.checkAllowed("ip|app")).doesNotThrowAnyException();
    }

    @Test
    void invalidConfigurationFallsBackToSafeDefaults() {
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(0, Duration.ZERO);
        assertThatCode(() -> throttle.checkAllowed("ip|app")).doesNotThrowAnyException();
        throttle.recordFailure("ip|app");
        assertThat(throttle.trackedKeys()).isEqualTo(1);
    }
}
