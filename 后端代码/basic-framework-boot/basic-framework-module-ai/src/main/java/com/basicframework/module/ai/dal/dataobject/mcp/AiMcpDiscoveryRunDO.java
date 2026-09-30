package com.basicframework.module.ai.dal.dataobject.mcp;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * MCP 发现运行留痕（X07）：每一次工具发现都是一条独立事实。
 *
 * <p>为什么必须留痕而不是打日志：本卡的核心不变量之一是"断线后有界重试 + 明确终止，
 * <b>不得静默降级</b>"。要能证明这一点，需要把
 * {@code attempts}（实际尝试了几次）、{@code termination}（为什么停）、
 * {@code failureCode}（稳定失败编号）落成可查询的结构化数据——
 * 日志既不保证留存，也无法被集成测试断言。
 *
 * <p>因此运维与验收都能回答同一个问题："上次发现到底试了几次、为什么停？"
 * 而不必去猜"这次没发现工具"到底是上游没有还是我们连不上。
 */
@TableName("ai_mcp_discovery_run")
@KeySequence("ai_mcp_discovery_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiMcpDiscoveryRunDO extends SoftDeletableDO {

    /** 终止结果：成功。 */
    public static final String TERMINATION_SUCCESS = "SUCCESS";

    /** 终止结果：有界重试耗尽。 */
    public static final String TERMINATION_RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    /** 终止结果：超时。 */
    public static final String TERMINATION_TIMEOUT = "TIMEOUT";

    /** 终止结果：授权被拒。 */
    public static final String TERMINATION_UNAUTHORIZED = "UNAUTHORIZED";

    /** 终止结果：网络不可达。 */
    public static final String TERMINATION_UNREACHABLE = "UNREACHABLE";

    /** 终止结果：协议错误。 */
    public static final String TERMINATION_PROTOCOL_ERROR = "PROTOCOL_ERROR";

    /** 终止结果：地址被拒（未发出任何请求）。 */
    public static final String TERMINATION_ADDRESS_DENIED = "ADDRESS_DENIED";

    /** 终止结果：协议版本不受支持。 */
    public static final String TERMINATION_PROTOCOL_VERSION_UNSUPPORTED = "PROTOCOL_VERSION_UNSUPPORTED";

    /** 终止结果：响应超限。 */
    public static final String TERMINATION_RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";

    /** 运行编号 */
    @TableId
    private Long id;

    /** 连接器编号 */
    private Long connectorId;

    /** 服务端实现名（协议协商所得，仅供审计）。 */
    private String serverName;

    /** 协商出的协议版本。 */
    private String protocolVersion;

    /** 实际尝试次数（1 表示未重试；这是"有界"的直接证据）。 */
    private Integer attempts;

    /** 终止结果（见本类 TERMINATION_* 常量）。 */
    private String termination;

    /** 失败稳定错误码（成功时为空）。 */
    private Integer failureCode;

    /** 观察到的工具条数（仅成功时有意义；失败恒为 0）。 */
    private Integer toolCount;

    /** 其中新发现的工具条数。 */
    private Integer newToolCount;

    /** 其中结构漂移被阻断的条数。 */
    private Integer blockedCount;

    /** 耗时（毫秒）。 */
    private Long elapsedMillis;

    /** 乐观锁版本。 */
    private Integer version;
}
