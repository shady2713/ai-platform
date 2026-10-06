package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 跨源错误出口（Y04）：工厂方法与稳定错误码一一对应，重试判定只放行"来源没跑成"。
 *
 * <p>这个类的全部价值在于**调用方能区分为什么没算出来**：同一语义被写成两个编号，
 * 调用方就只能看到一个笼统的失败，重试策略也无从判断。因此这里逐个锁死工厂→编号的映射，
 * 而不是抽查一两个出口。
 */
class AiCrossSourceExecutionErrorsTest {

    /** 一个出口：名字、工厂方法与它必须抛出的编号（名字只用于断言失败时定位）。 */
    private record Exit(String name, Supplier<RuntimeException> exit, ErrorCode code) {}

    private static List<Exit> exits() {
        return List.of(
                new Exit(
                        "executionNotExists",
                        AiCrossSourceExecutionErrors::executionNotExists,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS),
                new Exit(
                        "executionKeyConflict",
                        AiCrossSourceExecutionErrors::executionKeyConflict,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT),
                new Exit(
                        "planSourceNotDeclared",
                        AiCrossSourceExecutionErrors::planSourceNotDeclared,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED),
                new Exit(
                        "sourceTimeout",
                        AiCrossSourceExecutionErrors::sourceTimeout,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT),
                new Exit(
                        "sourceFailed",
                        AiCrossSourceExecutionErrors::sourceFailed,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT),
                new Exit(
                        "sourceCancelled",
                        AiCrossSourceExecutionErrors::sourceCancelled,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CANCELLED_CONFLICT),
                new Exit(
                        "concurrencyExceeded",
                        AiCrossSourceExecutionErrors::concurrencyExceeded,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT),
                new Exit(
                        "resultTooLarge",
                        AiCrossSourceExecutionErrors::resultTooLarge,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE),
                new Exit(
                        "resultTruncated",
                        AiCrossSourceExecutionErrors::resultTruncated,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT),
                new Exit(
                        "consistencySkew",
                        AiCrossSourceExecutionErrors::consistencySkew,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT),
                new Exit(
                        "capacityExceeded",
                        AiCrossSourceExecutionErrors::capacityExceeded,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED),
                new Exit(
                        "capacityRegistrationConflict",
                        AiCrossSourceExecutionErrors::capacityRegistrationConflict,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT),
                new Exit(
                        "alreadyCounted",
                        AiCrossSourceExecutionErrors::alreadyCounted,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT),
                new Exit(
                        "entityKeyMissing",
                        AiCrossSourceExecutionErrors::entityKeyMissing,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT),
                new Exit(
                        "entityKeyRevisionConflict",
                        AiCrossSourceExecutionErrors::entityKeyRevisionConflict,
                        AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT));
    }

    @Test
    void everyExitRaisesItsOwnStableCode() {
        List<Exit> all = exits();

        for (Exit exit : all) {
            // 工厂返回的是"待抛出的异常"，抛不抛由调用点决定；这里只锁死它带的是哪个编号
            assertThat(exit.exit().get())
                    .as("出口 %s 必须落到自己的稳定编号", exit.name())
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                            .isEqualTo(exit.code().getCode()));
        }

        // 编号互不相同：两个出口共用一个编号等于没区分，调用方与重试策略都会失去依据
        assertThat(all.stream().map(exit -> exit.code()).distinct()).hasSameSizeAs(all);
    }

    @Test
    void onlyTimeoutAndSourceFailureAreRetryable() {
        // 值得重试的只有两种：来源没在预算内完成、来源自身失败——换一次连接可能就成了
        assertThat(AiCrossSourceExecutionErrors.retryable(AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT))
                .as("超时：来源没在预算内完成")
                .isTrue();
        assertThat(AiCrossSourceExecutionErrors.retryable(AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT))
                .as("来源失败：可能是一次性的上游抖动")
                .isTrue();
        // 取消是"调用方自己不要了"：重试等于替它白干一遍，还会让调度器去救一个已被放弃的请求
        assertThat(AiCrossSourceExecutionErrors.retryable(AiErrorCodeConstants.AI_CROSS_SOURCE_CANCELLED_CONFLICT))
                .as("取消：调用方已经放弃，重试只是替它白干")
                .isFalse();
        // 并发超限：重试只会立刻再撞一次同一条并发上限，证据指向扇出与调度而不是本次数据
        assertThat(AiCrossSourceExecutionErrors.retryable(
                        AiErrorCodeConstants.AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT))
                .as("并发超限：重试只会重复同一次失败")
                .isFalse();
        for (ErrorCode code : List.of(
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE,
                AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT,
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS,
                AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT,
                AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT,
                AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED,
                AiErrorCodeConstants.AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT,
                AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED)) {
            assertThat(AiCrossSourceExecutionErrors.retryable(code))
                    .as("受控结束类出口 %s 重试多少次都是同一个结果", code.getCode())
                    .isFalse();
        }
        // 调用方可能还没定错误码就来问策略：不能因为空码就抛异常
        assertThat(AiCrossSourceExecutionErrors.retryable(null)).isFalse();
    }
}
