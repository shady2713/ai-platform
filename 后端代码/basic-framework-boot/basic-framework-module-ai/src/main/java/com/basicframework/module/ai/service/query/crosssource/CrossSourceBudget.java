package com.basicframework.module.ai.service.query.crosssource;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE;

import java.time.Duration;
import java.util.Locale;

/**
 * 跨源执行预算（Y04）：每源的并发/超时/行数/内存上限与全局内存上限。
 *
 * <p>预算的定位是**受控结束**：跨源聚合必须先把每个来源按主键粒度在源内聚合、再拉取有界中间结果，
 * 但"有界"本身需要一个服务端硬上限——没有上限时，一次误配的扇出就会把进程内存吃光，
 * 而被内存杀掉的执行既没有错误码也没有可查的记录。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>调用方只能往下调</b>：每个上限都有服务端硬顶，传入值超过硬顶即拒绝，而不是静默夹到硬顶
 *       （静默夹断会让调用方以为跑的是它要的预算）；</li>
 *   <li><b>行数与内存是两道独立的闸</b>：行数防"结果集太大"，字节数防"每行都很宽"——
 *       只看行数时，1000 行 × 100KB 仍然是 100MB；</li>
 *   <li><b>全局内存上限独立于单源</b>：N 个源各 10MB 合计 100MB 时，单源都没越界但进程已经危险。</li>
 * </ol>
 *
 * <p>不可变 record：预算在执行期间不能被改写，否则"执行到一半预算变了"会让同一份结果不可复现。
 */
public record CrossSourceBudget(
        int maxSourceRows,
        long maxSourceBytes,
        int maxConcurrentSources,
        int maxSourceTimeoutMillis,
        long maxTotalBytes,
        int maxSkewSeconds) {

    /**
     * 单源行数硬顶（字节之外的第二道）：999 行。
     *
     * <p>比 D03/D06 的 {@code MAX_ROWS}（1000）**少一行**是刻意的：跨源层必须留出一行探测位，
     * 否则来源的 {@code LIMIT} 会在跨源层看到结果之前就把结果截到预算值，
     * 于是"刚好装下"与"被静默截断"变得无法区分（见 {@code MysqlCrossSourceSourceFetcher}）。
     */
    public static final int HARD_MAX_SOURCE_ROWS =
            com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryRequest.MAX_ROWS - 1;

    /** 单源内存硬顶（字节）：4MB。 */
    public static final long HARD_MAX_SOURCE_BYTES = 4L * 1024 * 1024;

    /** 并发来源数硬顶：同时打开的只读连接数上限。 */
    public static final int HARD_MAX_CONCURRENT_SOURCES = 8;

    /** 单源超时硬顶（毫秒）：与 D03 的 {@code MAX_TIMEOUT_MILLIS} 一致。 */
    public static final int HARD_MAX_SOURCE_TIMEOUT_MILLIS = 30_000;

    /** 全局中间结果内存硬顶（字节）：64MB。 */
    public static final long HARD_MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /** 数据时间点偏移容忍硬顶（秒）：24 小时。 */
    public static final int HARD_MAX_SKEW_SECONDS = 86_400;

    /** 默认单源结果行数上限（含一行探测位，故为硬顶减一）。 */
    public static final int MAX_RESULT_ROWS = HARD_MAX_SOURCE_ROWS;

    public CrossSourceBudget {
        if (maxSourceRows < 1 || maxSourceRows > HARD_MAX_SOURCE_ROWS) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxSourceBytes < 1 || maxSourceBytes > HARD_MAX_SOURCE_BYTES) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxConcurrentSources < 1 || maxConcurrentSources > HARD_MAX_CONCURRENT_SOURCES) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxSourceTimeoutMillis < 1 || maxSourceTimeoutMillis > HARD_MAX_SOURCE_TIMEOUT_MILLIS) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxTotalBytes < maxSourceBytes) {
            // 全局上限低于单源上限意味着单源合法预算在全局就不合法：配置自相矛盾，直接拒绝
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxTotalBytes > HARD_MAX_TOTAL_BYTES) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
        if (maxSkewSeconds < 0 || maxSkewSeconds > HARD_MAX_SKEW_SECONDS) {
            throw exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        }
    }

    /** 默认预算：每源 1000 行 / 4MB，全局 16MB，最多 4 源并发，单源 10s，允许 5 分钟数据时间偏移。 */
    public static CrossSourceBudget defaults() {
        return new CrossSourceBudget(MAX_RESULT_ROWS, HARD_MAX_SOURCE_BYTES, 4, 10_000, 16L * 1024 * 1024, 300);
    }

    /** 单源超时（{@link Duration} 形态，供调度器直接使用）。 */
    public Duration sourceTimeout() {
        return Duration.ofMillis(maxSourceTimeoutMillis);
    }

    /** 预算摘要（进日志与执行记录；不含任何数据值）。 */
    public String describe() {
        return String.format(
                Locale.ROOT,
                "rows/source=%d, bytes/source=%d, concurrent=%d, timeout=%dms, total=%d, skew=%ds",
                maxSourceRows,
                maxSourceBytes,
                maxConcurrentSources,
                maxSourceTimeoutMillis,
                maxTotalBytes,
                maxSkewSeconds);
    }
}
