package com.basicframework.module.ai.service.evaluation.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 评测样例保存（服务层 DTO，Q04）。 */
@Data
@Accessors(chain = true)
public class AiEvalCaseSaveDTO {

    /** 样例编号（更新/删除时必填） */
    private Long id;

    /** 乐观锁版本（更新/删除时必填） */
    private Integer version;

    /** 所属套件编号 */
    private Long suiteId;

    /** 样例标识（套件内唯一，创建后不可修改） */
    private String caseKey;

    /** 标题 */
    private String title;

    /** 严重级别（BLOCKER/MAJOR/MINOR） */
    private String severity;

    /** 合成问题 */
    private String question;

    /** 期望的模型/服务版本标识（可空） */
    private String expectVersion;

    /** 期望规则（JSON 数组，写入前规范化为字典序） */
    private String checksJson;

    /** 是否需要人工复核 */
    private Boolean needsReview;
}
