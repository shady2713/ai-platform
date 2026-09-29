package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 范围选择结果（协议层 VO，Y01）：这次跨系统分析被哪些系统授权、依据哪一份目录事实。
 *
 * <p>{@code selectionFingerprint} 是该事实的封条：后续流程在运行前用
 * {@code POST /ai/application/discovery/verify} 携带同一份字段重新核验；事实变化返回 409。
 */
@Schema(description = "管理后台 - 跨系统分析范围选择结果")
@Data
@Accessors(chain = true)
public class AiAnalysisScopeSelectionRespVO {

    @Schema(description = "当前应用编号")
    private Long applicationId;

    @Schema(description = "当前主体类型")
    private String subjectType;

    @Schema(description = "当前主体外部用户标识")
    private String externalUserId;

    @Schema(description = "选择模式（CURRENT_SYSTEM/CROSS_SYSTEM）")
    private String mode;

    @Schema(description = "目标系统标识（当前系统在最前，其余按系统标识升序）")
    private List<String> targetSystemCodes;

    @Schema(description = "选择依据的目录指纹")
    private String catalogFingerprint;

    @Schema(description = "被选中的系统")
    private List<SelectedSystem> systems;

    @Schema(description = "模型可见目录（JSON 文本，只含被选中系统）")
    private String modelCatalog;

    @Schema(description = "选择指纹（可重新计算比对）")
    private String selectionFingerprint;

    /** 被选中的一个系统。 */
    @Schema(description = "被选中的系统")
    @Data
    @Accessors(chain = true)
    public static class SelectedSystem {

        @Schema(description = "系统（应用）编号")
        private Long applicationId;

        @Schema(description = "系统标识（应用 appCode）")
        private String appCode;

        @Schema(description = "系统名称")
        private String systemName;

        @Schema(description = "是否为当前系统")
        private boolean currentSystem;

        @Schema(description = "联邦映射编号（当前系统为空）")
        private Long federationId;

        @Schema(description = "该系统在选定时刻的访问指纹")
        private String systemFingerprint;
    }
}
