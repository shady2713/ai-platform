package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报表保存请求（应用端协议层 VO）。
 *
 * <p>请求体**不提供归属字段**：应用编号、主体类型、外部用户标识由服务端从会话解析，
 * 客户端无法替他人保存（第 5 步：复制 reportId/旧版本号也绕不过归属判定）。
 */
@Schema(description = "AI 应用端 - 报表保存请求")
@Data
public class AiReportSaveReqVO {

    @Schema(description = "报表编号（新建时为空；提供则为保存新版本）")
    private Long id;

    @Schema(description = "报表标识（新建必填，创建后不可修改）")
    @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$", message = "报表标识只允许小写字母开头的小写字母、数字、下划线与连字符")
    private String code;

    @Schema(description = "报表名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 128)
    private String name;

    @Schema(description = "说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "模式：SNAPSHOT（数据冻结）/ REFRESHABLE（按当前权限重新执行）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private String mode;

    @Schema(description = "来源服务编号")
    private Long serviceId;

    @Schema(description = "来源服务发布版本编号")
    private Long releaseId;

    @Schema(description = "主题标识")
    @Size(max = 64)
    private String themeId;

    @Schema(description = "主题修订号")
    @Min(0)
    private Integer themeRevision;

    @Schema(description = "ReportSpec 契约版本（默认 1.0）")
    @Size(max = 16)
    private String schemaVersion;

    @Schema(description = "已校验的 ReportSpec（JSON）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 262_144)
    private String specJson;

    @Schema(description = "快照数据（SNAPSHOT 模式必填）")
    @Size(max = 1_048_576)
    private String dataJson;

    @Schema(description = "来源与资源依赖（JSON 数组：resourceType 取 A03 词表 + resourceKey，逐项再鉴权）")
    @Size(max = 16_000)
    private String sourcesJson;

    @Schema(description = "数据完整性：COMPLETE / PARTIAL / FAILED")
    @Size(max = 16)
    private String completeness;

    @Schema(description = "来源运行标识")
    @Size(max = 64)
    private String createdByRun;

    @Schema(description = "乐观锁版本（保存新版本必填；与当前版本不一致返回 409）")
    @Min(0)
    private Integer version;
}
