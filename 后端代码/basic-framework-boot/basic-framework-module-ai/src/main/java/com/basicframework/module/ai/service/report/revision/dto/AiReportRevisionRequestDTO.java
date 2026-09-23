package com.basicframework.module.ai.service.report.revision.dto;

import com.basicframework.module.ai.domain.query.QueryScope;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表对话修改请求（服务层 DTO）。
 *
 * <p>归属（应用 + 主体 + 外部用户标识）来自**服务端会话**，请求体不提供；
 * 行范围（{@link #rowScope}）来自**授权层**（D06 契约），应用端 HTTP 请求体同样不提供——
 * 这两条决定了"数据类修订必须受控查询"不会被客户端绕过。
 */
@Data
@Accessors(chain = true)
public class AiReportRevisionRequestDTO {

    /** 报表编号（必填） */
    private Long reportId;

    /** 基础版本号（必填；候选规格以它为准） */
    private Integer baseVersionNo;

    /** 报表乐观锁版本（必填；并发修改返回 409，AT-046） */
    private Integer version;

    /** 用户的修改指令（自然语言，必填） */
    private String instruction;

    /** 模型端点编号（必填；数据类操作与展示类操作都由模型产出操作清单） */
    private Long endpointId;

    /** 本次允许重新查询的数据集编号（可选；模型只能对该数据集提数据类操作） */
    private Long datasetId;

    /** 数据集版本编号（可选；缺省取最新已发布版本） */
    private Long datasetVersionId;

    /** 本次允许的字段/指标码（可选；为空表示定义里的全部字段） */
    private List<String> allowedFieldCodes;

    /** 行范围（数据类操作必填且必须有效；来自授权层，不来自请求体） */
    private QueryScope rowScope;

    /** 来源运行标识（可选；保存时按 R04 口径校验属于当前主体） */
    private String createdByRun;
}
