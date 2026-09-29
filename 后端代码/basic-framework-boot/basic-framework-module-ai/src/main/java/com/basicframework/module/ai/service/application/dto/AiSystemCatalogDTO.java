package com.basicframework.module.ai.service.application.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 多系统授权目录（Y01）：某个外部主体"在哪些系统里有哪些可访问范围"的唯一事实来源。
 *
 * <p>两条不可让步的语义：
 * <ol>
 *   <li><b>无权系统不出现</b>：无权/未批准/已停用/范围不可解析的系统都不在 {@link #entries} 里，
 *       也不出现在 {@link #modelCatalog} 里；</li>
 *   <li><b>拒绝不可区分</b>：主体未登记、已停用、范围被拒、或没有任何可用授权，返回的都是
 *       {@code denied=true} + 空目录（响应完全一致），因此不能通过目录枚举主体是否存在。
 *       {@code denied=true} 时 {@link #catalogFingerprint} 仍给出稳定值（对本次身份事实的摘要）。</li>
 * </ol>
 *
 * <p>{@link #modelCatalog} 是**允许送进模型的系统目录**（只含 {@link #entries} 的系统标识与
 * 资源清单，不含外部用户标识），它随目录一起冻结：模型看到的系统集合不允许超出主体的授权事实。
 */
@Data
@Accessors(chain = true)
public class AiSystemCatalogDTO {

    /** 当前应用编号 */
    private Long applicationId;

    /** 当前主体类型 */
    private String subjectType;

    /** 当前主体外部用户标识 */
    private String externalUserId;

    /** 是否被拒绝（true 时 entries 为空，且与"主体不存在"同语义） */
    private boolean denied;

    /** 可访问系统（当前系统在最前，其余按系统标识升序） */
    private List<AiSystemEntryDTO> entries;

    /** 目录指纹：本次身份事实 + 全部条目的稳定摘要（用于范围选择的"可核验事实"） */
    private String catalogFingerprint;

    /** 模型可见目录（只含可访问系统的系统标识与资源清单；无系统时为空数组文本） */
    private String modelCatalog;
}
