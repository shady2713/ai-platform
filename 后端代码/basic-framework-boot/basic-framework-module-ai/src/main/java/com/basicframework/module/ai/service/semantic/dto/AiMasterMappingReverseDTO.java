package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 按源键反查统一对象的请求（Y02）："这条 CRM 客户编号在企业里是谁"。
 *
 * <p>反查只用来源系统给出的**源键**：同名不会命中（判定路径不读取展示名），未登记的源键返回
 * {@code mapped=false}（未映射即不关联），有效期不覆盖判定时刻或指向多个对象时阻断。
 */
@Data
@Accessors(chain = true)
public class AiMasterMappingReverseDTO {

    /** 来源系统（接入应用）编号 */
    private Long applicationId;

    /** 该系统内的实体类型 */
    private String entityType;

    /** 来源系统里的业务主键 */
    private String sourceKey;

    /** 判定时刻（有效期按此时刻计算） */
    private LocalDateTime asOf;
}
