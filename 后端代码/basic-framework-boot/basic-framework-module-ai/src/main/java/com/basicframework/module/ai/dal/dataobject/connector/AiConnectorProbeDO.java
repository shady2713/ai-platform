package com.basicframework.module.ai.dal.dataobject.connector;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** 连接器探测结论（D01）：只记录稳定原因码与耗时，不记录凭据、主机或异常正文。 */
@TableName("ai_connector_probe")
@KeySequence("ai_connector_probe_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiConnectorProbeDO extends SoftDeletableDO {

    /** 探测类型：HTTP 连通性 */
    public static final String KIND_HTTP_CONNECTIVITY = "HTTP_CONNECTIVITY";

    /** 探测类型：MySQL 连通性 */
    public static final String KIND_MYSQL_CONNECTIVITY = "MYSQL_CONNECTIVITY";

    /** 结论：可用 */
    public static final String STATUS_SUPPORTED = "SUPPORTED";

    /** 结论：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 探测记录编号 */
    @TableId
    private Long id;

    /** 连接器编号 */
    private Long connectorId;

    /** 探测类型 */
    private String probeKind;

    /** 结论（SUPPORTED/FAILED） */
    private String status;

    /** 失败原因码（稳定词表；成功为空） */
    private String detailCode;

    /** 耗时（毫秒） */
    private Integer latencyMs;

    /** 乐观锁版本 */
    private Integer version;
}
