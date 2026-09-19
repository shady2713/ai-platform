package com.basicframework.module.ai.service.serviceconfig.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 评测记录入参（服务层 DTO）。
 *
 * <p>调用方只提交得分与用例数：{@code passed} 由平台按发布版本冻结的门槛判定，
 * 避免"调用方自报通过"。{@code notes} 不得包含提示词、响应正文或凭据。
 * 同一版本可记录多条评测（重跑/复检），发布预检查始终以**最新一条**为准。
 */
@Data
@Accessors(chain = true)
public class AiServiceEvaluationSaveDTO {

    /** 发布版本编号 */
    private Long releaseId;

    /** 评测得分（0-100） */
    private Integer score;

    /** 评测用例数（至少 1） */
    private Integer caseCount;

    /** 备注 */
    private String notes;
}
