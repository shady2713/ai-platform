package com.basicframework.module.ai.service.report.persistence.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 报表保存请求（服务层 DTO）：模式 + 已校验规格 + 快照数据 + 来源 + 范围指纹。 */
@Data
@Accessors(chain = true)
public class AiReportSaveDTO {

    /** 报表编号（新建时为空） */
    private Long id;

    /** 报表标识（创建后不可修改） */
    private String code;

    /** 名称 */
    private String name;

    /** 说明 */
    private String description;

    /** 模式（SNAPSHOT/REFRESHABLE） */
    private String mode;

    /** 来源服务编号 */
    private Long serviceId;

    /** 来源服务发布版本编号 */
    private Long releaseId;

    /** 主题标识 */
    private String themeId;

    /** 主题修订号 */
    private Integer themeRevision;

    /** ReportSpec 契约版本 */
    private String schemaVersion;

    /** 已校验的 ReportSpec（JSON） */
    private String specJson;

    /** 快照数据（SNAPSHOT 模式必填） */
    private String dataJson;

    /** 来源与资源依赖（JSON） */
    private String sourcesJson;

    /** 数据完整性 */
    private String completeness;

    /** 来源运行标识 */
    private String createdByRun;

    /** 乐观锁版本（保存新版本时必填） */
    private Integer version;
}
