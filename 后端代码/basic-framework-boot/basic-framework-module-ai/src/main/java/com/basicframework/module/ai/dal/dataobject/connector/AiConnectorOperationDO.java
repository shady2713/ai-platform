package com.basicframework.module.ai.dal.dataobject.connector;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 连接器操作（D02）：由 OpenAPI 导入的**声明式**操作，发布后才可执行。
 *
 * <p>方法、路径模板、参数声明、响应提取与分页规则都在这里声明；执行时不允许模型或调用方
 * 替换 header 与 URL，只允许按声明填入参数值。
 */
@TableName("ai_connector_operation")
@KeySequence("ai_connector_operation_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiConnectorOperationDO extends SoftDeletableDO {

    /** 状态：草稿（导入结果，不可执行） */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：已发布（可执行） */
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 操作编号 */
    @TableId
    private Long id;

    /** 连接器编号 */
    private Long connectorId;

    /** 操作标识（OpenAPI operationId 或 method+path 派生） */
    private String operationKey;

    /** HTTP 方法（GET/POST） */
    private String httpMethod;

    /** 路径模板（以 / 开头，占位符形如 {id}） */
    private String pathTemplate;

    /** 操作说明 */
    private String summary;

    /** 参数声明（JSON 数组：名称/位置/是否必填/类型；不含脚本） */
    private String parameterJson;

    /** 响应提取规则（JSON 对象：根路径与列表路径） */
    private String responseJson;

    /** 分页规则（JSON 对象：NONE/PAGE/CURSOR + 参数名 + 页数上限 + 游标字段） */
    private String paginationJson;

    /** 状态（DRAFT/PUBLISHED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
