package com.basicframework.module.ai.service.semantic.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 主数据映射目录（Y02）：某个主体在指定对象/版本下**能看到**的跨系统映射事实。
 *
 * <p>三条与 Y01 授权发现一致的语义：
 * <ol>
 *   <li><b>无权不出现</b>：主体在某系统没有可访问范围时，该系统在该版本里的映射条目不出现在
 *       {@link #entries} 里，也不出现在 {@link #modelCatalog} 里；</li>
 *   <li><b>拒绝不可区分</b>：主体不可用/无任何可访问系统时返回 {@code denied=true} 的空目录，
 *       与"主体从未登记"完全同形（指纹只由身份与条目决定），不能借目录枚举主体；</li>
 *   <li><b>预算有界</b>：可见条目超过发现预算时拒绝返回**部分**目录（稳定错误码），不静默截断。</li>
 * </ol>
 *
 * <p>{@link #modelCatalog} 是允许送进模型的目录（只含对象/系统/实体类型/可用性，**不含源键值**）：
 * 键值是业务数据，留在服务端参与关联执行，不进入提示词。
 */
@Data
@Accessors(chain = true)
public class AiMasterObjectCatalogDTO {

    /** 统一对象编号 */
    private Long masterObjectId;

    /** 统一对象标识 */
    private String objectCode;

    /** 对象名称（仅展示） */
    private String objectName;

    /** 对象类型 */
    private String objectType;

    /** 被解释的映射版本号 */
    private Long revisionNo;

    /** 该版本发布时冻结的内容指纹 */
    private String revisionFingerprint;

    /** 判定时刻 */
    private LocalDateTime asOf;

    /** 是否被拒绝（true 时 entries 为空，且与"主体不存在"同语义） */
    private boolean denied;

    /** 可见映射条目（按系统标识 + 实体类型 + 源键排序） */
    private List<AiMasterCatalogEntryDTO> entries;

    /** 目录指纹：身份 + 对象 + 版本 + 条目的稳定摘要 */
    private String catalogFingerprint;

    /** 模型可见目录（只含对象/系统/实体类型/可用性，不含源键值） */
    private String modelCatalog;
}
