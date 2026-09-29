package com.basicframework.module.ai.service.context.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 范围选择结果（Y01）：一次跨系统分析的**可核验事实**。
 *
 * <p>它与"用户界面上勾了哪些系统"不是一回事：这里保存的是服务端在选定时刻重新计算出的
 * 事实（每个系统的访问指纹 + 目录指纹），并用 {@link #selectionFingerprint} 把它们封起来。
 * 后续任何环节（运行受理、跨源执行、历史产物读取）都可以用
 * {@code AiAnalysisScopeService#verify} 拿同一份选择重新校验：
 * <ul>
 *   <li>授权被撤销、主体被停用、联邦映射被撤销或改版本、范围解析结果变化 → 指纹必然变化，
 *       校验返回 409（拒绝继续），**不会**静默改用"当前仍然可访问的系统"；</li>
 *   <li>{@link #modelCatalog} 只包含已选定系统，因此模型可见的系统集合不超过这次选择的授权事实。</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
public class AiAnalysisScopeSelectionDTO {

    /** 当前应用编号 */
    private Long applicationId;

    /** 当前主体类型 */
    private String subjectType;

    /** 当前主体外部用户标识 */
    private String externalUserId;

    /** 选择模式（CURRENT_SYSTEM/CROSS_SYSTEM） */
    private String mode;

    /** 目标系统标识（已归一化：当前系统在最前，其余按系统标识升序） */
    private List<String> targetSystemCodes;

    /** 选择依据的目录指纹 */
    private String catalogFingerprint;

    /** 被选中的系统（含各自的访问指纹） */
    private List<AiSelectedSystemDTO> systems;

    /** 模型可见目录（只含被选中系统的系统标识与资源清单） */
    private String modelCatalog;

    /** 选择指纹：模式 + 选定系统指纹 + 目录指纹的稳定摘要（可重新计算比对） */
    private String selectionFingerprint;
}
