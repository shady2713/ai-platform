package com.basicframework.module.ai.service.context.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 知识片段输入（S04）：检索结果由调用方按相关度排序后传入，构建器按顺序保留。 */
@Data
@Accessors(chain = true)
public class AiContextKnowledgeDTO {

    /** 片段标识（来源可追溯；不参与权限判定） */
    private String key;

    /** 片段标题 */
    private String title;

    /** 片段正文 */
    private String snippet;
}
