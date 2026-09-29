package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 多系统授权目录响应（协议层 VO，Y01）。
 *
 * <p>无权系统不会以任何形式出现：条目列表与模型可见目录都只含当前主体确实可访问的系统。
 * {@code denied=true} 时条目为空，且与"主体未登记"完全同形（不枚举主体）。
 */
@Schema(description = "管理后台 - 多系统授权目录")
@Data
@Accessors(chain = true)
public class AiSystemCatalogRespVO {

    @Schema(description = "当前应用编号")
    private Long applicationId;

    @Schema(description = "当前主体类型")
    private String subjectType;

    @Schema(description = "当前主体外部用户标识")
    private String externalUserId;

    @Schema(description = "是否被拒绝（true 时没有任何可访问系统）")
    private boolean denied;

    @Schema(description = "可访问系统（当前系统在最前，其余按系统标识升序）")
    private List<SystemEntry> entries;

    @Schema(description = "目录指纹（范围选择据此判断事实是否变化）")
    private String catalogFingerprint;

    @Schema(description = "模型可见目录（JSON 文本，只含可访问系统）")
    private String modelCatalog;

    /** 目录中的一个系统条目。 */
    @Schema(description = "授权目录系统条目")
    @Data
    @Accessors(chain = true)
    public static class SystemEntry {

        @Schema(description = "系统（应用）编号")
        private Long applicationId;

        @Schema(description = "系统标识（应用 appCode，稳定不可修改）")
        private String appCode;

        @Schema(description = "系统名称")
        private String systemName;

        @Schema(description = "是否为当前系统")
        private boolean currentSystem;

        @Schema(description = "联邦映射编号（当前系统为空）")
        private Long federationId;

        @Schema(description = "联邦映射版本")
        private Long federationRevision;

        @Schema(description = "主体在该系统中的类型")
        private String subjectType;

        @Schema(description = "主体在该系统中的外部用户标识")
        private String externalUserId;

        @Schema(description = "范围来源标识")
        private String scopeSource;

        @Schema(description = "范围版本")
        private Long scopeVersion;

        @Schema(description = "该系统内的可访问范围")
        private List<ScopeRow> scopes;

        @Schema(description = "系统访问指纹")
        private String systemFingerprint;
    }

    /** 单条可访问范围。 */
    @Schema(description = "系统内的一条可访问范围")
    @Data
    @Accessors(chain = true)
    public static class ScopeRow {

        @Schema(description = "资源类型")
        private String resourceType;

        @Schema(description = "资源标识")
        private String resourceKey;

        @Schema(description = "动作白名单")
        private List<String> actions;
    }
}
