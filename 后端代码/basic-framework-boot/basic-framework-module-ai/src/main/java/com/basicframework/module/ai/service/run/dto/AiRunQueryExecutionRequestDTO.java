package com.basicframework.module.ai.service.run.dto;

import com.basicframework.module.ai.domain.query.QueryScope;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行侧受控查询请求（R05）：把"要按当前权限重新查一次数据"的全部输入一次说清。
 *
 * <p>行范围（{@link #rowScope}）是**授权层给出的**输入（D06 契约），不是请求体里的字段：
 * 应用端 HTTP 请求永远无法提供它，因此"数据类修订需要受控查询"不会被客户端绕过。
 */
@Data
@Accessors(chain = true)
public class AiRunQueryExecutionRequestDTO {

    /** 数据集编号（必填） */
    private Long datasetId;

    /** 数据集版本编号（可选；缺省取最新已发布版本） */
    private Long datasetVersionId;

    /** 模型端点编号（必填；规划器用它调用模型） */
    private Long endpointId;

    /** 查询问题（必填；来自用户需求或对话修订指令） */
    private String question;

    /** 本次允许的数据集集合（可选；规划器不扩大该集合） */
    private List<Long> allowedDatasetIds;

    /** 本次允许的字段/指标码（可选；为空表示定义里的全部字段） */
    private List<String> allowedFieldCodes;

    /** 行范围（必填且必须有效；空集合即拒绝，绝不退回全库） */
    private QueryScope rowScope;

    /** 来源运行标识（可选，仅用于可追溯；不参与授权判定） */
    private String runKey;
}
